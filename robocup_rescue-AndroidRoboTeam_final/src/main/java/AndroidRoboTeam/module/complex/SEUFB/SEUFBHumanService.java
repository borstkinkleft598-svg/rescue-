package AndroidRoboTeam.module.complex.SEUFB;

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
import rescuecore2.standard.entities.StandardEntity;
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
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        targetsCivilian.clear();
        targetHumans.clear();
        topLevelCivilian.clear();
        firstLevelCivilian.clear();
        secondLevelCivilian.clear();
        removeHuman.clear();
        sendCivilian.clear();
        notNeedSendCivilian.clear();
        Set<EntityID> changed = this.agentInfo.getChanged().getChangedEntities();
        Set<EntityID> ambulancePositions = new HashSet<>();
        for (EntityID entityID : changed) {
            StandardEntity entity = this.worldInfo.getEntity(entityID);
            if (entity instanceof AmbulanceTeam ambulanceTeam && ambulanceTeam.isPositionDefined()) {
                ambulancePositions.add(ambulanceTeam.getPosition());
            }
        }

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
                if (civilian.isPositionDefined() && ambulancePositions.contains(civilian.getPosition())) {
                    this.notNeedSendCivilian.add(civilian);
                }
                if (civilian.isBuriednessDefined() && civilian.getBuriedness() == 0 && civilian.isDamageDefined()
                        && civilian.getDamage() > 0 && civilian.isHPDefined() && civilian.getHP() > 0) {
                    if (this.notNeedSendCivilian.contains(civilian)) {
                        continue;
                    }
                    this.sendCivilian.add(civilian);
                }
            }
        }

        // Broadcast actionable civilians after scanning every changed entity so the
        // result is independent of HashSet iteration order and local AT visibility.
        for (Civilian civilian : this.sendCivilian) {
            if (!this.notNeedSendCivilian.contains(civilian)
                    && civilian.isBuriednessDefined() && civilian.getBuriedness() == 0
                    && civilian.isDamageDefined() && civilian.getDamage() > 0
                    && civilian.isHPDefined() && civilian.getHP() > 0) {
                messageManager.addMessage(new MessageCivilian(true, StandardMessagePriority.HIGH, civilian));
            }
        }
        this.sendCivilian.clear();

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
        objects3.addAll(this.topLevelCivilian);
        if (!objects3.isEmpty()) {
            target = objects3.first().getHuman().getID();
            return target;
        }
        TreeSet<SEUFBHuman> objects = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects.addAll(this.firstLevelCivilian);
        if (!objects.isEmpty()) {
            target = objects.first().getHuman().getID();
            return target;
        }
        TreeSet<SEUFBHuman> objects1 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects1.addAll(this.secondLevelCivilian);
        if (!objects1.isEmpty()) {
            target = objects1.first().getHuman().getID();
        }
        TreeSet<SEUFBHuman> objects2 = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects2.addAll(this.targetHumans);
        if (!objects2.isEmpty()) {
            target = objects2.first().getHuman().getID();
        }
        return target;
    }

}
