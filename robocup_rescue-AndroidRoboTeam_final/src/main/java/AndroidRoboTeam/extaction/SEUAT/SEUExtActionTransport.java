package AndroidRoboTeam.extaction.SEUAT;

import AndroidRoboTeam.world.SEUConstants;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionLoad;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toSet;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUExtActionTransport extends ExtAction {

    private PathPlanning pathPlanning;

    private int thresholdRest;

    private int kernelTime;

    private EntityID target;

    private ExtAction actionExtMove;

    private int unloadTime = 0;
    private final int UNLOAD_LIMITED_TIME = 3;

    private MessageManager messageManager;

    private List<Action> actionHistory = new LinkedList<>();
    final private int ACTION_HISTORY_SIZE = 15;

    private List<Point2D> pointHistory = new LinkedList<>();
    final private int POINT_HISTORY_SIZE = 15;

    private List<Area> areaHistory = new LinkedList<>();
    final private int AREA_HISTORY_SIZE = 15;

    private int stateOfStuckByWall = 0;

    private Random randomGen = new Random(42);

    private int isMovingCount = 0;
    final private int MOVE_COUNT_THRESHOLD = 1;

    private final Logger actionExtClearLogger = DefaultLogger.getLogger("ActionExtTransport/ActionExtTransport");
    private final Logger personalLogger = DefaultLogger
            .getLogger("ActionExtTransport/" + this.agentInfo.getID().toString());

    private final int AGENT_RADIUS = 500;

    public SEUExtActionTransport(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
            ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionTransport.rest", 100);
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionTransport.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionTransport.ActionExtMove",
                        "adf.impl.extaction.DefaultExtActionMove");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionTransport.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionTransport.ActionExtMove",
                        "adf.impl.extaction.DefaultExtActionMove");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionTransport.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionTransport.ActionExtMove",
                        "adf.impl.extaction.DefaultExtActionMove");
                break;
        }

    }

    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        try {
            this.kernelTime = this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            this.kernelTime = -1;
        }
        return this;
    }

    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.messageManager == null) {
            this.messageManager = messageManager;
        }
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        if (target != null) {
            StandardEntity entity = this.worldInfo.getEntity(target);
            if (entity instanceof Human || entity instanceof Area) {
                this.target = target;
                return this;
            }
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("Time: " + this.agentInfo.getTime());
            personalLogger.debug("LastTarget: " + this.target);
        }

        this.result = null;

        AmbulanceTeam myself = (AmbulanceTeam) this.agentInfo.me();
        Human humanOnBoard = this.agentInfo.someoneOnBoard();
        Point2D myPoint = new Point2D(myself.getX(), myself.getY());
        Area myPosEntity = (Area) this.agentInfo.getPositionArea();

        this.updateHistory(myPoint, myPosEntity);

        if (humanOnBoard != null) {

            this.result = this.getUnloadAction(myself, this.pathPlanning, humanOnBoard, this.target);
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("on board: (" + humanOnBoard + "), unload: " + this.result);
            }
        }

        if (this.result == null && this.needRest(myself)) {

            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("----需要休息----");
            }
            EntityID areaID = this.convertArea(this.target);
            ArrayList<EntityID> targets = new ArrayList<>();
            if (areaID != null) {
                targets.add(areaID);
            }
            this.result = this.getRefugeAction(myself, this.pathPlanning, targets, false);
            if (result == null) {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("[ERROR]: Can't find refuge");
                }
            }
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("getRefugeAction" + this.result);
            }
        }

        if (stateOfStuckByWall == 1) {
            this.result = this.randomWalk();
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("then, try to random walk");
            }
            if (this.result != null) {
                stateOfStuckByWall = 0;
                return this;
            }
        }
        stateOfStuckByWall = 0;

        if (this.isMoving(3) && this.isStill(3, 1000)) {

            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("cannot reach : " + this.result);
            }

            ++this.isMovingCount;

            if (this.isMovingCount > this.MOVE_COUNT_THRESHOLD) {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("cannot reach : " + this.target + " for " + this.MOVE_COUNT_THRESHOLD
                            + " times, start random move");
                }

                List<Point2D> passableMidPoint = myPosEntity.getEdges()
                        .stream()
                        .filter(Edge::isPassable)
                        .map(Edge::getLine)
                        .filter(l -> this.getLength(l) > this.AGENT_RADIUS)
                        .map(this::getMiddlePoint)
                        .sorted(comparing(e -> GeometryTools2D.getDistance(myPoint, e)))
                        .toList();
                if (passableMidPoint.isEmpty()) {
                    this.result = this.randomWalk();
                    if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                        personalLogger.debug("[ERROR]: no passable mid point, start random move " + this.result);
                    }
                } else {

                    Point2D mid = passableMidPoint.get(randomGen.nextInt(passableMidPoint.size()));

                    this.result = new ActionMove(List.of(myPosEntity.getID()), (int) mid.getX(), (int) mid.getY());
                    stateOfStuckByWall = 1;
                    if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                        personalLogger.debug("move to middle " + this.result);
                    }
                }
            }
            return this;
        } else {

            this.isMovingCount = 0;
        }

        if (this.result == null && this.target != null) {

            this.result = this.getRescueAction(myself, this.pathPlanning, this.target);
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("getRescueAction: " + this.result);
            }
        }
        return this;
    }

    private Action getRescueAction(AmbulanceTeam at, PathPlanning pathPlanning, EntityID target) {
        StandardEntity targetEntity = this.worldInfo.getEntity(target);

        if (targetEntity == null) {
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("[WARN] 当前target的Entity为null");
            }
            return null;
        }

        EntityID atPosID = at.getPosition();

        if (targetEntity instanceof Human human) {

            if (!human.isPositionDefined()) {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("[ERROR]: " + human + "'s position is undefined");
                }
                return null;
            }

            if (human.isHPDefined() && human.getHP() == 0) {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug(human + "已经死了，不救");
                }
                return null;
            }

            EntityID targetPosID = worldInfo.getPosition(human).getID();
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger
                        .debug("当前位置为:" + at.getPosition() + ",目标(" + human.getID() + ")位置为:" + targetPosID);
            }

            if (atPosID.getValue() == targetPosID.getValue()) {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("Has reached: " + targetPosID);
                }
                if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
                    if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                        personalLogger.debug(human.getID() + "等待被挖出");
                    }
                    if (targetEntity instanceof Civilian civilian) {
                        if (this.isNeedWait(civilian)) {
                            return new ActionRest();
                        }
                    }
                    return null;
                } else if (human.getStandardURN() == CIVILIAN) {
                    if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                        personalLogger.debug("觉得" + human.getID() + "已经挖出来了，背起来");
                    }
                    return new ActionLoad(human.getID());
                }
            } else {
                List<EntityID> path = pathPlanning.setFrom(atPosID).setDestination(targetPosID).calc()
                        .getResult();
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("还未走到目标" + human.getID() + "位置");
                }
                if (path != null && !path.isEmpty()) {
                    Action action = this.getMoveAction(path);
                    if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                        personalLogger.debug("走到目标位置,way:" + path + ",action:" + action);
                    }
                    return action;
                }
            }
            return null;
        }

        if (targetEntity.getStandardURN() == BLOCKADE) {

            Blockade blockade = (Blockade) targetEntity;
            if (blockade.isPositionDefined()) {
                targetEntity = this.worldInfo.getEntity(blockade.getPosition());
            }
        }
        if (targetEntity instanceof Area) {

            List<EntityID> path = pathPlanning.getResult(atPosID, targetEntity.getID());
            if (path != null && !path.isEmpty()) {
                this.result = this.getMoveAction(path);

            }
        }
        return null;
    }

    private Action getUnloadAction(AmbulanceTeam at, PathPlanning pathPlanning,
            Human humanOnBoard, EntityID targetID) {
        if (humanOnBoard == null) {
            return null;
        }

        if (this.agentInfo.someoneOnBoard() == null) {
            return null;
        }

        if (humanOnBoard.isHPDefined() && humanOnBoard.getHP() == 0) {
            if (this.unloadTime >= this.UNLOAD_LIMITED_TIME) {
                this.unloadTime = 0;
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("unload corpse: " + humanOnBoard.getID());
                }
                return new ActionUnload();
            } else {
                this.unloadTime++;
            }
        }

        EntityID posID = at.getPosition();
        StandardEntity posEntity = this.worldInfo.getEntity(posID);
        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("humanOnBoard:" + humanOnBoard + " damage:"
                    + humanOnBoard.isDamageDefined() + "," + humanOnBoard.getDamage());
        }
        if (posEntity != null && posEntity.getStandardURN() == REFUGE) {
            if (this.agentInfo.someoneOnBoard() == null) {
                return null;
            }
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("因为 " + humanOnBoard.getID() + " 当前在refuge，unload");
            }
            return new ActionUnload();
        } else {

            pathPlanning.setFrom(posID);
            Collection<EntityID> allRefugeIDs = this.worldInfo.getEntityIDsOfType(REFUGE);

            if (allRefugeIDs.isEmpty() || this.getVaildRefuges(allRefugeIDs).isEmpty()) {

                pathPlanning.setDestination(allRefugeIDs);
            } else {

                pathPlanning.setDestination(this.getVaildRefuges(allRefugeIDs));
            }

            List<EntityID> path = pathPlanning.calc().getResult();

            if (path != null && !path.isEmpty()) {
                return this.getMoveAction(path);
            } else {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("没有路到refuge.");
                }
            }
        }

        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("calcUnload异常，damage:" + humanOnBoard.getDamage());
        }
        return null;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) {
            return false;
        }
        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == -1) {
            try {
                this.kernelTime = this.scenarioInfo.getKernelTimesteps();
            } catch (NoSuchConfigOptionException e) {
                this.kernelTime = -1;
            }
        }
        return damage >= this.thresholdRest
                || (activeTime + this.agentInfo.getTime() + 20) < this.kernelTime;
    }

    private EntityID convertArea(EntityID targetID) {
        StandardEntity entity = this.worldInfo.getEntity(targetID);
        if (entity == null) {
            return null;
        }
        if (entity instanceof Human) {
            Human human = (Human) entity;
            if (human.isPositionDefined()) {
                EntityID position = human.getPosition();
                if (this.worldInfo.getEntity(position) instanceof Area) {
                    return position;
                }
            }
        } else if (entity instanceof Area) {
            return targetID;
        } else if (entity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) entity;
            if (blockade.isPositionDefined()) {
                return blockade.getPosition();
            }
        }
        return null;
    }

    private Action getRefugeAction(Human human, PathPlanning pathPlanning,
            Collection<EntityID> targets, boolean isUnload) {
        EntityID posEntityID = human.getPosition();
        Collection<EntityID> refugeIDs = this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE);
        int refugeSize = refugeIDs.size();

        if (refugeIDs.contains(posEntityID)) {

            return isUnload ? new ActionUnload() : new ActionRest();
        }

        refugeIDs = this.getVaildRefuges(refugeIDs);

        List<EntityID> firstResult = null;
        while (!refugeIDs.isEmpty()) {
            pathPlanning.setFrom(posEntityID);
            pathPlanning.setDestination(refugeIDs);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                if (firstResult == null) {
                    firstResult = new ArrayList<>(path);
                    if (targets == null || targets.isEmpty()) {
                        break;
                    }
                }
                EntityID refugeID = path.get(path.size() - 1);
                pathPlanning.setFrom(refugeID);
                pathPlanning.setDestination(targets);
                List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
                if (fromRefugeToTarget != null && !fromRefugeToTarget.isEmpty()) {
                    return this.getMoveAction(path);
                }
                refugeIDs.remove(refugeID);
                if (refugeSize == refugeIDs.size()) {
                    break;
                }
                refugeSize = refugeIDs.size();
            } else {
                break;
            }
        }
        return firstResult != null ? this.getMoveAction(firstResult) : null;
    }

    private Action getMoveAction(List<EntityID> path) {
        if (path != null && !path.isEmpty()) {
            return (ActionMove) actionExtMove.setTarget(path.get(path.size() - 1)).calc().getAction();
        }
        return null;
    }

    public Collection<EntityID> getVaildRefuges(Collection<EntityID> refuges) {
        Collection<EntityID> targetRefuges = new ArrayList<>();
        if (refuges != null) {
            for (EntityID entityID : refuges) {
                Refuge refuge = (Refuge) this.worldInfo.getEntity(entityID);
                if (refuge.getOccupiedBeds() < refuge.getBedCapacity()) {
                    targetRefuges.add(entityID);
                }
            }
        }
        return targetRefuges;
    }

    private List<EntityID> getPath(boolean isFull, EntityID from, EntityID dest) {
        if (from == null || dest == null) {
            return new ArrayList<>();
        }
        final EntityID start = this.getNonnullPosition(from);
        final EntityID end = this.getNonnullPosition(dest);
        if (start == null || end == null) {
            return new ArrayList<>();
        }

        this.pathPlanning.setFrom(start);
        this.pathPlanning.setDestination(end);
        this.pathPlanning.calc();
        List<EntityID> path = this.pathPlanning.getResult();
        if (path == null || path.isEmpty()) {
            return new ArrayList<>();
        }
        path = new ArrayList<>(path);

        if (!path.getLast().equals(end)) {
            return new ArrayList<>();
        }
        if (isFull) {
            if (!path.getFirst().equals(start)) {
                path.addFirst(start);
            }
        }

        return path;
    }

    private EntityID getNonnullPosition(EntityID entityID) {
        StandardEntity se = this.worldInfo.getEntity(entityID);

        if (se == null) {
            return null;
        }
        if (se instanceof Human || se.getStandardURN() == BLOCKADE) {
            StandardEntity position = this.worldInfo.getPosition(entityID);
            return position == null ? null : position.getID();
        }
        return entityID;
    }

    private void updateHistory(Point2D myPoint, Area myPosEntity) {

        this.pointHistory.add(myPoint);
        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("Current position: " + myPoint);
        }
        if (this.pointHistory.size() > this.POINT_HISTORY_SIZE) {
            pointHistory.removeFirst();
        }

        this.areaHistory.add(myPosEntity);
        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("Current area: " + myPosEntity);
        }
        if (this.areaHistory.size() > this.AREA_HISTORY_SIZE) {
            areaHistory.removeFirst();
        }

        try {
            this.actionHistory.add(this.agentInfo.getExecutedAction(-1));
        } catch (Exception e) {
            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("No LastAction");
            }
        }
        if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
            personalLogger.debug("LastAction: " + actionHistory.getLast());
        }
        if (this.actionHistory.size() > this.ACTION_HISTORY_SIZE) {
            actionHistory.removeFirst();
        }

    }

    private Action randomWalk() {

        Area myPosArea = this.agentInfo.getPositionArea();

        List<EntityID> scope = new ArrayList<>();
        final List<EntityID> neighbours = myPosArea.getNeighbours();
        scope.addAll(neighbours);
        scope.addAll(neighbours
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast)
                .filter(Objects::nonNull)
                .map(Area::getNeighbours)
                .flatMap(Collection::stream)
                .collect(toSet()));
        EntityID randomArea = null;
        if (!scope.isEmpty()) {

            randomArea = scope.get(randomGen.nextInt(scope.size()));
        }
        if (randomArea != null) {

            if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                personalLogger.debug("randomArea: " + randomArea);
            }
            List<EntityID> path = this.getPath(true, myPosArea.getID(), randomArea);
            if (!path.isEmpty()) {

                return new ActionMove(path);
            } else {
                if (SEUConstants.AT_ACTION_EXT_TRANSPORT_LOG) {
                    personalLogger.debug("randomArea is null");
                }
            }
        }

        return null;
    }

    public Boolean isNeedWait(Civilian civilian) {
        int numOfFireBrigade = 0;

        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
            MessageFireBrigade messageFireBrigade = (MessageFireBrigade) cm;
            if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_RESCUE
                    && messageFireBrigade.getTargetID().equals(civilian.getID())) {
                numOfFireBrigade++;
            }
        }
        if (numOfFireBrigade != 0 && civilian.isBuriednessDefined()
                && civilian.getBuriedness() / numOfFireBrigade < 10) {
            return true;
        }
        return false;
    }

    private boolean isMoving(int judgeLength) {
        if (this.actionHistory.size() < judgeLength) {
            return false;
        }

        return this.actionHistory
                .stream()
                .skip(Math.max(0, this.actionHistory.size() - judgeLength))
                .allMatch(action -> action instanceof ActionMove);
    }

    private boolean isStill(int judgeLength, double range) {
        if (this.pointHistory.size() < judgeLength) {
            return false;
        }

        Point2D currentPoint = this.getPoint();
        return this.pointHistory
                .stream()
                .skip(Math.max(0, this.pointHistory.size() - judgeLength))
                .allMatch(point -> isPoint2DNearPoint2D(point, currentPoint, range));
    }

    private boolean isStuckInArea(int judgeLength) {
        if (this.areaHistory.size() < judgeLength) {
            return false;
        }

        Area currentArea = this.agentInfo.getPositionArea();
        return this.areaHistory
                .stream()
                .skip(Math.max(0, this.areaHistory.size() - judgeLength))
                .allMatch(area -> area.getID().equals(currentArea.getID()));
    }

    private Point2D getPoint() {
        final double x = this.agentInfo.getX();
        final double y = this.agentInfo.getY();
        return new Point2D(x, y);
    }

    private static boolean isPoint2DNearPoint2D(Point2D point1, Point2D point2, double range) {
        return GeometryTools2D.getDistance(point1, point2) <= range;
    }

    private Point2D getMiddlePoint(Line2D line) {
        return line.getPoint(0.5);
    }

    private double getLength(Line2D line) {
        if (line == null) {
            return 0.0;
        }
        return GeometryTools2D.getDistance(line.getOrigin(), line.getEndPoint());
    }

}
