package AndroidRoboTeam.module.complex.SEUPF;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;

import static java.util.Comparator.comparing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;

import AndroidRoboTeam.world.SEUConstants;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageAmbulanceTeam;
import adf.core.agent.communication.standard.bundle.information.MessageCivilian;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.RoadDetector;
import adf.core.debug.DefaultLogger;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.CIVILIAN;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_FORCE;

import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;

public class SEURoadDetector extends RoadDetector {

    private EntityID result = null;

    private final boolean VOICE = false;
    private final boolean RADIO = true;

    final private int HELP_BURIED = 5;

    final private int HELP_BLOCKADE = 6;

    private GuidelineCreator guidelineCreator;

    private Map<EntityID, Line2D> guidelineMap;

    private PathPlanning pathPlanning;

    private Clustering clustering;

    private Collection<EntityID> clusterEntityIDs = new HashSet<>();

    private boolean hasArrivedCluster = false;
    private Integer clusterRange = 0;
    private Point2D clusterCenter = null;
    private EntityID clusterCenterID = null;

    private Map<EntityID, Integer> targetPriorityMap = new HashMap<>();
    private Set<EntityID> messageTargets = new HashSet<>();

    private Set<EntityID> passableRoads = new HashSet<>();

    private EntityID nearestRefugeID = null;

    private boolean isNeedClearOtherRoad = true;

    private boolean isInitial = true;

    private boolean hasArrivedNearestRefuge = false;

    private boolean isOutClusterIgnorant = true;

    private final int OPEN_CLUSTER_TIME = 2;

    private final int TIME_INSTANT_THRESHOLD = 4;

    private final int CLEAR_OTHER_ROAD_PF_COUNT_THRESHOLD = 5;

    private final int CLEAR_OTHER_ROAD_PF_DISTANCE = 30000;

    private final int GUIDELINE_WIDTH = 10;

    private final Logger roadDetectorLogger = DefaultLogger.getLogger("RoadDetector/SEURoadDetector");

    private final Logger personalLogger = DefaultLogger.getLogger("RoadDetector/" + this.agentInfo.getID().toString());

    public SEURoadDetector(
            AgentInfo ai, WorldInfo wi, ScenarioInfo si,
            ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);

        this.pathPlanning = mm.getModule("SampleRoadDetector.PathPlanning");

        this.clustering = mm.getModule("SampleRoadDetector.Clustering");

        this.guidelineCreator = mm.getModule("GuidelineCreator.Default");

        this.registerModule(this.pathPlanning);
        this.registerModule(this.clustering);
        this.result = null;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public RoadDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        this.clustering.precompute(precomputeData);
        this.guidelineCreator.precompute(precomputeData);
        return this;
    }

    @Override
    public RoadDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.clustering.resume(precomputeData);
        this.guidelineCreator.resume(precomputeData);
        this.guidelineMap = guidelineCreator.getGuidelineMap();

        this.isNeedClearOtherRoad = this.needClearOtherRoad(this.CLEAR_OTHER_ROAD_PF_COUNT_THRESHOLD);

        return this;
    }

    @Override
    public RoadDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.clustering.preparate();
        this.guidelineCreator.preparate();
        this.guidelineMap = guidelineCreator.getGuidelineMap();

        this.isNeedClearOtherRoad = this.needClearOtherRoad(this.CLEAR_OTHER_ROAD_PF_COUNT_THRESHOLD);
        return this;
    }

    @Override
    public RoadDetector calc() {

        if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
            personalLogger.debug("Time: " + this.agentInfo.getTime());
            personalLogger.debug("Result: " + this.result);
        }

        if (isInitial) {
            this.isInitial = false;

            if (this.clustering != null) {

                Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(
                        this.clustering.getClusterIndex(this.agentInfo.getID()));
                clusterEntityIDs = this.clustering.getClusterEntityIDs(
                        this.clustering.getClusterIndex(this.agentInfo.getID()));

                if (clusterEntities != null && !clusterEntities.isEmpty()) {
                    double x_sum = 0, y_sum = 0, number = 0;
                    for (StandardEntity standardEntity : clusterEntities) {
                        if (standardEntity instanceof Area) {
                            Area area = (Area) standardEntity;
                            x_sum += area.getX();
                            y_sum += area.getY();
                            number++;
                        }
                        if (standardEntity instanceof Refuge) {

                        }
                    }

                    this.clusterCenter = new Point2D(x_sum / number, y_sum / number);
                    int min_dis = Integer.MAX_VALUE;
                    for (StandardEntity standardEntity : clusterEntities) {
                        if (standardEntity instanceof Area) {
                            int sum = (int) this.getDistance((Area) standardEntity, this.clusterCenter);
                            if (sum > this.clusterRange) {
                                this.clusterRange = sum;
                            }
                            if (sum < min_dis) {
                                min_dis = sum;
                                this.clusterCenterID = standardEntity.getID();
                            }
                        }
                    }
                }
            }

            this.nearestRefugeID = this.getNearestRefugeID();

            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                roadDetectorLogger.debug("PF: " + this.agentInfo.getID() + " nearestRefugeID: " + this.nearestRefugeID);
            }
        }

        if (this.isIdle()) {
            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("In idle");
            }
            return this;
        }

        this.processChangedEntities();

        if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
            personalLogger.debug(targetPriorityMap);
        }

        Area area = this.agentInfo.getPositionArea();
        if (area instanceof Road road) {
            if (this.isRoadGuidelinePassable(road)) {
                this.passableRoads.add(road.getID());
            }
        }

        EntityID posEntityID = this.agentInfo.getPosition();
        StandardEntity posEntity = this.worldInfo.getEntity(posEntityID);

        if (this.nearestRefugeID == null) {

            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("nearestRefugeID is null");
            }
            this.hasArrivedNearestRefuge = true;
        }

        if (!this.hasArrivedNearestRefuge) {
            if (this.isStart(0)) {

                List<EntityID> path = this.getPath(true, posEntityID, nearestRefugeID);
                if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                    personalLogger.debug("NearestRefuge: " + path);
                }
                if (path.isEmpty()) {

                    this.hasArrivedNearestRefuge = true;
                } else {

                    this.result = path.getLast();
                }
            }
            if (this.result != null && this.result.equals(nearestRefugeID)) {
                if (posEntityID.equals(nearestRefugeID)) {

                    this.hasArrivedNearestRefuge = true;
                } else {

                    return this;
                }
            }
        }

        targetPriorityMap.keySet().removeAll(this.passableRoads);

        List<EntityID> zeroPriorityRoads = this.targetPriorityMap.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
        if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
            personalLogger.debug("0 Priority：" + zeroPriorityRoads);
        }
        if (this.result != null && zeroPriorityRoads.contains(this.result)) {

            if (this.result.getValue() == posEntityID.getValue()) {
                if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                    personalLogger.debug("I have reached: " + this.result);
                }
                if (posEntity instanceof Road road && this.isRoadGuidelinePassable(road)) {
                    if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                        personalLogger.debug("The road is passable: " + road.getID());
                    }
                    this.targetPriorityMap.remove(this.result);
                    this.passableRoads.add(this.result);
                    this.result = null;
                } else {

                    return this;
                }
            } else {

                return this;
            }
        } else if (!zeroPriorityRoads.isEmpty()) {

            Optional<EntityID> opt = zeroPriorityRoads.stream()
                    .min(Comparator.comparingInt(entityID -> {
                        if (entityID == null)
                            return Integer.MAX_VALUE;
                        return this.getPathDistance(entityID, this.agentInfo.getID());
                    }));
            return this.setAccessibleResult(opt.get());
        }

        if (this.clusterEntityIDs.contains(posEntityID)) {
            this.hasArrivedCluster = true;
            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("I have arrived at cluster center: " + this.clusterCenterID);
            }
        }
        if (!this.hasArrivedCluster) {
            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("正在前往聚类中心: " + this.clusterCenterID);
            }
            this.result = this.clusterCenterID;
            return this;
        }

        final int maxPriority = this.targetPriorityMap.values()
                .stream()
                .max(Integer::compare)
                .orElse(-1);
        for (int i = 1; i <= maxPriority; i++) {
            final int finalI = i;
            List<EntityID> priorityRoads = this.targetPriorityMap.entrySet().stream()
                    .filter(entry -> entry.getValue() == finalI)
                    .map(Map.Entry::getKey)
                    .toList();

            if (this.result != null && priorityRoads.contains(this.result)) {

                if (this.result.getValue() == posEntityID.getValue()) {
                    if (posEntity instanceof Road road && isRoadGuidelinePassable(road)) {
                        this.targetPriorityMap.remove(this.result);
                        this.passableRoads.add(this.result);
                        this.result = null;
                    } else {

                        return this;
                    }
                } else {

                    return this;
                }
            } else if (!priorityRoads.isEmpty()) {

                Optional<EntityID> opt = priorityRoads.stream()
                        .min(Comparator.comparingInt(entityID -> {
                            if (entityID == null)
                                return Integer.MAX_VALUE;
                            return this.getPathDistance(entityID, this.agentInfo.getID());
                        }));
                return this.setAccessibleResult(opt.get());
            }
        }

        if (posEntity instanceof Road road && this.isRoadGuidelinePassable(road)) {
            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("The road is passable: " + road.getID());
            }
            this.targetPriorityMap.remove(this.result);
            this.passableRoads.add(this.result);
            this.result = null;
        }

        this.processNonResult();

        return this;
    }

    private void processNonResult() {

        if (this.clustering == null) {

            this.worldInfo.getEntitiesOfType(BUILDING)
                    .stream()
                    .map(Building.class::cast)
                    .map(Building::getNeighbours)
                    .flatMap(Collection::stream)
                    .filter(id -> !this.passableRoads.contains(id))
                    .forEach(id -> this.targetPriorityMap.merge(id, 6, Math::min));
        } else {

            Collection<StandardEntity> clusterEntities = this.clustering.getClusterEntities(
                    this.clustering.getClusterIndex(this.agentInfo.getID()));
            for (StandardEntity se : clusterEntities) {

                if (se instanceof Human human) {
                    if (!this.isHumanValid(human)) {
                        continue;
                    }
                    EntityID pos = human.getPosition();
                    StandardEntity posEntity = this.worldInfo.getEntity(pos);

                    if (human instanceof Civilian) {
                        if (posEntity instanceof Building building) {

                            if (building.isBrokennessDefined() && building.getBrokenness() > 0) {

                                Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand(building);
                                entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 6, Math::min));
                            }
                        }
                    }

                    if (human instanceof AmbulanceTeam || human instanceof PoliceForce) {
                        if (posEntity instanceof Building building) {
                            Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand(building);
                            entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 6, Math::min));
                        } else if (posEntity instanceof Road road) {
                            if (!this.passableRoads.contains(road.getID())) {
                                this.targetPriorityMap.merge(road.getID(), 6, Math::min);
                            }
                        }
                    }
                } else if (se instanceof Building building) {

                    this.getUnpassableEntranceOfBuildingExpand(building)
                            .forEach(entrance -> this.targetPriorityMap.merge(entrance, 7, Math::min));
                } else if (se instanceof Road road) {

                    if (!this.passableRoads.contains(road.getID())) {
                        this.targetPriorityMap.merge(road.getID(), 6, Math::min);
                    }
                }
            }
        }
    }

    private void processChangedEntities() {

        Set<EntityID> changedEntityIDs = this.worldInfo.getChanged().getChangedEntities();
        Set<Human> allHumans = this.worldInfo.getEntitiesOfType(
                StandardEntityURN.CIVILIAN, StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.POLICE_FORCE, StandardEntityURN.FIRE_BRIGADE)
                .stream()
                .map(Human.class::cast)
                .collect(Collectors.toSet());

        if (this.agentInfo.getTime() > this.OPEN_CLUSTER_TIME) {
            this.isOutClusterIgnorant = false;
        }

        for (EntityID id : changedEntityIDs) {
            StandardEntity se = this.worldInfo.getEntity(id);

            if (se instanceof Road road) {

                if (this.isRoadGuidelinePassable(road)) {
                    this.targetPriorityMap.remove(id);
                    this.passableRoads.add(id);
                    continue;
                }

                if (!this.isNeedClearOtherRoad) {
                    continue;
                }

                if (road.getNeighbours()
                        .stream()
                        .map(this.worldInfo::getEntity)
                        .noneMatch(Building.class::isInstance)) {
                    this.targetPriorityMap.merge(id, 5, Math::min);
                }
            }

            if (this.clusterCenter != null && this.isOutClusterIgnorant) {
                if (se instanceof Area area) {
                    if (this.getDistance(area, this.clusterCenter) > this.clusterRange) {
                        continue;
                    }
                } else if (se instanceof Human human) {
                    StandardEntity posEntity = this.worldInfo.getEntity(human.getPosition());
                    if (!(posEntity instanceof Area area)) {
                        continue;
                    }
                    if (this.getDistance(area, this.clusterCenter) > this.clusterRange) {
                        continue;
                    }
                }
            }

            if (se instanceof Refuge) {

                Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand((Building) se);
                entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 0, Math::min));
            } else if (se instanceof FireBrigade || se instanceof AmbulanceTeam) {
                Human human = (Human) se;
                if (!this.isHumanValid(human)) {
                    continue;
                }
                EntityID pos = human.getPosition();
                StandardEntity posEntity = this.worldInfo.getEntity(pos);
                if (posEntity instanceof Building) {

                    if (this.isBuriedInBuilding(human)) {
                        Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand((Building) posEntity);
                        entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 1, Math::min));
                    }
                } else if (posEntity instanceof Road) {
                    if (this.isStuckInBlockade(human)) {
                        this.targetPriorityMap.merge(pos, 1, Math::min);
                    }
                }
            } else if (se instanceof Civilian civ) {
                if (!this.isHumanValid(civ)) {
                    continue;
                }
                EntityID pos = civ.getPosition();
                StandardEntity posEntity = this.worldInfo.getEntity(pos);
                if (posEntity instanceof Building building) {

                    if (building.isBrokennessDefined() && building.getBrokenness() > 0) {

                        Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand(building);
                        entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 1, Math::min));
                    }
                } else if (posEntity instanceof Road) {

                    if (this.isStuckInBlockade(civ)) {
                        this.targetPriorityMap.merge(pos, 4, Math::min);
                    }
                }
            } else if (se instanceof Building building) {
                if (!(building.isBrokennessDefined() && building.getBrokenness() > 0)) {
                    continue;
                }

                Set<EntityID> entrances = this.getUnpassableEntranceOfBuildingExpand(building);

                if (building.getID().getValue() == 28754) {
                    personalLogger.debug("Building " + building.getID() + " entrances: " + entrances);
                }

                for (Human human : allHumans) {
                    if (!human.isPositionDefined()) {
                        continue;
                    }
                    if (human.getID().getValue() == 1338076808) {
                        personalLogger.debug("Human: " + human.getID() + " is at " + human.getPosition());
                    }
                    if (human.getPosition().equals(building.getID())) {
                        if (building.getID().getValue() == 28754) {
                            personalLogger.debug("Human: " + human + " is in Building " + building.getID());
                        }
                        entrances.forEach(entrance -> this.targetPriorityMap.merge(entrance, 1, Math::min));
                        break;
                    }
                }
            }
        }
    }

    @Override
    public RoadDetector updateInfo(MessageManager messageManager) {
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        Set<StandardEntity> changedEntites = this.agentInfo.getChanged().getChangedEntities()
                .stream()
                .map(this.worldInfo::getEntity)
                .collect(Collectors.toSet());
        Set<Civilian> sendCiv = new HashSet<>();
        for (StandardEntity se : changedEntites) {
            if (se instanceof Civilian civ) {

                if (!this.isBuriedInBuilding(civ)) {

                    continue;
                }
                if (changedEntites.stream()
                        .filter(FireBrigade.class::isInstance)
                        .map(FireBrigade.class::cast)
                        .anyMatch(fb -> fb.getPosition().equals(civ.getPosition()))) {

                    continue;
                }
                sendCiv.add(civ);
            } else if (se instanceof FireBrigade fb) {

                sendCiv.stream()
                        .filter(this::isBuriedInBuilding)
                        .forEach(civ -> messageManager
                                .addMessage(new MessageCivilian(false, StandardMessagePriority.HIGH, civ)));
                sendCiv.clear();
            }
        }

        this.reflectMessage(messageManager);
        this.pathPlanning.updateInfo(messageManager);
        this.clustering.updateInfo(messageManager);
        this.guidelineCreator.updateInfo(messageManager);
        return this;
    }

    private void reflectMessage(MessageManager messageManager) {
        Set<EntityID> changedEntityIDs = this.agentInfo.getChanged().getChangedEntities();
        for (CommunicationMessage message : messageManager.getReceivedMessageList()) {
            Class<? extends CommunicationMessage> messageClass = message.getClass();
            if (messageClass == MessageAmbulanceTeam.class) {
                this.handleMessage((MessageAmbulanceTeam) message);
            } else if (messageClass == MessageFireBrigade.class) {
                this.handleMessage((MessageFireBrigade) message);
            } else if (messageClass == MessageRoad.class) {
                this.handleMessage((MessageRoad) message, changedEntityIDs);
            } else if (messageClass == MessagePoliceForce.class) {
                this.handleMessage((MessagePoliceForce) message);
            } else if (messageClass == CommandPolice.class) {
                this.handleMessage((CommandPolice) message);
            }
        }
    }

    private void handleMessage(MessageAmbulanceTeam msg) {
        return;
    }

    private void handleMessage(MessageFireBrigade msg) {
        return;
    }

    private void handleMessage(MessageRoad msgRoad, Collection<EntityID> changedIDs) {
        if (msgRoad.isBlockadeDefined() && !changedIDs.contains(msgRoad.getBlockadeID())) {
            MessageUtil.reflectMessage(this.worldInfo, msgRoad);
        }
        if (msgRoad.isPassable()) {
            this.passableRoads.add(msgRoad.getRoadID());
            this.targetPriorityMap.remove(msgRoad.getRoadID());
        }
    }

    private void handleMessage(MessagePoliceForce msg) {
        return;
    }

    private void handleMessage(CommandPolice cmdPolice) {

        PoliceForce pf = (PoliceForce) this.agentInfo.me();

        EntityID targetID = cmdPolice.getTargetID();

        if (targetID == null) {
            return;
        }
        if (this.isPFCanArriveInstant(pf, targetID) || this.clusterEntityIDs.contains(targetID)) {
            if (cmdPolice.getAction() == CommandPolice.ACTION_CLEAR) {
                StandardEntity se = this.worldInfo.getEntity(targetID);
                if (se instanceof Road road) {

                    this.targetPriorityMap.merge(targetID, 2, Math::min);
                } else if (se instanceof Blockade blockade) {

                    if (!blockade.isPositionDefined()) {
                        return;
                    }
                    StandardEntity posEntity = this.worldInfo.getEntity(blockade.getPosition());
                    if (posEntity instanceof Road road) {

                        this.targetPriorityMap.merge(posEntity.getID(), 2, Math::min);
                    }
                } else if (se instanceof Building building) {

                    this.getUnpassableEntranceOfBuildingExpand(building)
                            .forEach(id -> this.targetPriorityMap.merge(id, 2, Math::min));
                }
            }
        }
    }

    private boolean needClearOtherRoad(int policeCountThreshold) {
        double myX = this.agentInfo.getX();
        double myY = this.agentInfo.getY();
        long pfCount = this.worldInfo.getEntitiesOfType(POLICE_FORCE)
                .stream()
                .map(PoliceForce.class::cast)
                .filter(pf -> !pf.getID().equals(this.agentInfo.getID()))
                .filter(pf -> Math.abs(myX - pf.getX()) < this.CLEAR_OTHER_ROAD_PF_DISTANCE &&
                        Math.abs(myY - pf.getY()) < this.CLEAR_OTHER_ROAD_PF_DISTANCE)
                .count();
        return pfCount < policeCountThreshold;
    }

    private boolean isPFCanArriveInstant(PoliceForce pf, EntityID entityID) {

        StandardEntity se = this.worldInfo.getEntity(entityID);

        if (se instanceof Area area) {
            double manhattanDistance = this.getManhattanDistance(pf.getX(), pf.getY(), area.getX(), area.getY());
            return manhattanDistance / SEUConstants.MEAN_VELOCITY_OF_MOVING < this.TIME_INSTANT_THRESHOLD;
        }
        return false;
    }

    private boolean isStuckInBlockade(Human human) {
        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        if (!(posEntity instanceof Area)) {
            return false;
        }
        final Area area = (Area) posEntity;

        if (!area.isBlockadesDefined()) {
            return false;
        }

        final Optional<java.awt.geom.Area> combinedBlockade = this.worldInfo.getBlockades(area)
                .stream()
                .filter(Blockade::isApexesDefined)
                .map(Blockade::getShape)
                .map(java.awt.geom.Area::new)
                .reduce((acc, v) -> {
                    acc.add(v);
                    return acc;
                });
        if (combinedBlockade.isEmpty()) {
            return false;
        }

        final Point2D point = new Point2D(human.getX(), human.getY());

        final double rad = human.getStandardURN() == CIVILIAN
                ? SEUConstants.CIVILIAN_RADIUS
                : SEUConstants.AGENT_RADIUS;

        final java.awt.geom.Area circle = this.makeAWTArea(point, rad);
        circle.intersect(combinedBlockade.get());
        return !circle.isEmpty();
    }

    private boolean isBuriedInBuilding(Human human) {
        return this.isHumanValid(human) && human.isBuriednessDefined() && human.getBuriedness() > 0;
    }

    private boolean isStart(int startTime) {
        return this.agentInfo.getTime() > scenarioInfo.getKernelAgentsIgnoreuntil() + startTime;
    }

    private boolean isIdle() {
        final int time = this.agentInfo.getTime();
        final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();

        return time < ignored;
    }

    private boolean isRoadGuidelinePassable(Road road) {

        if (this.isIdle()) {
            return false;
        }

        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {

            if (road.getID().getValue() == 28770) {
                personalLogger.debug("Road 28770 has no blockades");
            }

            return true;
        }

        Line2D guideline2D = this.guidelineMap.getOrDefault(road.getID(), null);
        if (guideline2D == null) {
            if (SEUConstants.PF_ROAD_DETECTOR_LOG) {
                personalLogger.debug("Road " + road.getID() + " has no guideline");
            }
            return false;
        }

        Collection<Blockade> blockades = this.worldInfo.getBlockades(road)
                .stream()
                .filter(Blockade::isApexesDefined)
                .collect(Collectors.toSet());

        if (road.getID().getValue() == 28770) {
            personalLogger.debug("Road 28770 has blockade: " + blockades);
        }

        final Optional<java.awt.geom.Area> combinedBlockade = blockades
                .stream()
                .map(Blockade::getShape)
                .map(java.awt.geom.Area::new)
                .reduce((acc, v) -> {
                    acc.add(v);
                    return acc;
                });

        if (combinedBlockade.isEmpty()) {
            return true;
        }

        java.awt.geom.Area guidelineArea = this.expandLine2DToAWTArea(guideline2D);

        guidelineArea.intersect(combinedBlockade.get());

        if (road.getID().getValue() == 28770) {
            personalLogger.debug("Road 28770 blockade intersect with guideline: " + guidelineArea.isEmpty());
        }

        return guidelineArea.isEmpty();

    }

    private boolean isHumanValid(Human human) {
        return human.isPositionDefined() && human.isHPDefined() && human.getHP() > 0;
    }

    private EntityID getNearestRefugeID() {

        EntityID myID = this.agentInfo.getID();

        double myX = this.agentInfo.getX();
        double myY = this.agentInfo.getY();

        Optional<Refuge> targetRefugeOpt = worldInfo.getEntitiesOfType(StandardEntityURN.REFUGE)
                .stream()
                .map(Refuge.class::cast)

                .filter(refuge -> {

                    double myDist = getManhattanDistance(myX, myY, refuge.getX(), refuge.getY());

                    return worldInfo.getEntitiesOfType(StandardEntityURN.POLICE_FORCE)
                            .stream()
                            .map(PoliceForce.class::cast)

                            .filter(police -> !police.getID().equals(myID))
                            .allMatch(police -> {
                                double policeDist = getManhattanDistance(
                                        police.getX(), police.getY(),
                                        refuge.getX(), refuge.getY());

                                return policeDist >= myDist;
                            });
                })
                .findFirst();

        return targetRefugeOpt.map(AbstractEntity::getID).orElse(null);
    }

    private Set<EntityID> getUnpassableEntranceOfBuildingExpand(Building se) {
        Queue<StandardEntity> queue = new LinkedList<>();
        Set<StandardEntity> visited = new HashSet<>();
        Set<EntityID> entrances = new HashSet<>();
        queue.add(se);
        visited.add(se);
        while (!queue.isEmpty()) {
            StandardEntity current = queue.poll();

            if (current instanceof Building building) {

                if (current.getID().getValue() == 28754) {
                    personalLogger.debug("Building 28754 has neighbor: " + building.getNeighbours());
                }

                for (EntityID neighborID : building.getNeighbours()) {
                    StandardEntity neighbor = this.worldInfo.getEntity(neighborID);

                    if (visited.contains(neighbor)) {
                        continue;
                    }

                    visited.add(neighbor);

                    if (neighbor instanceof Road road) {

                        if (this.passableRoads.contains(neighborID)) {
                            if (neighbor.getID().getValue() == 28770) {
                                personalLogger.debug("Road 28770 is passable");
                            }

                            continue;
                        } else {

                            entrances.add(neighborID);
                        }
                    } else if (neighbor instanceof Building) {

                        queue.add(neighbor);
                    }
                }
            }
        }
        return entrances;
    }

    private RoadDetector setAccessibleResult(EntityID entityID) {

        List<EntityID> path = this.getPath(true, this.agentInfo.getPosition(), entityID);

        if (!path.isEmpty()) {

            this.result = path.getLast();
        } else {
            this.result = null;
        }
        return this;
    }

    private List<EntityID> getPath(boolean isFull, EntityID from, EntityID dest) {
        if (from == null || dest == null) {
            return new ArrayList<>();
        }
        final EntityID start = this.getNonnullPosition(from);
        final EntityID end = this.getNonnullPosition(dest);
        this.pathPlanning.setFrom(start);
        this.pathPlanning.setDestination(end);
        this.pathPlanning.calc();
        List<EntityID> path = this.pathPlanning.getResult();

        if (path == null) {
            path = new ArrayList<>();
        }
        if (isFull) {
            if (path.isEmpty() || !path.getFirst().equals(start)) {

                path.addFirst(start);
            }
            if (!path.getLast().equals(end)) {
                path.add(end);
            }
        }

        return path;
    }

    private int getPathDistance(EntityID from, EntityID dest) {

        if (from == null || dest == null) {
            return Integer.MAX_VALUE;
        }
        EntityID fromPosID = this.getNonnullPosition(from);
        EntityID destPosID = this.getNonnullPosition(dest);
        return (int) this.pathPlanning.setFrom(fromPosID)
                .setDestination(destPosID).calc().getDistance();
    }

    private EntityID getNonnullPosition(EntityID entityID) {
        StandardEntity se = this.worldInfo.getEntity(entityID);

        if (se instanceof Human || se.getStandardURN() == BLOCKADE) {
            return this.worldInfo.getPosition(entityID).getID();
        }
        return entityID;
    }

    private Comparator<EntityID> priorityComparator() {

        final EntityID me = this.agentInfo.getID();

        final Comparator<EntityID> comparator1 = comparing(this.targetPriorityMap::get);

        final Comparator<EntityID> comparator2 = comparing(
                i -> this.worldInfo.getDistance(me, i));

        return comparator1.thenComparing(comparator2);
    }

    private double getDistance(Area se, Point2D point) {
        return Math.hypot(se.getX() - point.getX(), se.getY() - point.getY());
    }

    private double getDistance(double x, double y) {
        return Math.hypot(this.agentInfo.getX() - x, this.agentInfo.getY() - y);
    }

    private java.awt.geom.Area expandLine2DToAWTArea(Line2D line) {

        final double x1 = line.getOrigin().getX();
        final double x2 = line.getEndPoint().getX();
        final double y1 = line.getOrigin().getY();
        final double y2 = line.getEndPoint().getY();

        final double length = Math.hypot(x2 - x1, y2 - y1);

        final double ldx = (y2 - y1) * GUIDELINE_WIDTH / 2 / length;
        final double ldy = (x1 - x2) * GUIDELINE_WIDTH / 2 / length;

        final double rdx = (y1 - y2) * GUIDELINE_WIDTH / 2 / length;
        final double rdy = (x2 - x1) * GUIDELINE_WIDTH / 2 / length;

        final Point2D p1 = new Point2D(x1 + ldx, y1 + ldy);
        final Point2D p2 = new Point2D(x2 + ldx, y2 + ldy);
        final Point2D p3 = new Point2D(x2 + rdx, y2 + rdy);
        final Point2D p4 = new Point2D(x1 + rdx, y1 + rdy);

        return this.makeAWTArea(new Point2D[] { p1, p2, p3, p4 });
    }

    private java.awt.geom.Line2D convertToAWTLine(Line2D line) {

        final double x1 = line.getOrigin().getX();
        final double x2 = line.getEndPoint().getX();

        final double y1 = line.getOrigin().getY();
        final double y2 = line.getEndPoint().getY();

        return new java.awt.geom.Line2D.Double(x1, y1, x2, y2);
    }

    private java.awt.geom.Area makeAWTArea(Point2D[] ps) {
        final int n = ps.length;
        Path2D path = new Path2D.Double();
        path.moveTo(ps[0].getX(), ps[0].getY());

        for (int i = 1; i < n; ++i) {
            path.lineTo(ps[i].getX(), ps[i].getY());
        }

        path.closePath();
        return new java.awt.geom.Area(path);
    }

    public java.awt.geom.Area makeAWTArea(Point2D p, double rad) {
        final double d = rad * 2.0;
        final double x = p.getX() - rad;
        final double y = p.getY() - rad;
        return new java.awt.geom.Area(new Ellipse2D.Double(x, y, d, d));
    }

    private double getManhattanDistance(double fromX, double fromY, double toX, double toY) {
        double dx = fromX - toX;
        double dy = fromY - toY;
        return Math.abs(dx) + Math.abs(dy);
    }
}
