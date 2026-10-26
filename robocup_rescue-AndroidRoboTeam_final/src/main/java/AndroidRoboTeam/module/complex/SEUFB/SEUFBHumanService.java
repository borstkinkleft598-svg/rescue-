package AndroidRoboTeam.module.complex.SEUFB;

import AndroidRoboTeam.module.complex.common.LDcopTaskScheduler;
import AndroidRoboTeam.module.complex.common.LDcopWorldPlanner;
import AndroidRoboTeam.module.complex.common.LayeredRescuePlanner;
import AndroidRoboTeam.world.SEUConstants;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SEUFBHumanService extends AbstractModule {

    private Clustering clustering;
    private PathPlanning pathPlanning;
    private Set<Civilian> allCivilian;

    public Map<EntityID, SEUFBHuman> getAllHuman() {
        return allHuman;
    }

    private Map<EntityID, SEUFBHuman> allHuman;
    private Set<Civilian> targetsCivilian;
    private Set<SEUFBHuman> firstLevelCivilian;
    private Set<SEUFBHuman> topLevelCivilian;
    private Set<SEUFBHuman> secondLevelCivilian;
    private Set<SEUFBHuman> targetHumans;
    private Set<SEUFBHuman> removeHuman;
    private Set<Civilian> sendCivilian;
    private Set<Civilian> notNeedSendCivilian;
    private final boolean ldcopEnabled;
    private final boolean layeredScheduling;
    private final LDcopWorldPlanner layeredPlanner;
    private MessageManager latestMessages;
    private final int ldcopTargetLockTime;
    private final int ldcopPreemptionMargin;
    private final int ldcopLoadTime;
    private final int ldcopUnloadTime;
    private EntityID ldcopLockedTarget;
    private int ldcopLockUntil;

    public SEUFBHumanService(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData, Clustering clustering, PathPlanning pathPlanning) {
        super(ai, wi, si, moduleManager, developData);
        this.allCivilian = new HashSet<>();
        this.allHuman = new HashMap<>();
        this.targetHumans = new HashSet<>();
        this.targetsCivilian = new HashSet<>();
        this.clustering = clustering;
        this.pathPlanning = pathPlanning;
        this.removeHuman = new HashSet<>();
        this.topLevelCivilian = new HashSet<>();
        this.firstLevelCivilian = new HashSet<>();
        this.secondLevelCivilian = new HashSet<>();
        this.sendCivilian = new HashSet<>();
        this.notNeedSendCivilian = new HashSet<>();
        this.ldcopEnabled = developData.getBoolean("LDcop.enabled", true);
        this.layeredScheduling = developData.getBoolean("LDcop.layeredScheduling", true);
        this.layeredPlanner = new LDcopWorldPlanner(ai, wi, pathPlanning, developData);
        this.ldcopTargetLockTime = developData.getInteger("LDcop.targetLockTime", 5);
        this.ldcopPreemptionMargin = developData.getInteger("LDcop.preemptionMargin", 4);
        this.ldcopLoadTime = developData.getInteger("LDcop.loadTime", 1);
        this.ldcopUnloadTime = developData.getInteger("LDcop.unloadTime", 1);
        this.ldcopLockedTarget = null;
        this.ldcopLockUntil = 0;
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        this.latestMessages = messageManager;
        this.layeredPlanner.update(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        targetsCivilian.clear();
        targetHumans.clear();
        topLevelCivilian.clear();
        firstLevelCivilian.clear();
        secondLevelCivilian.clear();
        removeHuman.clear();
        Set<EntityID> changed = this.agentInfo.getChanged().getChangedEntities();
        for (EntityID entityID : changed) {
            StandardEntity entity = this.worldInfo.getEntity(entityID);
            if (entity instanceof Human && !this.allHuman.containsKey(entityID)) {
                Human human = (Human) entity;
                if ((human.isBuriednessDefined() && human.getBuriedness() > 0)
                        && (human.isHPDefined() && human.getHP() > 0)) {
                    SEUFBHuman SEUFBHuman = new SEUFBHuman(human, agentInfo, worldInfo, scenarioInfo, pathPlanning,
                            clustering);
                    this.allHuman.put(entityID, SEUFBHuman);
                }
            }
            if (entity instanceof Civilian civilian) {
                for (EntityID entityID1 : changed) {
                    StandardEntity standardEntity = this.worldInfo.getEntity(entityID1);
                    if (standardEntity instanceof AmbulanceTeam
                            && ((AmbulanceTeam) standardEntity).getPosition().equals(civilian.getPosition())) {
                        this.notNeedSendCivilian.add(civilian);
                    }
                }
                if (civilian.isBuriednessDefined() && civilian.getBuriedness() == 0 && civilian.isDamageDefined()
                        && civilian.getDamage() > 0 && civilian.isHPDefined() && civilian.getHP() > 0) {
                    if (this.notNeedSendCivilian.contains(civilian)) {
                        continue;
                    }
                    this.sendCivilian.add(civilian);
                }
            }
            if (entity instanceof AmbulanceTeam) {
                for (Civilian civilian : this.sendCivilian) {
                    if (this.notNeedSendCivilian.contains(civilian)) {
                        continue;
                    }
                    if (civilian.isBuriednessDefined() && civilian.getBuriedness() == 0 && civilian.isDamageDefined()
                            && civilian.getDamage() > 0 && civilian.isHPDefined() && civilian.getHP() > 0) {
                        messageManager.addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH, civilian));
                    }
                }
                sendCivilian.clear();
            }
        }

        for (CommunicationMessage message : messageManager
                .getReceivedMessageList(MessageCivilian.class, MessageFireBrigade.class, MessagePoliceForce.class,
                        MessageAmbulanceTeam.class)) {
            StandardEntity entity = null;
            entity = MessageUtil.reflectMessage(worldInfo,
                    (StandardMessage) message);
            if ((entity instanceof Human) && !this.allHuman.containsKey(entity.getID())) {
                Human human = (Human) entity;
                if ((human.isBuriednessDefined() && human.getBuriedness() > 0)
                        && (human.isHPDefined() && human.getHP() > 0)) {
                    SEUFBHuman SEUFBHuman = new SEUFBHuman(human, agentInfo, worldInfo, scenarioInfo, pathPlanning,
                            clustering);
                    this.allHuman.put(entity.getID(), SEUFBHuman);
                }
            }
        }

        EntityID position = this.agentInfo.getPosition();
        for (SEUFBHuman SEUFBHuman : this.allHuman.values()) {
            Human human = SEUFBHuman.getHuman();
            if ((human.isBuriednessDefined() && human.getBuriedness() <= 0)
                    || (human.isHPDefined() && human.getHP() <= 0)) {
                removeHuman.add(SEUFBHuman);
            } else if (human.getPosition().getValue() == position.getValue() && !changed.contains(human.getID())) {
                removeHuman.add(SEUFBHuman);
            }
        }
        for (SEUFBHuman SEUFBHuman : removeHuman) {
            this.allHuman.remove(SEUFBHuman.getHuman().getID());
        }
        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());
        for (SEUFBHuman SEUFBHuman : this.allHuman.values()) {
            SEUFBHuman.updateInfo(messageManager);

            if (SEUFBHuman.isReachable() && SEUFBHuman.isCanRescue()) {
                if ((agentInfo.getTime() < 100 && !(SEUFBHuman.getHuman() instanceof Civilian)) ||
                        ((SEUFBHuman.getHuman() instanceof Civilian) && !(SEUFBHuman.getDeadTime() > 200))) {
                    if (SEUFBHuman.getDistanceToMe() < 50000 && SEUFBHuman.getDeadTime() < 60) {
                        this.topLevelCivilian.add(SEUFBHuman);
                    }
                    if (SEUFBHuman.getDeadTime() < 30 && SEUFBHuman.getDistanceToMe() < 240000) {
                        this.firstLevelCivilian.add(SEUFBHuman);
                    } else if (SEUFBHuman.getDeadTime() < 60 && SEUFBHuman.getDistanceToMe() < 120000) {
                        this.secondLevelCivilian.add(SEUFBHuman);
                    }
                }
                this.targetHumans.add(SEUFBHuman);
            }
        }

        return this;
    }

    public EntityID getTargetByDistance() {
        EntityID target = null;
        TreeSet<SEUFBHuman> objects3 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects3.addAll(this.topLevelCivilian.stream().filter(this::allowsFallback).toList());
        if (!objects3.isEmpty()) {
            target = objects3.first().getHuman().getID();
            return target;
        }
        TreeSet<SEUFBHuman> objects = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects.addAll(this.firstLevelCivilian.stream().filter(this::allowsFallback).toList());
        if (!objects.isEmpty()) {
            target = objects.first().getHuman().getID();
            return target;
        }
        TreeSet<SEUFBHuman> objects1 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects1.addAll(this.secondLevelCivilian.stream().filter(this::allowsFallback).toList());
        if (!objects1.isEmpty()) {
            target = objects1.first().getHuman().getID();
        }
        TreeSet<SEUFBHuman> objects2 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects2.addAll(this.targetHumans.stream().filter(this::allowsFallback).toList());
        if (!objects2.isEmpty()) {
            target = objects2.first().getHuman().getID();
        }
        return target;
    }

    /**
     * Rescue-layer selection. The follow-up transport estimate represents the
     * ordering edge rescue -> load/transport, so a human is selected only when
     * both layers are expected to finish within the survival time window.
     */
    public EntityID getTargetByLDcop() {
        if (!this.ldcopEnabled) {
            return null;
        }
        if (this.layeredScheduling) {
            return this.layeredPlanner.select(LayeredRescuePlanner.Role.RESCUE, this.latestMessages);
        }

        int now = this.agentInfo.getTime();
        List<LDcopTaskScheduler.Candidate> candidates = new ArrayList<>();
        for (SEUFBHuman rescueHuman : this.targetHumans) {
            Human human = rescueHuman.getHuman();
            if (human == null || !human.isHPDefined() || human.getHP() <= 0
                    || !human.isPositionDefined()) {
                continue;
            }

            int travelTime = Math.max(0, (int) Math.ceil(rescueHuman.getArriveTime()));
            int rescueTime = rescueHuman.getEstimatedRescueTime();
            int transportTime = human instanceof Civilian
                    ? this.estimateTransportTime(human.getPosition())
                            + this.ldcopLoadTime + this.ldcopUnloadTime
                    : 0;
            int latestFinish = now + Math.max(1, (int) Math.floor(rescueHuman.getDeadTime()));

            candidates.add(new LDcopTaskScheduler.Candidate(
                    human.getID(),
                    LDcopTaskScheduler.Layer.RESCUE,
                    travelTime,
                    rescueTime,
                    transportTime,
                    latestFinish,
                    rescueHuman.isReachable()));
        }

        LDcopTaskScheduler.Selection selection = LDcopTaskScheduler.select(
                candidates,
                this.ldcopLockedTarget,
                this.ldcopLockUntil,
                now,
                this.ldcopTargetLockTime,
                this.ldcopPreemptionMargin);
        if (selection == null) {
            this.ldcopLockedTarget = null;
            this.ldcopLockUntil = 0;
            return null;
        }

        this.ldcopLockedTarget = selection.target();
        this.ldcopLockUntil = selection.lockUntil();
        return selection.target();
    }

    public boolean isLayeredSchedulingEnabled() {
        return this.ldcopEnabled && this.layeredScheduling;
    }

    private boolean allowsFallback(SEUFBHuman human) {
        return !isLayeredSchedulingEnabled() || layeredPlanner.allowsFallback(
                LayeredRescuePlanner.Role.RESCUE, human.getHuman().getID());
    }
    public void confirmLDcopTarget(EntityID target) {
        if (isLayeredSchedulingEnabled()) layeredPlanner.confirmTarget(
                LayeredRescuePlanner.Role.RESCUE, target, latestMessages);
    }

    private int estimateTransportTime(EntityID humanPosition) {
        int minimumDistance = Integer.MAX_VALUE;
        for (EntityID refuge : this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE)) {
            int distance = this.worldInfo.getDistance(humanPosition, refuge);
            if (distance >= 0) {
                minimumDistance = Math.min(minimumDistance, distance);
            }
        }
        if (minimumDistance == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.max(1,
                (int) Math.ceil((double) minimumDistance / SEUConstants.MEAN_VELOCITY_OF_MOVING));
    }

}
