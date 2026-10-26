package AndroidRoboTeam.module.complex.common;

import AndroidRoboTeam.world.SEUConstants;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.action.fire.ActionRescue;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.util.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;
import static AndroidRoboTeam.module.complex.common.LayeredRescuePlanner.*;

/** Bridges real RRS state to the layered planner. Uses ordinary action/target
 * status messages as short leases; no simulator modifications or side-channel. */
public final class LDcopWorldPlanner {
    private record Claim(int target, int expires, boolean working) {}
    private record Observation(int hp, int damage, int time) {}
    private final AgentInfo agent;
    private final WorldInfo world;
    private final PathPlanning path;
    private final int maxCandidates, maxTasks, interval, defaultDamage, load, unload, lease;
    private final int freshness, crewMaximum, heavyThreshold, assistTravel;
    private final int progressTimeout, retryDelay;
    private final boolean progressLeases, readyFirst, assistDiversion;
    private final double travelMultiplier;
    private final Map<Integer, Claim> rescueClaims = new HashMap<>(), transportClaims = new HashMap<>();
    private final Map<Long, Integer> travelCache = new HashMap<>();
    private final Map<Integer, Observation> observations = new HashMap<>();
    private final Map<Integer, Integer> lastHeard = new HashMap<>();
    private final Map<Integer, DispatchProgress> progress = new HashMap<>();
    private final Map<Integer, Integer> retryAfter = new HashMap<>(), refugeCache = new HashMap<>();
    private final Set<Integer> loadedAmbulances = new HashSet<>();
    private Integer announcedTarget;
    private Integer assistingTarget;
    private boolean announcedWorking;
    private int lastPlan = -1000, lastReceive = -1, lastAnnounce = -1000;
    private Plan current = new Plan(List.of(), 0);

    public LDcopWorldPlanner(AgentInfo agent, WorldInfo world, PathPlanning path, DevelopData data) {
        this.agent = agent;
        this.world = world;
        this.path = path;
        maxCandidates = Math.max(1, Math.min(32, data.getInteger("LDcop.maxCandidates", 12)));
        maxTasks = Math.max(1, Math.min(6, data.getInteger("LDcop.maxTasksPerAgent", 3)));
        interval = Math.max(1, data.getInteger("LDcop.replanInterval", 5));
        lease = Math.max(interval + 2, data.getInteger("LDcop.claimTTL", 7));
        freshness = Math.max(1, data.getInteger("LDcop.workerFreshness", 7));
        crewMaximum = Math.max(1, Math.min(6, data.getInteger("LDcop.rescueCrewLimit", 3)));
        heavyThreshold = Math.max(1, data.getInteger("LDcop.assistBuriedness", 40));
        assistTravel = Math.max(0, data.getInteger("LDcop.assistMaxTravel", 8));
        progressLeases = data.getBoolean("LDcop.progressLeases", true);
        readyFirst = data.getBoolean("LDcop.readyTransportFirst", true);
        assistDiversion = data.getBoolean("LDcop.assistDiversion", true);
        progressTimeout = Math.max(lease + 1, data.getInteger("LDcop.progressTimeout", 12));
        retryDelay = Math.max(1, data.getInteger("LDcop.stalledRetryDelay", 5));
        defaultDamage = Math.max(1, data.getInteger("LDcop.defaultDamage", 20));
        load = Math.max(1, data.getInteger("LDcop.loadTime", 1));
        unload = Math.max(1, data.getInteger("LDcop.unloadTime", 1));
        travelMultiplier = Math.max(1, data.getInteger("LDcop.travelPercent", 130)) / 100.0;
    }

    public void update(MessageManager messages) {
        int now = agent.getTime();
        if (now == lastReceive) return;
        lastReceive = now;
        travelCache.clear(); refugeCache.clear();
        retryAfter.entrySet().removeIf(e -> e.getValue() <= now);
        rescueClaims.entrySet().removeIf(e -> e.getValue().expires() < now);
        transportClaims.entrySet().removeIf(e -> e.getValue().expires() < now);
        if (messages != null) for (CommunicationMessage cm : messages.getReceivedMessageList(MessageFireBrigade.class)) {
            MessageFireBrigade m = (MessageFireBrigade) cm;
            MessageUtil.reflectMessage(world, (StandardMessage) m); // generic overload preserves direct observations
            receive(rescueClaims, m.getAgentID(), m.getTargetID(),
                    m.getAction() == MessageFireBrigade.ACTION_MOVE || m.getAction() == MessageFireBrigade.ACTION_RESCUE,
                    m.getAction() == MessageFireBrigade.ACTION_RESCUE, m.getPosition(),
                    (!m.isHPDefined() || m.getHP() > 0) && (!m.isBuriednessDefined() || m.getBuriedness() == 0), now);
        }
        if (messages != null) for (CommunicationMessage cm : messages.getReceivedMessageList(MessageAmbulanceTeam.class)) {
            MessageAmbulanceTeam m = (MessageAmbulanceTeam) cm;
            MessageUtil.reflectMessage(world, (StandardMessage) m);
            receive(transportClaims, m.getAgentID(), m.getTargetID(),
                    m.getAction() == MessageAmbulanceTeam.ACTION_MOVE || m.getAction() == MessageAmbulanceTeam.ACTION_LOAD,
                    m.getAction() == MessageAmbulanceTeam.ACTION_LOAD, m.getPosition(),
                    (!m.isHPDefined() || m.getHP() > 0) && (!m.isBuriednessDefined() || m.getBuriedness() == 0), now);
        }
        // Record freshness, rather than extending a stale HP estimate every planning cycle.
        loadedAmbulances.clear();
        for (StandardEntity entity : world.getEntitiesOfType(StandardEntityURN.CIVILIAN,
                StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.POLICE_FORCE)) {
            Human h = (Human) entity;
            if (h instanceof Civilian && h.isPositionDefined()
                    && world.getEntity(h.getPosition()) instanceof AmbulanceTeam)
                loadedAmbulances.add(h.getPosition().getValue());
            if (agent.getChanged().getChangedEntities().contains(h.getID())) lastHeard.put(h.getID().getValue(), now);
            if (!h.isHPDefined()) continue;
            observe(h, now);
        }
        // Direct observations can update movement even when radio packets were lost.
        for (int actor : new ArrayList<>(progress.keySet())) {
            if (!agent.getChanged().getChangedEntities().contains(new EntityID(actor))) continue;
            StandardEntity e = world.getEntity(new EntityID(actor));
            if (e instanceof Human h && h.isPositionDefined()) {
                DispatchProgress p = progress.get(actor);
                updateProgress(actor, p.target(), h.getPosition(), p.working(), now);
            }
        }
        progress.keySet().removeIf(id -> !rescueClaims.containsKey(id) && !transportClaims.containsKey(id));
    }

    private void receive(Map<Integer, Claim> claims, EntityID owner, EntityID target, boolean relevant,
                         boolean working, EntityID position, boolean available, int now) {
        if (owner == null) return;
        lastHeard.put(owner.getValue(), now);
        if (owner.equals(agent.getID())) return;
        if (relevant && available && target != null && world.getEntity(target) instanceof Human) {
            claims.put(owner.getValue(), new Claim(target.getValue(), now + lease, working));
            updateProgress(owner.getValue(), target.getValue(), position, working, now);
        } else if (!relevant || !available) {
            claims.remove(owner.getValue());
            progress.remove(owner.getValue());
        } else {
            // A normal area-target MOVE is not a new human reservation. Update
            // movement and clear the old "working" state, without renewing its TTL.
            Claim old = claims.get(owner.getValue());
            if (old != null) {
                claims.put(owner.getValue(), new Claim(old.target(), old.expires(), false));
                updateProgress(owner.getValue(), old.target(), position, false, now);
            }
        }
    }

    public EntityID select(Role role, MessageManager messages) {
        update(messages);
        int now = agent.getTime();
        if (agent.someoneOnBoard() != null || !(agent.me() instanceof Human self) || !alive(self)
                || (self.isBuriednessDefined() && self.getBuriedness() > 0)) return null;
        releaseStalledOwnClaim(role, now);
        if (readyFirst && role == Role.TRANSPORT) {
            EntityID pickup = readyTransport(now);
            if (pickup != null) return pickup;
        }
        boolean valid = !current.slots().isEmpty() && validHead(current.slots().get(0).task(), role, now);
        if (assistDiversion && role == Role.RESCUE && now % interval == 0) {
            EntityID helper = assistance(now);
            if (helper != null) return helper;
        }
        if (!valid && role == Role.RESCUE) {
            if (assistingTarget != null) {
                StandardEntity e = world.getEntity(new EntityID(assistingTarget));
                if (e instanceof Human h && alive(h) && h.isBuriednessDefined() && h.getBuriedness() > 0
                        && world.getEntity(h.getPosition()) instanceof Area) {
                    Observation observation = observe(h, now);
                    if (now < latestFinish(observation.time(), observation.hp(), observation.damage())
                            && allowsFallback(Role.RESCUE, h.getID())
                            && exactTravel(agent.getPosition(), h.getPosition()) < UNREACHABLE) return h.getID();
                }
                assistingTarget = null;
            }
            EntityID assistance = assistance(now);
            if (assistance != null) return assistance;
        }
        if (now - lastPlan >= interval || !valid) {
            travelCache.clear();
            List<Worker> workers = workers(now, role);
            List<Victim> victims = victims(now, role);
            Result result = LayeredRescuePlanner.plan(workers, victims, now, this::travel,
                    load, unload, maxTasks, owners(rescueClaims), owners(transportClaims), executingTargets());
            Map<Integer, Plan> plans = role == Role.RESCUE ? result.rescue() : result.transport();
            Plan proposed = plans.getOrDefault(agent.getID().getValue(), new Plan(List.of(), now));
            current = proposed;
            lastPlan = now;
            System.out.println("[LDCOP-v4] time=" + now + " agent=" + agent.getID()
                    + " role=" + role + " candidates=" + victims.size() + " schedule="
                    + current.slots().stream().map(s -> s.task().id() + "@" + s.start() + "-" + s.finish()).toList());
        }
        if (current.slots().isEmpty()) return null;
        Task head = current.slots().get(0).task();
        EntityID target = new EntityID(head.id());
        if (!allowsFallback(role, target)) {
            current = new Plan(List.of(), now);
            return null;
        }
        // Validate the first leg with the real path planner before dispatching an action.
        int firstLeg = exactTravel(agent.getPosition(), new EntityID(head.pickup()));
        if (firstLeg >= UNREACHABLE || (long) Math.max(now + firstLeg, head.release())
                + head.duration() + head.followUp() > head.deadline()) {
            System.out.println("[LDCOP-REJECT] time=" + now + " agent=" + agent.getID()
                    + " role=" + role + " target=" + target + " reason="
                    + (firstLeg >= UNREACHABLE ? "unreachable_first_leg" : "deadline_after_route"));
            current = new Plan(List.of(), now);
            return null;
        }
        return target;
    }

    /** Publish the detector's FINAL choice, including local work/fallbacks, not an unused proposal. */
    public void confirmTarget(Role role, EntityID target, MessageManager messages) {
        int now = agent.getTime(), me = agent.getID().getValue();
        Map<Integer, Claim> claims = role == Role.RESCUE ? rescueClaims : transportClaims;
        StandardEntity entity = target == null ? null : world.getEntity(target);
        if (!(entity instanceof Human human) || !alive(human)
                || !(world.getEntity(human.getPosition()) instanceof Area)
                || world.getEntity(human.getPosition()) instanceof Refuge) {
            claims.remove(me); announcedTarget = null;
            progress.remove(me);
            assistingTarget = null;
            current = new Plan(List.of(), now);
            return;
        }
        if (!Objects.equals(assistingTarget, target.getValue())) assistingTarget = null;
        if (!current.slots().isEmpty() && current.slots().get(0).task().id() != target.getValue())
            current = new Plan(List.of(), now);
        boolean working = role == Role.RESCUE && human.getPosition().equals(agent.getPosition())
                && human.isBuriednessDefined() && human.getBuriedness() > 0
                && agent.getExecutedAction(now - 1) instanceof ActionRescue rescue
                && target.equals(rescue.getTarget());
        claims.put(me, new Claim(target.getValue(), now + lease, working));
        updateProgress(me, target.getValue(), agent.getPosition(), working, now);
        if (messages != null && (!Objects.equals(announcedTarget, target.getValue())
                || working != announcedWorking || now - lastAnnounce >= interval)) {
            if (agent.me() instanceof FireBrigade fb)
                messages.addMessage(new MessageFireBrigade(true, StandardMessagePriority.NORMAL, fb,
                        working ? MessageFireBrigade.ACTION_RESCUE : MessageFireBrigade.ACTION_MOVE, target));
            else if (agent.me() instanceof AmbulanceTeam at)
                messages.addMessage(new MessageAmbulanceTeam(true, StandardMessagePriority.NORMAL, at,
                        MessageAmbulanceTeam.ACTION_MOVE, target));
            announcedTarget = target.getValue(); lastAnnounce = now; announcedWorking = working;
            System.out.println("[LDCOP-INTENT] time=" + now + " agent=" + me + " role=" + role
                    + " target=" + target + " working=" + working);
        }
    }

    private boolean validHead(Task task, Role role, int now) {
        StandardEntity e = world.getEntity(new EntityID(task.id()));
        if (!(e instanceof Human h) || !alive(h) || !(world.getEntity(h.getPosition()) instanceof Area)
                || world.getEntity(h.getPosition()) instanceof Refuge) return false;
        Integer owner = owners(role == Role.RESCUE ? rescueClaims : transportClaims).get(task.id());
        if (owner != null && owner != agent.getID().getValue()) return false;
        Observation observation = observe(h, now);
        return h.getPosition().getValue() == task.pickup()
                && now < Math.min(task.deadline(), latestFinish(observation.time(), observation.hp(), observation.damage()))
                && (role != Role.RESCUE || (h.isBuriednessDefined() && h.getBuriedness() > 0));
    }

    private Map<Integer, Integer> owners(Map<Integer, Claim> claims) {
        Map<Integer, Integer> owners = new HashMap<>();
        for (int owner : activeClaimers(claims, null)) {
            Claim claim = claims.get(owner);
            owners.merge(claim.target(), owner, (a, b) -> closerClaimant(a, b, claim.target()));
        }
        return owners;
    }

    private int closerClaimant(int a, int b, int target) {
        Human victim = (Human) world.getEntity(new EntityID(target));
        if (victim == null || !victim.isPositionDefined()) return Math.min(a,b);
        int x = approximate(workerPosition(a), victim.getPosition().getValue());
        int y = approximate(workerPosition(b), victim.getPosition().getValue());
        return x == y ? Math.min(a,b) : x < y ? a : b;
    }

    private List<Worker> workers(int now, Role role) {
        List<Worker> result = new ArrayList<>();
        for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.AMBULANCE_TEAM)) {
            Human h = (Human) e;
            if (!h.isPositionDefined() || !(world.getEntity(h.getPosition()) instanceof Area)
                    || !h.isHPDefined() || h.getHP() <= 0
                    || (h.isBuriednessDefined() && h.getBuriedness() > 0)) continue;
            // Loaded ambulances are unavailable until delivery; do not assign a second passenger.
            boolean loaded = h instanceof AmbulanceTeam && hasPassenger(h.getID().getValue());
            if (!loaded) result.add(new Worker(h.getID().getValue(), h instanceof FireBrigade ? Role.RESCUE : Role.TRANSPORT,
                    h.getPosition().getValue(), now));
        }
        Set<Integer> fresh = new HashSet<>();
        for (Worker worker : result) if (isFresh(worker.id(), now)) fresh.add(worker.id());
        return ExecutionDispatchPolicy.dispatchWorkers(result, agent.getID().getValue(), role, fresh,
                activeClaimers(rescueClaims, null));
    }

    private List<Victim> victims(int now, Role role) {
        List<ExecutionDispatchPolicy.Candidate> candidates = new ArrayList<>();
        for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.CIVILIAN, StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.POLICE_FORCE)) {
            Human h = (Human) e;
            if (!alive(h) || h.getID().equals(agent.getID()) || !(world.getEntity(h.getPosition()) instanceof Area)
                    || world.getEntity(h.getPosition()) instanceof Refuge || !h.isBuriednessDefined()) continue;
            boolean civilian = h instanceof Civilian;
            if (!civilian && h.getBuriedness() <= 0) continue;
            if (civilian && h.getBuriedness() <= 0 && (!h.isDamageDefined() || h.getDamage() <= 0)) continue;
            Observation observation = observe(h, now);
            int deadline = latestFinish(observation.time(), observation.hp(), observation.damage());
            if (deadline <= now) continue;
            int refuge = civilian ? nearestRefuge(h.getPosition()) : -1;
            if (civilian && refuge < 0) continue;
            Set<Integer> crew = workingCrew(h.getID().getValue());
            if (role == Role.RESCUE) crew.add(agent.getID().getValue());
            int rescueTime = ExecutionDispatchPolicy.rescueDuration(h.getBuriedness(), crew.size());
            Victim victim = new Victim(h.getID().getValue(), h.getPosition().getValue(), refuge,
                    h.getBuriedness(), deadline, civilian, rescueTime);
            int movement = approximate(agent.getPosition().getValue(), victim.position());
            int transport = civilian ? travel(victim.position(), refuge) : 0;
            int service = role == Role.RESCUE ? rescueTime : load + transport + unload;
            int tail = role == Role.RESCUE && civilian ? load + transport + unload : 0;
            candidates.add(new ExecutionDispatchPolicy.Candidate(victim, movement, service, tail));
        }
        Claim executing = (role == Role.RESCUE ? rescueClaims : transportClaims).get(agent.getID().getValue());
        Integer executingTarget = null;
        if (executing != null) executingTarget = executing.target();
        Integer head = ExecutionDispatchPolicy.protectedHead(current, executingTarget);
        return ExecutionDispatchPolicy.candidates(candidates, now, maxCandidates, head, role,
                new HashSet<>(owners(rescueClaims).keySet()));
    }

    private boolean isFresh(int actor, int now) {
        return actor == agent.getID().getValue() || now - lastHeard.getOrDefault(actor, -100000) <= freshness;
    }
    private void updateProgress(int actor, int target, EntityID position, boolean working, int now) {
        StandardEntity e = world.getEntity(new EntityID(target));
        StandardEntity worker = world.getEntity(new EntityID(actor));
        if (worker instanceof Human h && h.isPositionDefined()
                && agent.getChanged().getChangedEntities().contains(h.getID())) position = h.getPosition();
        if (!(e instanceof Human victim) || !victim.isPositionDefined() || position == null) return;
        int distance = world.getDistance(position, victim.getPosition());
        progress.put(actor, DispatchProgress.update(progress.get(actor), target, position.getValue(),
                distance, working, now));
    }
    private int workerPosition(int actor) {
        if (actor == agent.getID().getValue()) return agent.getPosition().getValue();
        DispatchProgress p = progress.get(actor);
        StandardEntity e = world.getEntity(new EntityID(actor));
        if (e instanceof Human h && h.isPositionDefined()
                && agent.getChanged().getChangedEntities().contains(h.getID())) return h.getPosition().getValue();
        if (p != null) return p.position();
        return e instanceof Human h && h.isPositionDefined() ? h.getPosition().getValue() : -1;
    }
    private boolean hasPassenger(int actor) {
        if (actor == agent.getID().getValue()) return agent.someoneOnBoard() != null;
        return loadedAmbulances.contains(actor);
    }
    private void releaseStalledOwnClaim(Role role, int now) {
        if (!progressLeases) return;
        int me = agent.getID().getValue();
        DispatchProgress p = progress.get(me);
        if (p == null || p.productive(now, progressTimeout)) return;
        // Never abandon an immediate physical rescue/load because a radio clock expired.
        StandardEntity e = world.getEntity(new EntityID(p.target()));
        if (e instanceof Human h && h.isPositionDefined() && h.getPosition().equals(agent.getPosition())) return;
        retryAfter.put(p.target(), now + retryDelay);
        (role == Role.RESCUE ? rescueClaims : transportClaims).remove(me);
        progress.remove(me); current = new Plan(List.of(), now); assistingTarget = null;
        System.out.println("[LDCOP-STALL] time=" + now + " agent=" + me + " role=" + role
                + " target=" + p.target() + " retryAfter=" + (now + retryDelay));
    }
    private EntityID readyTransport(int now) {
        List<Human> ready = new ArrayList<>();
        for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
            Human h = (Human) e;
            if (!alive(h) || !h.isBuriednessDefined() || h.getBuriedness() != 0) continue;
            StandardEntity area = world.getEntity(h.getPosition());
            if (!(area instanceof Area) || area instanceof Refuge) continue;
            boolean injured = h.isDamageDefined() && h.getDamage() > 0;
            boolean unsafe = area instanceof Building b && b.isBrokennessDefined() && b.getBrokenness() > 0;
            // Do not spend the global pickup priority on distant healthy people
            // who may self-evacuate. Preserve the original immediate local load.
            if (!injured && (!unsafe || !h.getPosition().equals(agent.getPosition()))) continue;
            if (h.getPosition().equals(agent.getPosition())
                    && !agent.getChanged().getChangedEntities().contains(h.getID())) continue;
            if (allowsFallback(Role.TRANSPORT, h.getID())) ready.add(h);
        }
        ready.sort(Comparator.comparingInt((Human h) -> approximate(agent.getPosition().getValue(), h.getPosition().getValue()))
                .thenComparingInt(h -> h.getID().getValue()));
        // Same-area loading does not depend on a speculative deadline/refuge estimate.
        if (!ready.isEmpty() && ready.get(0).getPosition().equals(agent.getPosition())) {
            Human local = ready.stream().filter(h -> h.getPosition().equals(agent.getPosition()))
                    .min(Comparator.comparingInt((Human h) -> {
                        Observation o = observe(h, now); return latestFinish(o.time(), o.hp(), o.damage());
                    }).thenComparingInt(h -> h.getID().getValue())).orElseThrow();
            return local.getID();
        }
        Claim own = transportClaims.get(agent.getID().getValue());
        LinkedHashSet<Human> shortlist = new LinkedHashSet<>(ready.subList(0, Math.min(6, ready.size())));
        if (own != null) ready.stream().filter(h -> h.getID().getValue() == own.target()).findFirst().ifPresent(shortlist::add);
        List<ReadyTransportPolicy.Pickup> pickups = new ArrayList<>();
        for (Human h : shortlist) {
            int leg = exactTravel(agent.getPosition(), h.getPosition());
            int refuge = nearestRefuge(h.getPosition());
            if (leg >= UNREACHABLE || refuge < 0) continue;
            int tail = travel(h.getPosition().getValue(), refuge);
            if (tail >= UNREACHABLE) continue;
            Observation o = observe(h, now);
            int deadline = latestFinish(o.time(), o.hp(), o.damage());
            long finish = (long) now + leg + load + tail + unload;
            if (finish <= Integer.MAX_VALUE) pickups.add(new ReadyTransportPolicy.Pickup(h.getID().getValue(),
                    leg, (int) finish, deadline));
        }
        Integer executing = null;
        if (own != null) executing = own.target();
        Integer chosen = ReadyTransportPolicy.choose(pickups, executing, 4);
        if (chosen != null && !Objects.equals(announcedTarget, chosen))
            System.out.println("[LDCOP-PICKUP] time=" + now + " agent=" + agent.getID() + " target=" + chosen);
        return chosen == null ? null : new EntityID(chosen);
    }
    private Map<Integer, Integer> executingTargets() {
        Map<Integer, Integer> result = new HashMap<>();
        for (Map<Integer, Claim> claims : List.of(rescueClaims, transportClaims)) {
            for (int actor : activeClaimers(claims, null)) {
                if (actor != agent.getID().getValue()) result.put(actor, claims.get(actor).target());
            }
        }
        return result;
    }
    private Set<Integer> activeClaimers(Map<Integer, Claim> claims, Integer target) {
        Set<Integer> result = new HashSet<>();
        claims.forEach((actor, claim) -> {
            StandardEntity e = world.getEntity(new EntityID(actor));
            if (claim.expires() >= agent.getTime() && isFresh(actor, agent.getTime())
                    && e instanceof Human h && alive(h) && (!h.isBuriednessDefined() || h.getBuriedness() == 0)
                    && (!(h instanceof AmbulanceTeam) || !hasPassenger(actor))
                    && (!progressLeases || (progress.containsKey(actor)
                    && progress.get(actor).productive(agent.getTime(), progressTimeout)))
                    && (target == null || claim.target() == target)) result.add(actor);
        });
        return result;
    }
    private Set<Integer> workingCrew(int target) {
        Set<Integer> crew = activeClaimers(rescueClaims, target);
        StandardEntity victim = world.getEntity(new EntityID(target));
        crew.removeIf(id -> !rescueClaims.get(id).working() || !(victim instanceof Human v)
                || workerPosition(id) != v.getPosition().getValue());
        return crew;
    }
    private int approximate(int from, int to) {
        if (from == to) return 0;
        int distance = world.getDistance(new EntityID(from), new EntityID(to));
        return distance < 0 ? UNREACHABLE : Math.max(1, (int) Math.ceil((double) distance / SEUConstants.MEAN_VELOCITY_OF_MOVING));
    }

    public boolean allowsFallback(Role role, EntityID target) {
        StandardEntity entity = world.getEntity(target);
        if (!(entity instanceof Human h) || !alive(h)) return false;
        // Never refuse an immediate local rescue/load opportunity because of radio estimates.
        if (h.getPosition().equals(agent.getPosition())) return true;
        if (retryAfter.getOrDefault(target.getValue(), -1) > agent.getTime()) return false;
        Map<Integer, Claim> claims = role == Role.RESCUE ? rescueClaims : transportClaims;
        Set<Integer> peers = activeClaimers(claims, target.getValue());
        if (role == Role.RESCUE) {
            int limit = crewLimit(h);
            // A much closer newcomer must not be excluded by a distant first claimant.
            boolean newcomer = !peers.contains(agent.getID().getValue());
            List<ExecutionDispatchPolicy.Arrival> roster = new ArrayList<>();
            for (int id : peers) roster.add(new ExecutionDispatchPolicy.Arrival(id,
                    approximate(workerPosition(id), h.getPosition().getValue())));
            if (newcomer) roster.add(new ExecutionDispatchPolicy.Arrival(agent.getID().getValue(),
                    approximate(agent.getPosition().getValue(), h.getPosition().getValue()) + 2));
            return ExecutionDispatchPolicy.retainedClaimant(agent.getID().getValue(), roster, limit);
        }
        int ours = approximate(agent.getPosition().getValue(), h.getPosition().getValue());
        return peers.stream().noneMatch(id -> {
            int cost = approximate(workerPosition(id), h.getPosition().getValue());
            return cost < ours || (cost == ours && id < agent.getID().getValue());
        });
    }

    private int crewLimit(Human human) {
        int buried = human.isBuriednessDefined() ? human.getBuriedness() : 0;
        Observation o = observe(human, agent.getTime());
        long budget = (long) latestFinish(o.time(), o.hp(), o.damage()) - agent.getTime();
        if (human instanceof Civilian) {
            int refuge = nearestRefuge(human.getPosition());
            if (refuge >= 0) budget -= (long) load + approximate(human.getPosition().getValue(), refuge) + unload;
        }
        int window = (int) Math.max(1, Math.min(Integer.MAX_VALUE, budget));
        return ExecutionDispatchPolicy.crewLimit(buried, heavyThreshold, crewMaximum, window);
    }

    private EntityID assistance(int now) {
        Claim own = rescueClaims.get(agent.getID().getValue());
        if (own != null && own.expires() >= now && (!assistDiversion || own.working())) return null;
        Set<Integer> busy = activeClaimers(rescueClaims, null);
        EntityID best = null; int bestDeadline = Integer.MAX_VALUE, bestTravel = UNREACHABLE;
        for (int target : new TreeSet<>(owners(rescueClaims).keySet())) {
            StandardEntity entity = world.getEntity(new EntityID(target));
            if (!(entity instanceof Human h) || !alive(h) || !h.isBuriednessDefined()) continue;
            Set<Integer> crew = workingCrew(target), committed = activeClaimers(rescueClaims, target);
            if (crew.isEmpty()) continue;
            List<ExecutionDispatchPolicy.Arrival> nearby = new ArrayList<>();
            for (StandardEntity e : world.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
                Human worker = (Human) e; int id = worker.getID().getValue();
                if (!alive(worker) || !isFresh(id, now) || (worker.isBuriednessDefined() && worker.getBuriedness() > 0)) continue;
                if (id != agent.getID().getValue() && busy.contains(id)
                        && rescueClaims.get(id).target() != target) continue;
                int movement = id == agent.getID().getValue() ? exactTravel(agent.getPosition(), h.getPosition())
                        : approximate(workerPosition(id), h.getPosition().getValue());
                nearby.add(new ExecutionDispatchPolicy.Arrival(id, movement));
            }
            if (!ExecutionDispatchPolicy.mayAssist(agent.getID().getValue(), h.getBuriedness(), crewLimit(h),
                    assistTravel, h.getBuriedness() < heavyThreshold ? 1 : 4, committed, crew.size(), nearby)) continue;
            int movement = exactTravel(agent.getPosition(), h.getPosition());
            Observation o = observe(h, now); int deadline = latestFinish(o.time(), o.hp(), o.damage());
            int remaining = Math.max(0, h.getBuriedness() - movement * crew.size());
            int assisted = movement + ExecutionDispatchPolicy.rescueDuration(remaining, crew.size() + 1);
            if ((long) now + assisted > deadline) continue;
            if (own != null && own.target() != target) {
                StandardEntity old = world.getEntity(new EntityID(own.target()));
                if (old instanceof Human previous && alive(previous)) {
                    int oldTravel = approximate(agent.getPosition().getValue(), previous.getPosition().getValue());
                    Observation before = observe(previous, now);
                    int oldSlack = latestFinish(before.time(), before.hp(), before.damage()) - now - oldTravel
                            - previous.getBuriedness();
                    int saving = ExecutionDispatchPolicy.rescueDuration(h.getBuriedness(), crew.size()) - assisted;
                    if (!DispatchProgress.mayDivertToAssist(oldTravel, movement, saving, oldSlack, 4)) continue;
                }
            }
            if (best == null || deadline < bestDeadline || (deadline == bestDeadline && movement < bestTravel)) {
                best = h.getID(); bestDeadline = deadline; bestTravel = movement;
            }
        }
        if (best != null) {
            assistingTarget = best.getValue();
            System.out.println("[LDCOP-ASSIST] time=" + now + " agent=" + agent.getID() + " target=" + best);
        }
        return best;
    }

    private boolean alive(Human h) {
        return h.isHPDefined() && h.getHP() > 0 && h.isPositionDefined();
    }
    private Observation observe(Human human, int now) {
        int damage = human.isDamageDefined() ? Math.max(0, human.getDamage()) : defaultDamage;
        int id = human.getID().getValue();
        Observation previous = observations.get(id);
        if (previous == null || previous.hp() != human.getHP() || previous.damage() != damage
                || agent.getChanged().getChangedEntities().contains(human.getID())) {
            previous = new Observation(human.getHP(), damage, now);
            observations.put(id, previous);
        }
        return previous;
    }
    static int latestFinish(int observedAt, int hp, int damage) {
        if (hp <= 0) return observedAt;
        if (damage == 0) return Integer.MAX_VALUE;
        return (int) Math.min(Integer.MAX_VALUE,
                (long) observedAt + Math.max(0, (hp - 1L) / Math.max(1, damage)));
    }
    private int nearestRefuge(EntityID position) {
        Integer cached = refugeCache.get(position.getValue());
        if (cached != null) return cached;
        int best = -1, minimum = UNREACHABLE;
        for (EntityID refuge : world.getEntityIDsOfType(StandardEntityURN.REFUGE)) {
            int cost = travel(position.getValue(), refuge.getValue());
            if (cost < minimum || (cost == minimum && (best < 0 || refuge.getValue() < best))) {
                minimum = cost; best = refuge.getValue();
            }
        }
        int result = minimum >= UNREACHABLE ? -1 : best;
        refugeCache.put(position.getValue(), result);
        return result;
    }
    private int travel(int from, int to) {
        if (from == to) return 0;
        long key = ((long) from << 32) ^ (to & 0xffffffffL);
        return travelCache.computeIfAbsent(key, ignored -> {
            if (from == agent.getPosition().getValue()) return exactTravel(new EntityID(from), new EntityID(to));
            int distance = world.getDistance(new EntityID(from), new EntityID(to));
            if (distance < 0) return UNREACHABLE;
            return Math.max(1, (int) Math.ceil(distance * travelMultiplier / SEUConstants.MEAN_VELOCITY_OF_MOVING));
        });
    }
    private int exactTravel(EntityID from, EntityID to) {
        if (from.equals(to)) return 0;
        List<EntityID> route = path.setFrom(from).setDestination(to).calc().getResult();
        if (route == null || route.isEmpty()) return UNREACHABLE;
        double distance = path.getDistance();
        if (!Double.isFinite(distance) || distance < 0) return UNREACHABLE;
        return Math.max(1, (int) Math.ceil(distance * travelMultiplier / SEUConstants.MEAN_VELOCITY_OF_MOVING));
    }
}
