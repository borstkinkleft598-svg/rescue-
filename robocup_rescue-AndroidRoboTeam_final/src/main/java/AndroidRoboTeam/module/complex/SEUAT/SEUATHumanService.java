package AndroidRoboTeam.module.complex.SEUAT;

import AndroidRoboTeam.module.complex.common.LDcopTaskScheduler;
import AndroidRoboTeam.module.complex.common.LDcopWorldPlanner;
import AndroidRoboTeam.module.complex.common.LayeredRescuePlanner;
import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.world.SEUWorldService;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SEUATHumanService extends AbstractModule {

    private Clustering clustering;

    private PathPlanning pathPlanning;

    private Map<Civilian, Integer> civTimeMap;

    private Set<Civilian> targetsCivilian;

    private MessageManager messageManager;

    private Set<Building> notNeedBuilding;

    private SEUWorldService SEUWorldService;

    private Set<Civilian> sendCivilian;

    private Set<EntityID> sendCommandPolice;

    private int MAX_DISTANCE;

    private final boolean ldcopEnabled;
    private final boolean layeredScheduling;
    private final LDcopWorldPlanner layeredPlanner;
    private final int ldcopTargetLockTime;
    private final int ldcopPreemptionMargin;
    private final int ldcopLoadTime;
    private final int ldcopUnloadTime;
    private final int ldcopDefaultDamage;
    private EntityID ldcopLockedTarget;
    private int ldcopLockUntil;

    public SEUATHumanService(SEUWorldService SEUWorldService, AgentInfo ai, WorldInfo wi, ScenarioInfo si,
            ModuleManager moduleManager, DevelopData developData,
            Clustering clustering, PathPlanning pathPlanning) {
        super(ai, wi, si, moduleManager, developData);
        this.civTimeMap = new HashMap<>();
        this.targetsCivilian = new HashSet<>();
        this.notNeedBuilding = new HashSet<>();
        this.clustering = clustering;
        this.pathPlanning = pathPlanning;

        this.sendCivilian = new HashSet<>();
        this.sendCommandPolice = new HashSet<>();
        this.SEUWorldService = SEUWorldService;

        this.MAX_DISTANCE = (int) (Math.sqrt(SEUWorldService.getMapHeight() * SEUWorldService.getMapWidth()
                / si.getScenarioAgentsAt()) * 1.5);

        this.ldcopEnabled = developData.getBoolean("LDcop.enabled", true);
        this.layeredScheduling = developData.getBoolean("LDcop.layeredScheduling", true);
        this.layeredPlanner = new LDcopWorldPlanner(ai, wi, pathPlanning, developData);
        this.ldcopTargetLockTime = developData.getInteger("LDcop.targetLockTime", 5);
        this.ldcopPreemptionMargin = developData.getInteger("LDcop.preemptionMargin", 4);
        this.ldcopLoadTime = developData.getInteger("LDcop.loadTime", 1);
        this.ldcopUnloadTime = developData.getInteger("LDcop.unloadTime", 1);
        this.ldcopDefaultDamage = developData.getInteger("LDcop.defaultDamage", 20);
        this.ldcopLockedTarget = null;
        this.ldcopLockUntil = 0;
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        this.layeredPlanner.update(messageManager);
        if (this.messageManager == null) {
            this.messageManager = messageManager;
        }
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.targetsCivilian.clear();
        this.notNeedBuilding.clear();

        for (EntityID entityID : this.SEUWorldService.getCiviliansSeen()) {

            Civilian civilian = (Civilian) worldInfo.getEntity(entityID);
            if (civilian.isHPDefined() && civilian.getHP() > 0) {

                StandardEntity posEntity = worldInfo.getEntity(civilian.getPosition());
                if (this.agentInfo.getPosition().equals(posEntity.getID())) {

                    continue;
                }

                if (!(posEntity instanceof Refuge)
                        && posEntity instanceof Building building) {

                    if (building.isBrokennessDefined() && building.getBrokenness() > 0) {

                        List<EntityID> path = this.pathPlanning
                                .setFrom(this.agentInfo.getPosition())
                                .setDestination(posEntity.getID()).calc().getResult();
                        if (path == null || path.isEmpty()) {

                            this.sendCommandPolice.add(posEntity.getID());
                        }
                    }
                }
            }
        }

        Set<EntityID> changedIDs = this.agentInfo.getChanged().getChangedEntities();
        for (EntityID changedID : changedIDs) {
            StandardEntity changedSE = this.worldInfo.getEntity(changedID);

            if (changedSE instanceof Civilian civ) {

                this.civTimeMap.put(civ, this.agentInfo.getTime());
                if (civ.isBuriednessDefined() && civ.getBuriedness() > 0
                        && civ.isHPDefined() && civ.getHP() > 0) {

                    boolean flag = true;
                    for (EntityID entityID1 : changedIDs) {

                        StandardEntity se = this.worldInfo.getEntity(entityID1);
                        if (se instanceof FireBrigade fb
                                && fb.getPosition().equals(civ.getPosition())) {
                            flag = false;
                        }
                    }
                    if (flag) {

                        this.sendCivilian.add(civ);
                    }
                }
            }

            if (changedSE instanceof FireBrigade) {

                for (Civilian civilian : this.sendCivilian) {
                    if (civilian.isBuriednessDefined() && civilian.getBuriedness() > 0
                            && civilian.isHPDefined() && civilian.getHP() > 0) {

                        messageManager.addMessage(
                                new MessageCivilian(false, StandardMessagePriority.HIGH, civilian));
                    }
                }

                sendCivilian.clear();
            } else if (changedSE instanceof PoliceForce) {

                for (EntityID entityID1 : this.sendCommandPolice) {
                    messageManager.addMessage(
                            new CommandPolice(false, StandardMessagePriority.HIGH, null,
                                    entityID1, CommandPolice.ACTION_CLEAR));
                }
                this.sendCommandPolice.clear();
            }
        }

        for (CommunicationMessage message : messageManager
                .getReceivedMessageList(MessageCivilian.class)) {
            StandardEntity entity = MessageUtil.reflectMessage(worldInfo,
                    (StandardMessage) message);

            if (entity instanceof Civilian civilian) {
                if (civilian.isPositionDefined()) {

                    this.civTimeMap.put(civilian, this.agentInfo.getTime());
                }
            }
        }

        EntityID myPosEntityID = this.agentInfo.getPosition();
        Set<Civilian> removeCivilian = new HashSet<>();
        for (Civilian civilian : this.civTimeMap.keySet()) {
            if (civilian.getPosition().getValue() == myPosEntityID.getValue()
                    && !changedIDs.contains(civilian.getID())) {
                removeCivilian.add(civilian);
            }
        }

        for (CommunicationMessage communicationMessage : messageManager
                .getReceivedMessageList(MessageAmbulanceTeam.class)) {
            MessageAmbulanceTeam ma = (MessageAmbulanceTeam) communicationMessage;
            EntityID target = ma.getTargetID();
            AmbulanceTeam ambulanceTeam = (AmbulanceTeam) this.worldInfo.getEntity(ma.getAgentID());
            StandardEntity standardEntity = this.worldInfo.getEntity(target);
            if (standardEntity instanceof Building building) {
                double distance1 = this.pathPlanning.setFrom(ambulanceTeam.getPosition())
                        .setDestination(building.getID()).calc().getDistance();
                double distance2 = this.pathPlanning.setFrom(this.agentInfo.getPosition())
                        .setDestination(building.getID()).calc().getDistance();
                if (distance2 > distance1) {

                    this.notNeedBuilding.add(building);
                }
            }
            if (ma.getAction() == MessageAmbulanceTeam.ACTION_LOAD) {

                Civilian civilian = (Civilian) this.worldInfo.getEntity(ma.getTargetID());
                if (civilian != null && civilian.isPositionDefined()) {
                    removeCivilian.add(civilian);
                }
            }
        }

        for (Civilian civilian : removeCivilian) {
            this.civTimeMap.remove(civilian);
        }

        int indexOfAgent = clustering.getClusterIndex(agentInfo.getID());
        for (Civilian civ : this.civTimeMap.keySet()) {
            if (civ.isHPDefined() && civ.getHP() > 0) {

                StandardEntity civPosEntity = this.worldInfo.getPosition(civ);
                if (civPosEntity instanceof Building building) {

                    if ((civ.isDamageDefined() && civ.getDamage() > 0
                            || building.isBrokennessDefined() && building.getBrokenness() > 0)) {

                        if (civ.isBuriednessDefined() && civ.getBuriedness() == 0
                                || (civ.getBuriedness() < 60 && this.needLoad(civ))) {
                            if (!(civPosEntity instanceof Refuge)) {

                                int indexOfHuman = clustering.getClusterIndex(civPosEntity.getID());
                                if (indexOfAgent == indexOfHuman) {

                                    this.targetsCivilian.add(civ);
                                } else {

                                    double distance = this.pathPlanning
                                            .setFrom(this.agentInfo.getPosition())
                                            .setDestination(civPosEntity.getID()).calc().getDistance();
                                    if (isReachable(civ)) {
                                        if (distance < this.MAX_DISTANCE) {
                                            this.targetsCivilian.add(civ);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Set<Civilian> hasATCiv = new HashSet<>();
        for (Civilian civilian : this.targetsCivilian) {
            if (this.isATPresent(civilian)
                    && !this.agentInfo.getPosition().equals(civilian.getPosition())) {
                hasATCiv.add(civilian);
            }
        }
        this.targetsCivilian.removeAll(hasATCiv);

        return this;
    }

    public Boolean isFBPresent(Civilian civilian) {
        for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
            FireBrigade fireBrigade = (FireBrigade) standardEntity;
            if (fireBrigade.getPosition().getValue() == civilian.getPosition().getValue()) {
                return true;
            }
        }
        return false;
    }

    public Boolean needLoad(Civilian civilian) {
        int fbNum = 0;
        int rescueTime = 0;
        int arriveTime = 0;
        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
            MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
            if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_RESCUE
                    && messageFireBrigade.getTargetID().equals(civilian.getID())) {
                fbNum++;
            }
        }
        double distance = this.pathPlanning.setFrom(this.agentInfo.getPosition())
                .setDestination(civilian.getID()).calc().getDistance();
        arriveTime = (int) Math.ceil(distance / SEUConstants.MEAN_VELOCITY_OF_MOVING);
        if (fbNum != 0 && civilian.isBuriednessDefined()) {
            rescueTime = civilian.getBuriedness() / fbNum;
        } else {
            return false;
        }
        return (rescueTime - arriveTime < 10);
    }

    public Boolean isATPresent(Civilian civilian) {
        int numOfCivilian = 0;
        int numOfAmbulanceTeam = 0;
        for (Civilian civilian1 : this.targetsCivilian) {
            if (civilian1.getPosition().equals(civilian.getPosition())) {
                numOfCivilian++;
            }
        }
        for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.AMBULANCE_TEAM)) {
            AmbulanceTeam ambulanceTeam = (AmbulanceTeam) standardEntity;
            if (ambulanceTeam.getID().equals(this.agentInfo.getID())) {
                continue;
            }
            if (ambulanceTeam.getPosition().getValue() == civilian.getPosition().getValue()) {
                numOfAmbulanceTeam++;
            }
        }
        return numOfAmbulanceTeam >= numOfCivilian;
    }

    public EntityID getTargetByDistance() {
        EntityID target = null;
        TreeSet<Civilian> objects = new TreeSet<>(
                (o1, o2) -> Double.compare(
                        this.worldInfo.getDistance(o1.getID(), this.agentInfo.getID()),
                        this.worldInfo.getDistance(o2.getID(), this.agentInfo.getID())));
        objects.addAll(this.targetsCivilian);

        for (Civilian civilian : objects) {
            if (this.isReachable(civilian) && allowsFallback(civilian)) {
                target = civilian.getID();
                break;
            }

        }
        return target;
    }

    /**
     * Rolling L-DCOP selection for the ambulance layer. Buried humans are
     * represented as RESCUE candidates with a predecessor wait; unburied
     * humans are TRANSPORT candidates. In both cases the candidate is accepted
     * only when loading, transport and unloading can finish before death.
     */
    public EntityID getTargetByLDcop() {
        if (!this.ldcopEnabled) {
            return null;
        }
        if (this.layeredScheduling) {
            return this.layeredPlanner.select(LayeredRescuePlanner.Role.TRANSPORT, this.messageManager);
        }

        int now = this.agentInfo.getTime();
        List<LDcopTaskScheduler.Candidate> candidates = new ArrayList<>();
        for (Civilian civilian : this.targetsCivilian) {
            if (!this.isHumanAliveAndLocated(civilian)) {
                continue;
            }

            int travelTime = this.estimateTravelTime(
                    this.agentInfo.getPosition(), civilian.getPosition());
            if (travelTime == Integer.MAX_VALUE) {
                continue;
            }

            int rescueWait = civilian.isBuriednessDefined() && civilian.getBuriedness() > 0
                    ? this.estimateRemainingRescueTime(civilian)
                    : 0;
            int transportTime = this.estimateTransportTime(civilian.getPosition());
            int latestFinish = this.estimateLatestFinish(civilian, now);
            LDcopTaskScheduler.Layer layer = rescueWait > 0
                    ? LDcopTaskScheduler.Layer.RESCUE
                    : LDcopTaskScheduler.Layer.TRANSPORT;

            candidates.add(new LDcopTaskScheduler.Candidate(
                    civilian.getID(),
                    layer,
                    travelTime,
                    rescueWait + this.ldcopLoadTime,
                    transportTime + this.ldcopUnloadTime,
                    latestFinish,
                    true));
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

    private boolean allowsFallback(Civilian human) {
        return !isLayeredSchedulingEnabled() || layeredPlanner.allowsFallback(
                LayeredRescuePlanner.Role.TRANSPORT, human.getID());
    }
    public void confirmLDcopTarget(EntityID target) {
        if (isLayeredSchedulingEnabled()) layeredPlanner.confirmTarget(
                LayeredRescuePlanner.Role.TRANSPORT, target, messageManager);
    }

    private boolean isHumanAliveAndLocated(Civilian civilian) {
        return civilian != null
                && civilian.isHPDefined()
                && civilian.getHP() > 0
                && civilian.isPositionDefined()
                && !(this.worldInfo.getEntity(civilian.getPosition()) instanceof Refuge);
    }

    private int estimateTravelTime(EntityID from, EntityID destination) {
        if (from == null || destination == null) {
            return Integer.MAX_VALUE;
        }
        if (from.equals(destination)) {
            return 0;
        }
        List<EntityID> path = this.pathPlanning.setFrom(from)
                .setDestination(destination).calc().getResult();
        if (path == null || path.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        double distance = this.pathPlanning.getDistance();
        return Math.max(1, (int) Math.ceil(distance / SEUConstants.MEAN_VELOCITY_OF_MOVING));
    }

    private int estimateRemainingRescueTime(Civilian civilian) {
        Set<EntityID> rescuers = new HashSet<>();
        for (CommunicationMessage cm : this.messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
            MessageFireBrigade message = (MessageFireBrigade) cm;
            if (message.getAction() == MessageFireBrigade.ACTION_RESCUE
                    && civilian.getID().equals(message.getTargetID())) {
                rescuers.add(message.getAgentID());
            }
        }
        for (StandardEntity entity : this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
            FireBrigade rescueAgent = (FireBrigade) entity;
            if (rescueAgent.isPositionDefined()
                    && rescueAgent.getPosition().equals(civilian.getPosition())) {
                rescuers.add(rescueAgent.getID());
            }
        }
        int buriedness = civilian.isBuriednessDefined() ? civilian.getBuriedness() : 0;
        return rescuers.isEmpty()
                ? Integer.MAX_VALUE / 4
                : Math.max(1, (int) Math.ceil((double) buriedness / rescuers.size()));
    }

    private int estimateTransportTime(EntityID civilianPosition) {
        int minimumDistance = Integer.MAX_VALUE;
        for (EntityID refuge : this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE)) {
            int distance = this.worldInfo.getDistance(civilianPosition, refuge);
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

    private int estimateLatestFinish(Civilian civilian, int now) {
        int damage = civilian.isDamageDefined() && civilian.getDamage() > 0
                ? civilian.getDamage()
                : Math.max(1, this.ldcopDefaultDamage);
        int remainingLife = Math.max(1,
                (int) Math.ceil((double) civilian.getHP() / damage));
        return now + remainingLife;
    }

    public EntityID getTargetByTimeAndBuriedness() {
        Set<Civilian> targetSet = new HashSet<>();
        for (Civilian civ : this.civTimeMap.keySet()) {
            if (civ.isBuriednessDefined() && civ.getBuriedness() > 0) {
                int fbNum = 0;
                int maxTime = 1;
                for (StandardEntity se : this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
                    FireBrigade fb = (FireBrigade) se;
                    if (fb.getPosition().equals(civ.getPosition())) {
                        fbNum++;
                    }
                }
                if (fbNum != 0) {
                    maxTime = fbNum;
                }

                if (this.civTimeMap.get(civ) + (civ.getBuriedness() / maxTime) < this.agentInfo.getTime()) {
                    targetSet.add(civ);
                }
            }
        }
        EntityID target = null;
        TreeSet<Civilian> objects = new TreeSet<>(
                (o1, o2) -> Double.compare(
                        this.worldInfo.getDistance(o1.getID(), this.agentInfo.getID()),
                        this.worldInfo.getDistance(o2.getID(), this.agentInfo.getID())));
        objects.addAll(targetSet);
        int repeat = 0;
        for (Civilian civilian : objects) {
            if (this.isReachable(civilian) && repeat < 50 && allowsFallback(civilian)) {
                target = civilian.getID();
                break;
            }
            repeat--;
        }
        return target;
    }

    public Boolean isReachable(Civilian civilian) {
        EntityID from = this.agentInfo.getPosition();
        EntityID destination = civilian.getPosition();
        if (from.equals(destination)) {
            return true;
        }
        List<EntityID> result = this.pathPlanning.setFrom(from)
                .setDestination(civilian.getID()).calc().getResult();
        return result != null;
    }

}
