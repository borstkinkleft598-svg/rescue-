package AndroidRoboTeam.module.complex.paper;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

/** Only selected by config/paper-module.cfg; competition detectors are unchanged. */
public final class PaperHumanDetector extends HumanDetector {
    private record Health(int hp, int damage, int observedAt) {}
    private final VirtualTransportLedger ledger = new VirtualTransportLedger();
    private final Map<Integer, Health> health = new HashMap<>();
    private final PaperLdcopEngine engine;
    private final PaperLdcopEngine.Policy policy;
    private final int relayPort, defaultDamage;
    private final double speed;
    private PaperExchange exchange;
    private List<Task> queue = List.of();
    private EntityID result;
    private int calculatedAt = -1, targetSwitches;
    private int decisionCount;
    private long totalDecisionMs, maxDecisionMs;
    private Integer previousTarget;
    private List<Integer> lastMembers = List.of();

    public PaperHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        if (!dd.getBoolean("Paper.enabled", false))
            throw new IllegalStateException("paper detector requires Paper.enabled=true");
        policy = dd.getBoolean("Paper.dcopBaseline", false) ? PaperLdcopEngine.Policy.DCOP : PaperLdcopEngine.Policy.LDCOP;
        engine = new PaperLdcopEngine(dd.getInteger("Paper.bmsIterations", 32), dd.getInteger("Paper.maxTasks", 0),
                dd.getInteger("Paper.unservedPenalty", 10000));
        relayPort = Integer.parseInt(System.getenv().getOrDefault("PAPER_LDCOP_PORT", "28033"));
        defaultDamage = Math.max(1, dd.getInteger("Paper.defaultDamage", 20));
        speed = dd.getInteger("Paper.speed", 31445);
        if (speed <= 0) throw new IllegalArgumentException("Paper.speed must be positive");
    }
    @Override public EntityID getTarget() { return result; }
    @Override public HumanDetector calc() {
        int now = agentInfo.getTime();
        if (calculatedAt == now) return this;
        calculatedAt = now;
        result = null;
        long started = System.nanoTime();
        try {
            List<VirtualTransportLedger.Observation> observations = new ArrayList<>();
            List<Victim> victims = new ArrayList<>();
            for (StandardEntity e : worldInfo.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
                Civilian civilian = (Civilian) e;
                if (!civilian.isHPDefined() || !civilian.isPositionDefined() || !civilian.isBuriednessDefined()) continue;
                int refuge = refuge(civilian.getPosition());
                int duration = refuge < 0 ? UNREACHABLE : travel(civilian.getPosition().getValue(), refuge);
                observations.add(new VirtualTransportLedger.Observation(civilian.getID().getValue(),
                        civilian.getBuriedness(), civilian.getHP(), civilian.isDamageDefined() ? civilian.getDamage() : 0,
                        duration, refuge));
                int damage = civilian.isDamageDefined() && civilian.getDamage() > 0 ? civilian.getDamage() : defaultDamage;
                Health old = health.get(civilian.getID().getValue());
                if (old == null || old.hp() != civilian.getHP() || old.damage() != damage
                        || (agentInfo.getChanged() != null && agentInfo.getChanged().getChangedEntities().contains(civilian.getID()))) {
                    old = new Health(civilian.getHP(), damage, now);
                    health.put(civilian.getID().getValue(), old);
                }
                int deadline = (int) Math.min(Integer.MAX_VALUE, (long) old.observedAt() + Math.max(0, (old.hp() - 1L) / damage));
                if (civilian.getHP() > 0 && civilian.getBuriedness() > 0 && refuge >= 0
                        && worldInfo.getEntity(civilian.getPosition()) instanceof Area)
                    victims.add(new Victim(civilian.getID().getValue(), civilian.getPosition().getValue(),
                            civilian.getBuriedness(), deadline, duration, refuge));
            }
            ledger.observe(now, observations);
            List<Integer> members = worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE).stream()
                    .map(Human.class::cast).filter(h -> h.isPositionDefined())
                    .filter(h -> !h.isHPDefined() || h.getHP() > 0)
                    .filter(h -> !h.isBuriednessDefined() || h.getBuriedness() == 0)
                    .map(h -> h.getID().getValue()).sorted().toList();
            int me = agentInfo.getID().getValue();
            lastMembers = members;
            if (now < scenarioInfo.getKernelAgentsIgnoreuntil() || !members.contains(me)) {
                writeMetrics(now, 0, 0);
                return this;
            }
            if (now == scenarioInfo.getKernelAgentsIgnoreuntil()) System.out.println("[PAPER-MEMBERS] actor=" + me
                    + " members=" + members + " victims=" + victims.size());
            if (exchange == null) exchange = new SocketPaperExchange(relayPort, System.getenv("PAPER_LDCOP_TOKEN"), 45000);
            queue = policy == PaperLdcopEngine.Policy.LDCOP
                    ? PaperLdcopEngine.refresh(queue, victims, agentInfo.getPosition().getValue(), now, this::travel) : List.of();
            PaperLdcopEngine.Outcome outcome = engine.plan(new State(me, agentInfo.getPosition().getValue(), victims, queue),
                    members, now, policy, this::travel, exchange);
            queue = outcome.queue();
            if (!queue.isEmpty()) result = new EntityID(queue.get(0).id());
            Integer target = result == null ? null : result.getValue();
            if (previousTarget != null && target != null && !previousTarget.equals(target)
                    && victims.stream().anyMatch(v -> v.id() == previousTarget && v.work() > 0)) targetSwitches++;
            previousTarget = target;
            long duration = (System.nanoTime() - started) / 1_000_000;
            decisionCount++;
            totalDecisionMs += duration;
            maxDecisionMs = Math.max(maxDecisionMs, duration);
            writeMetrics(now, duration, outcome.conflicts());
            System.out.println("[PAPER-LDCOP] time=" + now + " actor=" + me + " policy=" + policy
                    + " rounds=" + outcome.assignmentRounds() + " ms=" + duration
                    + " queue=" + queue.stream().map(Task::id).toList());
        } catch (Exception e) {
            System.err.println("[PAPER-ERROR] actor=" + agentInfo.getID() + " time=" + now + " " + e);
            // An invalid experiment must be marked failed, never silently become the competition algorithm.
            throw new IllegalStateException("paper experiment synchronization/planning failed", e);
        }
        return this;
    }
    private int travel(int from, int to) {
        if (from == to) return 0;
        int distance = worldInfo.getDistance(new EntityID(from), new EntityID(to));
        return distance < 0 ? UNREACHABLE : Math.max(1, (int) Math.ceil(distance / speed));
    }
    private int refuge(EntityID position) {
        int best = -1, time = UNREACHABLE;
        for (EntityID id : worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE)) {
            int candidate = travel(position.getValue(), id.getValue());
            if (candidate < time || (candidate == time && (best < 0 || id.getValue() < best))) {
                time = candidate; best = id.getValue();
            }
        }
        return best;
    }
    private void writeMetrics(int time, long duration, int conflicts) throws IOException {
        String directory = System.getenv("PAPER_METRICS_DIR");
        if (directory == null) return;
        Path folder = Path.of(directory);
        Files.createDirectories(folder);
        String name = "agent-" + agentInfo.getID().getValue();
        String json = "{\"time\":" + time + ",\"actor\":" + agentInfo.getID().getValue()
                + ",\"policy\":\"" + policy + "\",\"cohort\":" + ledger.cohort().size()
                + ",\"rescued\":" + ledger.rescued() + ",\"virtualTransported\":" + ledger.transported()
                + ",\"unknownArrivals\":" + ledger.entries().stream().filter(e -> e.status() == VirtualTransportLedger.Status.UNKNOWN).count()
                + ",\"targetSwitches\":" + targetSwitches + ",\"lastDecisionMs\":" + duration
                + ",\"activeActors\":" + lastMembers
                + ",\"bmsConflicts\":" + conflicts + ",\"decisionCount\":" + decisionCount
                + ",\"totalDecisionMs\":" + totalDecisionMs + ",\"maxDecisionMs\":" + maxDecisionMs + "}";
        Path temporary = folder.resolve(name + ".tmp");
        Files.writeString(temporary, json);
        Files.move(temporary, folder.resolve(name + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
