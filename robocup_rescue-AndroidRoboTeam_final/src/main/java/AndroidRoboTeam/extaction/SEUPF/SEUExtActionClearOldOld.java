
package AndroidRoboTeam.extaction.SEUPF;

import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.module.complex.SEUPF.GuidelineCreator;

import AndroidRoboTeam.world.Util;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.police.ActionClear;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;

import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.Entity;
import rescuecore2.worldmodel.EntityID;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toSet;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUExtActionClearOldOld extends ExtAction {

    protected double repairDistance;
    MessageManager messageManager = null;

    private GuidelineCreator guidelineCreator;
    private Map<EntityID, Line2D> guidelineMap;
    private final int GUIDELINE_WIDTH = 10;

    private final Logger actionExtClearLogger = DefaultLogger.getLogger("ActionExtClear/SEUActionExtClear");
    private final Logger personalLogger = DefaultLogger
            .getLogger("ActionExtClear/" + this.agentInfo.getID().toString());

    private Set<EntityID> passableRoads = new HashSet<>();
    private Map<EntityID, Action> targetIDActionMap = new HashMap<>();
    private Set<Blockade> passableBlockades = new HashSet<>();

    private final double CLEAR_REPAIR_DISTANCE;
    private final double CLEAR_ALLOWANCE_RATIO = 2.0 / 3.0;
    private final double MOVE_ALLOWANCE_DISTANCE = 500;
    private final double MOVE_ALLOWANCE_RATIO = 1.0 / 5.0;

    private final int AGENT_RADIUS = 100;
    private final int CIVILIAN_RADIUS = 100;
    private final Integer THRESHOLD_REST;

    private final int DAMAGE_NEEDED_REST = 100;

    private int KERNEL_TIME = 300;

    private PathPlanning pathPlanning;
    private Clustering clustering;

    private EntityID target;

    final private int POINT_HISTORY_SIZE = 15;
    private List<Point2D> pointHistory = new LinkedList<>();
    private List<Area> areaHistory = new LinkedList<>();
    final private int AREA_HISTORY_SIZE = 15;

    final private int ACTION_HISTORY_SIZE = 15;
    private List<Action> actionHistory = new LinkedList<>();

    private Point2D lastClearPoint = new Point2D(0, 0);
    private boolean isLastAvoidError = false;
    private int isMovingCount = 0;
    final private int MOVE_COUNT_THRESHOLD = 1;
    final private int MOVING_JUDGE_LENGTH = 3;
    final private int STILL_JUDGE_LENGTH = 6;

    private Set<EntityID> candidateAreaWhenCannotMove = new HashSet<>();

    private Set<EntityID> messageTargetRoads = new HashSet<>();

    private int stateOfStuckByWall = 0;

    private Random randomGen = new Random(42);

    public SEUExtActionClearOldOld(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.CLEAR_REPAIR_DISTANCE = si.getClearRepairDistance();
        this.THRESHOLD_REST = developData.getInteger("ActionExtClear.rest", 100);

        this.target = null;

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.sample.module.algorithm.SamplePathPlanning");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.sample.module.algorithm.SamplePathPlanning");
                break;
            case NON_PRECOMPUTE:
                this.KERNEL_TIME = si.getKernelTimesteps();
                this.pathPlanning = moduleManager.getModule("DefaultExtActionClear.PathPlanning",
                        "adf.sample.module.algorithm.SamplePathPlanning");
                break;
        }
        this.clustering = moduleManager.getModule("ActionExtClear.Clustering",
                "adf.sample.module.algorithm.SampleKMeans");
        this.guidelineCreator = moduleManager.getModule("GuidelineCreator.Default", SEUConstants.PATH_PLANNING_PATH);

    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
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
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.clustering.resume(precomputeData);
        this.guidelineCreator.resume(precomputeData);
        this.guidelineMap = guidelineCreator.getGuidelineMap();

        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.clustering.preparate();
        this.guidelineCreator.preparate();
        this.guidelineMap = guidelineCreator.getGuidelineMap();

        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.messageManager = messageManager;

        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        this.pathPlanning.updateInfo(messageManager);
        this.clustering.updateInfo(messageManager);
        this.guidelineCreator.updateInfo(messageManager);

        if (this.isIdle()) {
            return this;
        }
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity != null) {
            if (entity instanceof Road) {
                this.target = target;
            } else if (entity.getStandardURN().equals(BLOCKADE)) {
                this.target = ((Blockade) entity).getPosition();
            } else if (entity instanceof Building) {
                this.target = target;
            }
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
            personalLogger.debug("Time: " + this.agentInfo.getTime());
            personalLogger.debug("Target: " + this.target);

        }

        this.result = null;

        if (this.isIdle()) {
            return this;
        }

        EntityID myselfID = this.agentInfo.getID();
        PoliceForce myself = (PoliceForce) Objects.requireNonNull(this.worldInfo.getEntity(myselfID),
                "这个人：" + myselfID + "无法在世界找到！");
        Point2D myPoint = new Point2D(myself.getX(), myself.getY());
        Area myPosEntity = (Area) this.worldInfo.getPosition(myself.getID());

        this.pointHistory.add(myPoint);
        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
            personalLogger.debug("Current position: " + myPoint);
        }
        if (this.pointHistory.size() > this.POINT_HISTORY_SIZE) {
            pointHistory.removeFirst();
        }

        this.areaHistory.add(myPosEntity);
        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
            personalLogger.debug("Current area: " + myPosEntity);
        }
        if (this.areaHistory.size() > this.AREA_HISTORY_SIZE) {
            areaHistory.removeFirst();
        }

        try {
            this.actionHistory.add(this.agentInfo.getExecutedAction(-1));
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("LastAction: " + actionHistory.getLast());
            }
        } catch (Exception e) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("No LastAction");
            }
        }
        if (this.actionHistory.size() > this.ACTION_HISTORY_SIZE) {
            actionHistory.removeFirst();
        }

        if (this.needRest()) {
            final EntityID refuge = this.seekBestRefuge();
            if (myPosEntity instanceof Refuge) {
                this.result = new ActionRest();
                return this;
            }
            if (refuge != null) {
                this.target = refuge;
            }
        }

        List<Blockade> blockades;

        blockades = this.getNearestBlockadeBlockingHuman(myself);
        if (!blockades.isEmpty()) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("I am stuck in " + blockades.getFirst());
            }

            this.result = new ActionClear(blockades.getFirst().getID());
            return this;
        }

        if (stateOfStuckByWall == 1) {
            this.result = this.randomWalk();
            if (this.result != null) {
                stateOfStuckByWall = 0;
                return this;
            }
        }
        stateOfStuckByWall = 0;

        if (this.isMoving(3) && this.isStill(3, 1000)) {

            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("cannot reach : " + this.result);
            }

            ++this.isMovingCount;

            if (this.isMovingCount >= this.MOVE_COUNT_THRESHOLD) {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("cannot reach : " + this.target + " for " + this.MOVE_COUNT_THRESHOLD
                            + " times, random move");
                }
                List<EntityID> lastActionMovePath = ((ActionMove) this.actionHistory.getLast()).getPath();
                if (lastActionMovePath.isEmpty() || lastActionMovePath.size() == 1) {

                    personalLogger.debug("cannot reach: start search");
                    this.result = this.randomWalk();
                } else {

                    try {
                        Point2D mid = getMiddlePoint(myPosEntity.getEdgeTo(lastActionMovePath.get(1)).getLine());
                        this.result = new ActionMove(lastActionMovePath.subList(0, 1), (int) mid.getX(),
                                (int) mid.getY());
                        stateOfStuckByWall = 1;
                    } catch (Exception e) {
                        this.result = this.randomWalk();
                        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                            personalLogger.debug("path is not complete: start randomwalk!");
                        }
                    }
                }
            }
            return this;
        } else {

            this.isMovingCount = 0;
        }

        if (this.isMoving(8) && isStill(8, SEUConstants.MEAN_VELOCITY_OF_MOVING)) {
            this.result = this.makeActionToAvoidError();

            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("keep moving cannot reach : " + this.result);
            }
            return this;
        }

        if (this.target == null) {

            if (this.result == null) {
                this.result = this.makeActionToAvoidError();
            } else {
                this.result = null;
            }
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("Target is null, make action: " + this.result);
            }
            return this;
        }

        this.result = this.makeActionToAchieveTarget();
        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
            personalLogger.debug("makeActionToAchieveTarget: " + this.result);
        }
        if (this.result != null) {
            return this;
        }

        return this;
    }

    private EntityID seekBestRefuge() {

        final EntityID me = this.agentInfo.getID();

        final Optional<EntityID> ret = this.worldInfo.getEntityIDsOfType(REFUGE)
                .stream()

                .min((r1, r2) -> {
                    double d1, d2;
                    if (this.target != null) {

                        d1 = this.worldInfo.getDistance(me, r1) +
                                this.worldInfo.getDistance(r1, this.target);

                        d2 = this.worldInfo.getDistance(me, r2) +
                                this.worldInfo.getDistance(r2, this.target);

                    } else {
                        d1 = this.worldInfo.getDistance(me, r1);
                        d2 = this.worldInfo.getDistance(me, r2);
                    }
                    return Double.compare(d1, d2);
                });

        return ret.orElse(null);
    }

    private Action makeActionToAchieveTarget() {
        EntityID myselfID = this.agentInfo.getID();
        PoliceForce myself = (PoliceForce) Objects.requireNonNull(this.worldInfo.getEntity(myselfID),
                "这个人：" + myselfID + "无法在世界找到！");
        StandardEntity myPosEntity = this.worldInfo.getPosition(myself.getID());
        Action action = null;

        StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
        if (!(targetEntity instanceof Area)) {
            return null;
        }

        Area targetArea = (Area) targetEntity;

        if (myPosEntity instanceof Road road) {
            action = this.rescueSurroundings(myself, road);
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("rescueSurroundings: " + action);
            }
            if (action != null) {
                return action;
            }
        }

        if (myPosEntity.getID().equals(this.target) && myPosEntity instanceof Road road) {

            action = this.clearOrMoveToRoadGuideline(road, true);
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("clearOrMoveToRoadGuideline: " + action);
            }
        } else if (((Area) targetEntity).getEdgeTo(myPosEntity.getID()) != null) {

            action = this.clearOrMoveToNearbyArea(myself, targetArea, true);
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("clearOrMoveToNearbyArea: " + action);
            }
        } else {

            final List<EntityID> path = this.getPath(true, myselfID, this.target);
            if (path.size() <= 2) {
                return null;
            }

            action = this.clearAndMoveByConcretePath(myself, path);
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("Clear by concrete path: " + action);
            }
        }

        return action;
    }

    private Action clearAndMoveByConcretePath(PoliceForce pf, List<EntityID> path) {

        if (path == null || path.size() <= 2) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("[ERROR]: clearAndMoveByConcretePath");
            }
            return null;
        }

        Line2D concreteLine;
        List<Line2D> concretePath = new ArrayList<>();
        Map<Line2D, Area> concreteLineToAreaMap = new HashMap<>();
        List<Area> pathArea = path.stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast)
                .toList();

        Area startArea = pathArea.getFirst();
        Point2D startPoint = this.getPoint();
        Action action;
        concreteLine = new Line2D(startPoint, getMiddlePoint(startArea.getEdgeTo(path.get(1)).getLine()));
        concretePath.add(concreteLine);
        concreteLineToAreaMap.put(concreteLine, startArea);

        for (int i = 1; i < pathArea.size() - 1; i++) {
            Area pre = pathArea.get(i - 1);
            Area cur = pathArea.get(i);
            Point2D centroid = getPoint(cur);
            Area next = pathArea.get(i + 1);

            concreteLine = new Line2D(getMiddlePoint(pre.getEdgeTo(cur.getID()).getLine()), centroid);
            concretePath.add(concreteLine);
            concreteLineToAreaMap.put(concreteLine, cur);

            concreteLine = new Line2D(centroid, getMiddlePoint(cur.getEdgeTo(next.getID()).getLine()));
            concretePath.add(concreteLine);
            concreteLineToAreaMap.put(concreteLine, cur);
        }

        for (Line2D line2D : concretePath) {
            concreteLine = line2D;
            Area lineArea = concreteLineToAreaMap.get(concreteLine);
            Collection<Blockade> blockades = this.worldInfo.getBlockades(lineArea);

            Point2D intersect = this.getNearestIntersectPoint(concreteLine.getOrigin(), concreteLine.getOrigin(),
                    concreteLine.getEndPoint(), blockades);

            if (intersect != null) {
                action = this.clearPoint(this.getPoint(), intersect, blockades, pf,
                        (Road) lineArea, true);
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("clearPoint: " + action);
                }
                if (action != null) {
                    return action;
                }
            }

        }

        int endPointX;
        int endPointY;

        Line2D lastConcreteLine = concretePath.getLast();
        Point2D endPoint = lastConcreteLine.getPoint(1 - this.MOVE_ALLOWANCE_RATIO);
        endPointX = (int) endPoint.getX();
        endPointY = (int) endPoint.getY();

        return new ActionMove(path.subList(0, path.size() - 1), endPointX, endPointY);
    }

    private Action rescueSurroundings(PoliceForce pf, Area pfArea) {

        List<Human> humans = this.worldInfo.getChanged().getChangedEntities()
                .stream()
                .map(this.worldInfo::getEntity)
                .filter(h -> h instanceof Human)
                .map(Human.class::cast)
                .toList();

        for (Human human : humans) {
            if (!this.isHumanValid(human)) {
                continue;
            }

            if (human.getID().getValue() == 1338076808 && SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("I have seen human: " + human.getID().getValue());
                personalLogger.debug("Position: " + human.getPosition() + " HP: " + human.getHP());
            }

            EntityID posID = human.getPosition();
            StandardEntity posEntity = this.worldInfo.getEntity(posID);

            if (posEntity instanceof Building building) {

                if (human instanceof Civilian && !building.isBrokennessDefined()) {
                    continue;
                }

                Set<EntityID> allEntrance = this.getUnpassableEntranceOfBuildingExpand(building);

                if (human.getID().getValue() == 1338076808) {
                    personalLogger.debug("entrance to building: " + allEntrance);
                }

                if (allEntrance.isEmpty()) {
                    continue;
                }

                for (EntityID entranceID : allEntrance) {
                    StandardEntity neighbour = this.worldInfo.getEntity(entranceID);
                    if (pfArea.getID().equals(entranceID)) {

                        Action a = this.clearOrMoveToRoadGuideline((Road) pfArea, true);
                        if (a != null) {
                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("clearOrMoveToRoadGuideline: " + a);
                            }
                            return a;
                        }
                    }
                    if (pfArea.getEdgeTo(entranceID) != null) {

                        Action a = this.clearOrMoveToNearbyArea(pf, (Area) neighbour, true);
                        if (a != null) {
                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("clearOrMoveToNearbyArea: " + a);
                            }
                            return a;
                        }
                    }
                }
            }

            if (human instanceof Civilian || human instanceof PoliceForce) {
                continue;
            }

            if (!human.getPosition().equals(pfArea.getID()) &&
                    pfArea.getEdgeTo(human.getPosition()) == null) {

                continue;
            }

            Area humanArea = (Area) posEntity;

            java.awt.geom.Area stuckArea = this.getHumanIntersectBlockade(human);
            if (!stuckArea.isEmpty()) {

                Point2D center = new Point2D(
                        stuckArea.getBounds2D().getCenterX(),
                        stuckArea.getBounds2D().getCenterY());

                if (!stuckArea.contains(center.getX(), center.getY())) {

                    double[] coords = new double[6];
                    java.awt.geom.PathIterator it = stuckArea.getPathIterator(null);
                    if (!it.isDone()) {
                        it.currentSegment(coords);
                        center = new Point2D(coords[0], coords[1]);
                    }
                }
                Action a = this.moveToPoint(this.getPoint(), center, pf,
                        (Road) humanArea, true);
                if (a != null) {
                    if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                        personalLogger.debug("clearOrMoveToPoint: " + a);
                    }
                    return a;
                }
            }
        }
        return null;
    }

    private Action clearOrMoveToNearbyArea(PoliceForce pf, Area target, boolean isMove) {
        StandardEntity posEntity = this.worldInfo.getEntity(pf.getPosition());
        Edge edge = target.getEdgeTo(posEntity.getID());

        if (edge == null) {
            return null;
        }

        Point2D edgeMid = getMiddlePoint(edge.getLine());

        if (posEntity instanceof Road road) {
            if (edge.isPassable()) {
                Collection<Blockade> blockades = this.worldInfo.getBlockades(road).stream()
                        .filter(Blockade::isApexesDefined)
                        .collect(Collectors.toSet());
                Point2D intersect = this.getNearestIntersectPoint(this.getPoint(), this.getPoint(),
                        edgeMid, blockades);
                if (intersect != null) {
                    Action clearToEdgeMid = this.clearPoint(this.getPoint(), intersect, blockades, pf, road, isMove);
                    if (clearToEdgeMid != null) {
                        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                            personalLogger.debug("ClearOrMoveToPoint: " + clearToEdgeMid);
                        }
                        return clearToEdgeMid;
                    }
                }
            }
        }

        if (target instanceof Road road) {

            if (!road.isBlockadesDefined() || this.isRoadGuidelinePassable(road)) {
                return new ActionMove(List.of(posEntity.getID(), target.getID()));
            }

            Action clearActionToGuideline = this.clearOrMoveToRoadGuideline(road, isMove);
            if (clearActionToGuideline != null) {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("ClearOrMoveToRoadGuideline: " + clearActionToGuideline);
                }
                return clearActionToGuideline;
            }
        } else if (target instanceof Building) {

            return new ActionMove(List.of(posEntity.getID(), target.getID()));
        }
        return null;
    }

    private Action clearOrMoveToRoadGuideline(Road road, boolean isMove) {
        if (!road.isBlockadesDefined()) {
            return null;
        }

        Collection<Blockade> blockades = this.getPath(true, this.getMyself().getID(), road.getID())
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast).filter(Objects::nonNull)
                .map(area -> this.worldInfo.getBlockades(area))
                .flatMap(Collection::stream)
                .collect(toSet());

        Line2D guideline = this.guidelineMap.get(road.getID());

        Point2D intersect;
        if (guideline == null) {
            intersect = getPoint(road);
        } else {
            intersect = this.getNearestIntersectPoint(this.getPoint(),
                    guideline.getOrigin(), guideline.getEndPoint(), blockades);
        }

        if (intersect == null) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("No intersect point, maybe passable?");
            }
            return null;
        }

        return this.clearPoint(this.getPoint(), intersect, blockades,
                this.getMyself(), road, isMove);
    }

    private Action moveToPoint(Point2D agentPoint, Point2D goal, Human human, Road road,
            boolean isMove) {

        List<EntityID> path = this.getPath(true, human.getID(), road.getID());
        Collection<Blockade> blockades = path
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast).filter(Objects::nonNull)
                .filter(Area::isBlockadesDefined)
                .map(area -> this.worldInfo.getBlockades(area))
                .flatMap(Collection::stream)
                .filter(Blockade::isApexesDefined)
                .collect(toSet());
        return this.moveToPoint(agentPoint, goal, blockades, human, road, isMove);
    }

    private Action moveToPoint(Point2D agentPoint, Point2D goal, Collection<Blockade> blockades, Human human, Road road,
            boolean isMove) {
        List<EntityID> path = this.getPath(true, human.getID(), road.getID());

        Point2D intersectPoint = this.getNearestIntersectPoint(agentPoint, agentPoint, goal, blockades);
        double agentX = agentPoint.getX();
        double agentY = agentPoint.getY();
        double moveGoalX = goal.getX();
        double moveGoalY = goal.getY();
        double clearGoalX;
        double clearGoalY;
        double distance;
        Vector2D clearVector;

        if (intersectPoint != null) {

            clearGoalX = intersectPoint.getX();
            clearGoalY = intersectPoint.getY();
            moveGoalX = clearGoalX;
            moveGoalY = clearGoalY;

            distance = this.getEuclidDistance(agentX, agentY,
                    moveGoalX, moveGoalY);

            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug(
                        "[Test]: ClearOrMoveToPoint: " + "(moveGoalX, moveGoalY) = " + moveGoalX + ", " + moveGoalY);
            }

            if ((int) moveGoalX == (int) agentX && (int) moveGoalY == (int) agentY) {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("[WARN]: ClearOrMoveToPoint: " + "moveGoalX == agentX && moveGoalY == agentY");
                }
                Point2D lastPoint = this.getLastDifferentPosition();

                if (lastPoint == null) {
                    clearGoalX = agentX + 100;
                    clearGoalY = agentY + 100;
                } else {
                    Vector2D v = new Line2D(lastPoint, agentPoint).getDirection().normalised();
                    clearGoalX = agentX + v.getX() * 100;
                    clearGoalY = agentY + v.getY() * 100;
                }
            }

            if (distance < this.CLEAR_REPAIR_DISTANCE * this.CLEAR_ALLOWANCE_RATIO) {
                clearVector = this.scaleClearVector(this.getVector(
                        agentX, agentY,
                        clearGoalX, clearGoalY));
                int clearScaleX = (int) (agentX + clearVector.getX());
                int clearScaleY = (int) (agentY + clearVector.getY());

                if (!isPoint2DNearPoint2D(new Point2D(clearGoalX, clearGoalY), this.lastClearPoint)) {

                    this.lastClearPoint = new Point2D(clearGoalX, clearGoalY);
                    return new ActionClear(clearScaleX, clearScaleY);
                } else {

                    this.lastClearPoint = new Point2D(clearGoalX, clearGoalY);
                    if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                        personalLogger.debug("[ERROR]: CanNotDetectPointHasCleared: " + "(clearGoalX, clearGoalY) = "
                                + clearGoalX + ", " + clearGoalY);
                    }
                    isMove = true;
                }
            }
            if (isMove) {

                Action action;

                for (EntityID id : path) {
                    Area area = (Area) this.worldInfo.getEntity(id);
                    if (this.isInside(intersectPoint.getX(), intersectPoint.getY(), area.getApexList())) {
                        path = this.getPath(true, human.getPosition(), id);
                        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                            personalLogger
                                    .debug("[NOTE]: intersect point is in" + area + ", recalculate path: " + path);
                        }
                        break;
                    }
                }

                if (path.isEmpty()) {

                    if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                        personalLogger.debug(
                                "[ERROR]: CanNotGetPath: " + "(moveGoalX, moveGoalY) = " + moveGoalX + ", "
                                        + moveGoalY);
                    }
                    action = null;
                } else if (path.size() == 1) {

                    Vector2D v = new Line2D(agentPoint, intersectPoint).getDirection().normalised();
                    moveGoalX = intersectPoint.getX() - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = intersectPoint.getY() - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                } else {

                    EntityID preOfEnd = path.get(path.size() - 2);
                    EntityID end = path.getLast();
                    Area preOfEndArea = (Area) this.worldInfo.getEntity(preOfEnd);
                    Edge e = preOfEndArea.getEdgeTo(end);
                    Point2D midPoint = getMiddlePoint(e.getLine());
                    Vector2D v;
                    if (isPoint2DNearPoint2D(midPoint, intersectPoint)) {

                        Point2D centroidOfpreOfEnd = getPoint(preOfEndArea);
                        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                            personalLogger.debug("[ERROR]: ClearOrMoveToPoint: midPoint == intersectPoint");
                        }

                        v = new Line2D(centroidOfpreOfEnd, intersectPoint).getDirection().normalised();
                    } else {

                        Area endArea = (Area) this.worldInfo.getEntity(end);
                        Point2D endCentroid = getPoint(endArea);

                        Line2D midToCentroid = new Line2D(midPoint, endCentroid);
                        if (isLine2DIntersectBlockades(midToCentroid, blockades)) {

                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("[Test]: ClearOrMoveToPoint: mid -> centroid 与障碍有交点");
                            }
                            v = new Line2D(midPoint, intersectPoint).getDirection().normalised();
                        } else {

                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("[Test]: ClearOrMoveToPoint: mid -> centroid 与障碍无交点");
                            }
                            v = new Line2D(endCentroid, intersectPoint).getDirection().normalised();
                        }
                    }
                    moveGoalX = intersectPoint.getX() - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = intersectPoint.getY() - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                }
                return action;
            }
        } else {

            if (isMove) {
                path = this.getPath(true, human.getPosition(), road.getID());
                Action action;
                if (path.isEmpty()) {

                    if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                        personalLogger.debug(
                                "[ERROR]: CanNotGetPath: " + "(clearX, clearY) = " + moveGoalX + ", " + moveGoalY);
                    }
                    action = null;
                } else if (path.size() == 1) {

                    Vector2D v = new Line2D(agentPoint, goal).getDirection().normalised();
                    moveGoalX = goal.getX() - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = goal.getY() - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                } else {

                    EntityID preOfEnd = path.get(path.size() - 2);
                    EntityID end = path.getLast();
                    Area preOfEndArea = (Area) this.worldInfo.getEntity(preOfEnd);
                    Edge e = preOfEndArea.getEdgeTo(end);
                    Point2D midPoint = getMiddlePoint(e.getLine());
                    Vector2D v = new Line2D(midPoint, goal).getDirection().normalised();

                    moveGoalX = goal.getX() - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = goal.getY() - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                }
                return action;
            }
        }
        return null;
    }

    private Action clearPoint(Point2D agentPoint, Point2D goal, Human human, Road road,
            boolean isMove) {

        Collection<Blockade> blockades = this.getPath(true, this.getMyself().getID(), road.getID())
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast).filter(Objects::nonNull)
                .filter(Area::isBlockadesDefined)
                .map(area -> this.worldInfo.getBlockades(area))
                .flatMap(Collection::stream)
                .filter(Blockade::isApexesDefined)
                .collect(toSet());

        return this.clearPoint(agentPoint, goal, blockades, human, road, isMove);
    }

    private Action clearPoint(Point2D agentPoint, Point2D goal, Collection<Blockade> blockades, Human human, Road road,
            boolean isMove) {
        double distance = this.getEuclidDistance(
                agentPoint.getX(), agentPoint.getY(),
                goal.getX(), goal.getY());
        double clearGoalX = (int) goal.getX();
        double clearGoalY = (int) goal.getY();
        Action action;
        if (distance < this.CLEAR_REPAIR_DISTANCE * this.CLEAR_ALLOWANCE_RATIO) {

            Vector2D vector2D = scaleClearVector(this.getVector(agentPoint, goal));
            int clearScaleX = (int) (agentPoint.getX() + vector2D.getX());
            int clearScaleY = (int) (agentPoint.getY() + vector2D.getY());
            if (!isPoint2DNearPoint2D(new Point2D(clearGoalX, clearGoalY), this.lastClearPoint)) {

                this.lastClearPoint = new Point2D(clearGoalX, clearGoalY);
                action = new ActionClear(clearScaleX, clearScaleY);
            } else {

                this.lastClearPoint = new Point2D(clearGoalX, clearGoalY);
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug(
                            "[ERROR]: CanNotDetectPointHasCleared: " + "(clearScaleX, clearScaleY) = " + clearScaleX
                                    + ", " + clearScaleY);
                }
                List<EntityID> path = this.getPath(true, human.getPosition(), road.getID());
                double moveGoalX, moveGoalY;
                if (path.isEmpty()) {

                    if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                        personalLogger
                                .debug("[ERROR]: CanNotGetPath: " + "(clearScaleX, clearScaleY) = " + clearScaleX + ", "
                                        + clearScaleY);
                    }
                    action = null;
                } else if (path.size() == 1) {

                    Vector2D v = new Line2D(agentPoint, goal).getDirection().normalised();
                    moveGoalX = clearGoalX - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = clearGoalY - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                } else {

                    EntityID preOfEnd = path.get(path.size() - 2);
                    EntityID end = path.getLast();
                    Area preOfEndArea = (Area) this.worldInfo.getEntity(preOfEnd);
                    Edge e = preOfEndArea.getEdgeTo(end);
                    Point2D midPoint = getMiddlePoint(e.getLine());
                    Vector2D v;
                    if (isPoint2DNearPoint2D(midPoint, goal)) {

                        Point2D centroidOfpreOfEnd = getPoint(preOfEndArea);
                        if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                            personalLogger.debug("[ERROR]: ClearPoint: midPoint == goal");
                        }

                        v = new Line2D(centroidOfpreOfEnd, goal).getDirection().normalised();
                    } else {

                        Area endArea = (Area) this.worldInfo.getEntity(end);
                        Point2D endCentroid = getPoint(endArea);

                        Line2D midToCentroid = new Line2D(midPoint, endCentroid);
                        if (isLine2DIntersectBlockades(midToCentroid, blockades)) {

                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("[Test]: ClearPoint: mid -> centroid 与障碍有交点");
                            }
                            v = new Line2D(midPoint, goal).getDirection().normalised();
                        } else {

                            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                                personalLogger.debug("[Test]: ClearPoint: mid -> centroid 与障碍无交点");
                            }
                            v = new Line2D(endCentroid, goal).getDirection().normalised();
                        }
                    }
                    moveGoalX = goal.getX() - v.getX() * this.MOVE_ALLOWANCE_DISTANCE;
                    moveGoalY = goal.getY() - v.getY() * this.MOVE_ALLOWANCE_DISTANCE;
                    action = new ActionMove(path, (int) moveGoalX, (int) moveGoalY);
                }
            }
            return action;
        }
        if (isMove) {

            action = this.moveToPoint(agentPoint, goal, blockades, human, road, true);
            if (action != null) {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("moveToPoint: " + action);
                }
                return action;
            }
        }
        return null;
    }

    private Action makeActionToAvoidError() {
        final EntityID pos = this.agentInfo.getPosition();
        final Area posArea = (Area) this.worldInfo.getEntity(pos);
        final Point2D myPoint = this.getPoint();
        Action action = null;

        if (posArea instanceof Road posRoad) {
            action = this.clearOrMoveToRoadGuideline(posRoad, true);
            if (action != null) {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("clearOrMoveToRoadGuideline: " + action);
                }
                return action;
            }
        }

        Set<EntityID> scope = new HashSet<>();
        scope.add(pos);
        final List<EntityID> neighbours = posArea.getNeighbours();
        scope.addAll(neighbours);
        scope.addAll(neighbours
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast)
                .filter(Objects::nonNull)
                .map(Area::getNeighbours)
                .flatMap(Collection::stream)
                .collect(toSet()));

        final Blockade blockade = scope
                .stream()
                .map(this.worldInfo::getEntity)
                .filter(Objects::nonNull)
                .map(Area.class::cast)
                .filter(Area::isBlockadesDefined)
                .map(Area::getBlockades)
                .flatMap(List::stream)
                .map(this.worldInfo::getEntity)
                .map(Blockade.class::cast)
                .min(comparing(blockade1 -> blockade1 != null ? this.worldInfo.getDistance(
                        this.getMyself(), blockade1) : Integer.MAX_VALUE))
                .orElse(null);

        if (blockade != null) {

            final Point2D clearPoint = this.getClosestPointToBlockade(blockade);
            return this.clearPoint(myPoint, clearPoint, List.of(blockade), this.getMyself(),
                    (Road) this.worldInfo.getPosition(blockade), true);
        }

        if (!messageTargetRoads.isEmpty()) {
            this.target = messageTargetRoads.stream()
                    .min(comparing(r -> this.worldInfo.getDistance(this.getMyself().getID(), r)))
                    .orElse(null);

            action = this.makeActionToAchieveTarget();
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("makeActionToAchieveTarget: " + action);
            }
            if (action != null) {
                return action;
            }
        }

        return null;
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

            randomArea = scope.get(this.randomGen.nextInt(scope.size()));
        }

        if (randomArea != null) {

            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.debug("randomArea: " + randomArea);
            }
            List<EntityID> path = this.getPath(true, myPosArea.getID(), randomArea);
            if (!path.isEmpty()) {

                return new ActionMove(path);
            } else {
                if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                    personalLogger.debug("randomArea is null");
                }
            }
        }

        return null;
    }

    private static boolean isLine2DIntersectBlockades(Line2D line2D, Collection<Blockade> blockades) {
        if (blockades == null || blockades.isEmpty()) {
            return false;
        }

        for (Blockade blockade : blockades) {
            final List<Line2D> blockLines = GeometryTools2D.pointsToLines(
                    GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);

            for (Line2D blockLine : blockLines) {
                if (isLine2DIntersectLine2D(line2D, blockLine)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLine2DIntersectLine2D(Line2D line1, Line2D line2) {
        return getLine2DIntersectLine2D(line1, line2) != null;
    }

    private boolean isPoint2DNearPoint2D(Point2D point1, Point2D point2, double range) {
        if (point1 == null || point2 == null) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.warn("isPoint2DNearPoint2D: point1 or point2 is null");
            }
            return false;
        }
        return GeometryTools2D.getDistance(point1, point2) <= range;
    }

    private boolean isPoint2DNearPoint2D(Point2D point1, Point2D point2) {
        if (point1 == null || point2 == null) {
            if (SEUConstants.PF_ACTION_EXT_CLEAR_LOG) {
                personalLogger.warn("isPoint2DNearPoint2D: point1 or point2 is null");
            }
            return false;
        }
        return GeometryTools2D.getDistance(point1, point2) <= 10.0;
    }

    private boolean isHumanStuckInBlockade(Human human) {

        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        if (!(posEntity instanceof Road road)) {
            return false;
        }

        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
            return false;
        }

        final Point2D agentPoint = new Point2D(human.getX(), human.getY());
        final double agentRad = human.getStandardURN() == StandardEntityURN.CIVILIAN
                ? CIVILIAN_RADIUS
                : AGENT_RADIUS + 200;

        final java.awt.geom.Area intersectArea = this.getCircleIntersectBlockades(agentPoint, agentRad,
                road.getBlockades().stream()
                        .map(this.worldInfo::getEntity)
                        .map(Blockade.class::cast)
                        .collect(Collectors.toList()));

        return !intersectArea.isEmpty();
    }

    private boolean isHumanStuckInBuilding(Human human) {

        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        if (!(posEntity instanceof Building building)) {
            return false;
        }

        return this.getUnpassableEntranceOfBuildingExpand(building)
                .stream()
                .map(this.worldInfo::getEntity)
                .filter(Objects::nonNull)
                .map(Road.class::cast)
                .findAny()
                .isPresent();

    }

    private boolean isIdle() {

        final int time = this.agentInfo.getTime();

        final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();

        return time <= ignored;
    }

    private boolean isNearBlockade(double pX, double pY, Blockade blockade) {
        int[] apex = blockade.getApexes();
        for (int i = 0; i < apex.length - 4; i += 2) {
            if (java.awt.geom.Line2D.ptLineDist(apex[i], apex[i + 1], apex[i + 2], apex[i + 3], pX, pY) < 600) {
                return true;
            }
        }
        if (java.awt.geom.Line2D.ptLineDist(apex[0], apex[1], apex[apex.length - 2], apex[apex.length - 1], pX,
                pY) < 600) {
            return true;
        }
        return false;
    }

    private boolean isInside(double pX, double pY, int[] apex) {
        Point2D p = new Point2D(pX, pY);
        Vector2D v1 = (new Point2D(apex[apex.length - 2], apex[apex.length - 1])).minus(p);
        Vector2D v2 = (new Point2D(apex[0], apex[1])).minus(p);
        double theta = this.getAngle(v1, v2);

        for (int i = 0; i < apex.length - 2; i += 2) {
            v1 = (new Point2D(apex[i], apex[i + 1])).minus(p);
            v2 = (new Point2D(apex[i + 2], apex[i + 3])).minus(p);
            theta += this.getAngle(v1, v2);
        }
        return Math.round(Math.abs((theta / 2) / Math.PI)) >= 1;
    }

    private boolean needRest() {

        final PoliceForce me = (PoliceForce) this.agentInfo.me();

        final int hp = me.getHP();

        final int damage = me.getDamage();

        if (hp == 0) {
            return false;
        }

        if (damage == 0) {
            return false;
        }

        final int time = this.agentInfo.getTime();

        final int die = (int) Math.ceil((double) hp / damage);

        return damage >= DAMAGE_NEEDED_REST || (time + die) < KERNEL_TIME;
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

    private boolean isMoving(int judgeLength) {
        if (this.actionHistory.size() < judgeLength) {
            return false;
        }

        return this.actionHistory
                .stream()
                .skip(Math.max(0, this.actionHistory.size() - judgeLength))
                .allMatch(action -> action instanceof ActionMove);
    }

    private boolean isRoadGuidelinePassable(Road road) {

        if (this.isIdle()) {
            return false;
        }

        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
            return true;
        }

        Line2D guideline2D = this.guidelineMap.getOrDefault(road.getID(), null);
        if (guideline2D == null) {
            personalLogger.debug("Road " + road.getID() + " has no guideline");
            return false;
        }

        Collection<Blockade> blockades = this.worldInfo.getBlockades(road)
                .stream()
                .filter(Blockade::isApexesDefined)
                .collect(toSet());

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

        java.awt.geom.Area guidelineArea = this.expandLine2DToAWTArea(guideline2D, GUIDELINE_WIDTH);

        guidelineArea.intersect(combinedBlockade.get());

        return guidelineArea.isEmpty();
    }

    private boolean isHumanValid(Human human) {
        return human.isPositionDefined() && human.isHPDefined() && human.getHP() > 0;
    }

    private PoliceForce getMyself() {
        return (PoliceForce) this.agentInfo.me();
    }

    private Point2D getPoint() {
        final double x = this.agentInfo.getX();
        final double y = this.agentInfo.getY();
        return new Point2D(x, y);
    }

    private static Point2D getPoint(Area area) {
        final double x = area.getX();
        final double y = area.getY();
        return new Point2D(x, y);
    }

    private static Point2D getPoint(Human human) {
        final double x = human.getX();
        final double y = human.getY();
        return new Point2D(x, y);
    }

    private static Point2D getMiddlePoint(Line2D line) {
        return line.getPoint(0.5);
    }

    private Point2D getLastDifferentPosition() {

        rescuecore2.misc.geometry.Point2D currentPoint = this.getPoint();

        if (this.pointHistory.isEmpty()) {
            return null;
        }

        Object[] points = this.pointHistory.toArray();

        for (int i = points.length - 1; i >= 0; i--) {
            Point2D historyPoint = (Point2D) points[i];
            if (!isPoint2DNearPoint2D(historyPoint, currentPoint)) {
                return historyPoint;
            }
        }

        return null;
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

        if (path.size() >= 3) {
            List<EntityID> simplified = new ArrayList<>();
            for (EntityID id : path) {
                simplified.add(id);
                while (simplified.size() >= 3) {
                    int last = simplified.size() - 1;
                    EntityID a = simplified.get(last - 2);
                    EntityID b = simplified.get(last - 1);
                    EntityID c = simplified.get(last);
                    Entity entity = this.worldInfo.getEntity(a);
                    if (entity instanceof Area && !((Area) entity).getEdgesTo(c).isEmpty()) {

                        simplified.remove(last - 1);
                    } else {
                        break;
                    }
                }
            }

            path = simplified;
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

        if (se == null) {
            return null;
        }
        if (se instanceof Human || se.getStandardURN() == BLOCKADE) {
            StandardEntity position = this.worldInfo.getPosition(entityID);
            return position == null ? null : position.getID();
        }
        return entityID;
    }

    private java.awt.geom.Area getCircleIntersectBlockades(Point2D point2D, double radius,
            Collection<Blockade> blockades) {

        final Optional<java.awt.geom.Area> posBlockades = blockades.stream()
                .filter(Objects::nonNull)
                .map(Blockade::getShape)
                .map(java.awt.geom.Area::new)
                .reduce((acc, v) -> {
                    acc.add(v);
                    return acc;
                });

        final java.awt.geom.Area circleArea = makeAWTArea(point2D, radius);

        if (posBlockades.isEmpty()) {
            return new java.awt.geom.Area();
        }

        circleArea.intersect(posBlockades.get());

        return circleArea;
    }

    private java.awt.geom.Area getHumanIntersectBlockade(Human human) {

        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        if (!(posEntity instanceof Road road)) {
            return new java.awt.geom.Area();
        }

        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
            return new java.awt.geom.Area();
        }

        final Point2D agentPoint = new Point2D(human.getX(), human.getY());
        final double agentRad = human.getStandardURN() == StandardEntityURN.CIVILIAN
                ? CIVILIAN_RADIUS
                : AGENT_RADIUS + 200;

        List<EntityID> scope = new ArrayList<>();
        scope.add(road.getID());
        scope.addAll(road.getNeighbours());
        Collection<Blockade> blockades = scope
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Area.class::cast)
                .filter(Objects::nonNull)
                .filter(Area::isBlockadesDefined)
                .map(a -> this.worldInfo.getBlockades(a))
                .flatMap(Collection::stream)
                .filter(Blockade::isApexesDefined)
                .toList();

        final java.awt.geom.Area intersectArea = this.getCircleIntersectBlockades(agentPoint, agentRad,
                blockades);

        return intersectArea;
    }

    private Point2D getNearestIntersectPoint(Point2D agent, Point2D start, Point2D end,
            Collection<Blockade> blockades) {
        if (blockades == null || blockades.isEmpty()) {
            return null;
        }
        Point2D nearestPoint = null;
        double minDist = Double.POSITIVE_INFINITY;
        Line2D targetLine = new Line2D(start, end);

        Point2D intersectPoint;
        for (Blockade blockade : blockades) {
            final List<Line2D> blockLines = GeometryTools2D.pointsToLines(
                    GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);
            for (Line2D blockLine : blockLines) {
                intersectPoint = getLine2DIntersectLine2D(targetLine, blockLine);
                if (intersectPoint != null) {
                    double dist = GeometryTools2D.getDistance(agent, intersectPoint);
                    if (dist < minDist) {
                        minDist = dist;
                        nearestPoint = intersectPoint;
                    }
                }
            }
        }
        return nearestPoint;
    }

    private Point2D getClosestPointToBlockade(Blockade blockade) {
        final EntityID pos = this.agentInfo.getPosition();
        final Area posArea = (Area) this.worldInfo.getEntity(pos);

        final List<Line2D> lines = GeometryTools2D.pointsToLines(
                GeometryTools2D.vertexArrayToPoints(blockade.getApexes()), true);

        final Point2D agent = new Point2D(this.agentInfo.getX(), this.agentInfo.getY());

        final Point2D closest = lines
                .stream()
                .map(l -> GeometryTools2D.getClosestPointOnSegment(l, agent))
                .min(Comparator.comparingDouble(p -> GeometryTools2D.getDistance(agent, p)))
                .orElse(null);

        return closest;
    }

    private List<Blockade> getNearestBlockadeBlockingHuman(Human human) {
        List<Blockade> intersectBlockades = new ArrayList<>();
        List<Blockade> blockades;

        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        if (!(posEntity instanceof Road road)) {
            return intersectBlockades;
        }

        if (!road.isBlockadesDefined() || road.getBlockades().isEmpty()) {
            return intersectBlockades;
        }

        blockades = road.getBlockades()
                .stream()
                .map(this.worldInfo::getEntity)
                .map(Blockade.class::cast)
                .sorted(comparing(e -> this.worldInfo.getDistance(human.getID(), e.getID())))
                .toList();

        final Point2D agentPoint = new Point2D(human.getX(), human.getY());
        final double agentRad = human.getStandardURN() == StandardEntityURN.CIVILIAN
                ? CIVILIAN_RADIUS
                : AGENT_RADIUS;
        final java.awt.geom.Area agentArea = makeAWTArea(agentPoint, agentRad);

        for (Blockade blockade : blockades) {

            java.awt.geom.Area shape = new java.awt.geom.Area(blockade.getShape());
            shape = (java.awt.geom.Area) shape.clone();

            shape.intersect(agentArea);

            if (!shape.isEmpty()) {
                intersectBlockades.add(blockade);
            }
        }

        return intersectBlockades;
    }

    private List<Blockade> getNearestBlockadeBlockingHumanInBuilding(Human human) {

        final EntityID pos = human.getPosition();
        final StandardEntity posEntity = this.worldInfo.getEntity(pos);

        List<Blockade> blockades = new ArrayList<>();

        if (!(posEntity instanceof Building building)) {
            return blockades;
        }

        blockades = this.getUnpassableEntranceOfBuildingExpand(building)
                .stream()
                .map(this.worldInfo::getEntity)
                .filter(Objects::nonNull)
                .map(Road.class::cast)
                .map(Road::getBlockades)
                .flatMap(Collection::stream)
                .map(this.worldInfo::getEntity)
                .map(Blockade.class::cast)
                .sorted(comparing(e -> this.worldInfo.getDistance(human.getID(), e.getID())))
                .toList();
        return blockades;
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

                for (EntityID neighborID : building.getNeighbours()) {
                    StandardEntity neighbor = this.worldInfo.getEntity(neighborID);

                    if (visited.contains(neighbor)) {
                        continue;
                    }

                    visited.add(neighbor);

                    if (neighbor instanceof Road road) {
                        if (this.isRoadGuidelinePassable(road)) {
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

    private static Point2D getLine2DIntersectLine2D(Line2D a, Line2D b) {
        double ax = a.getOrigin().getX(), ay = a.getOrigin().getY();
        double dx = a.getEndPoint().getX(), dy = a.getEndPoint().getY();
        double bx = b.getOrigin().getX(), by = b.getOrigin().getY();
        double cx = b.getEndPoint().getX(), cy = b.getEndPoint().getY();

        double ux = dx - ax, uy = dy - ay;
        double vx = cx - bx, vy = cy - by;
        double wx = bx - ax, wy = by - ay;

        double cross = ux * vy - uy * vx;
        if (Math.abs(cross) < 1e-8) {

            return null;
        }

        double t = (wx * vy - wy * vx) / cross;
        double s = (wx * uy - wy * ux) / cross;

        if (t >= -1e-8 && t <= 1 + 1e-8 && s >= -1e-8 && s <= 1 + 1e-8) {
            double ix = ax + t * ux;
            double iy = ay + t * uy;
            return new Point2D(ix, iy);
        }
        return null;
    }

    private static java.awt.geom.Line2D convertToAWTLine(Line2D line) {

        final double x1 = line.getOrigin().getX();
        final double x2 = line.getEndPoint().getX();

        final double y1 = line.getOrigin().getY();
        final double y2 = line.getEndPoint().getY();

        return new java.awt.geom.Line2D.Double(x1, y1, x2, y2);
    }

    private double getCrossProduct(Line2D line, Point2D point) {
        double X = point.getX();
        double Y = point.getY();
        double X1 = line.getOrigin().getX();
        double Y1 = line.getOrigin().getY();
        double X2 = line.getEndPoint().getX();
        double Y2 = line.getEndPoint().getY();
        return ((X2 - X1) * (Y - Y1) - (X - X1) * (Y2 - Y1));
    }

    private Area getEuclidNearest(Set<Area> entityIDs, double refX, double refY) {
        if (entityIDs.isEmpty()) {
            return null;
        }
        return entityIDs.stream()
                .min(Comparator.comparingDouble(
                        a -> this.getEuclidDistance(a.getX(), a.getY(), refX, refY)))
                .get();
    }

    private static double getEuclidDistance(double fromX, double fromY, double toX, double toY) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        return Math.hypot(dx, dy);
    }

    private double getAngle(Vector2D v1, Vector2D v2) {
        return Util.getAngle(v1, v2);
    }

    private Vector2D getVector(double fromX, double fromY, double toX, double toY) {
        return (new Point2D(toX, toY)).minus(new Point2D(fromX, fromY));
    }

    private Vector2D getVector(Point2D from, Point2D to) {
        return to.minus(from);
    }

    private Vector2D scaleClearVector(Vector2D vector) {
        return vector.normalised().scale(this.CLEAR_REPAIR_DISTANCE);
    }

    private Vector2D scaleMove(Vector2D vector) {
        return vector.normalised().scale(100);
    }

    private java.awt.geom.Area expandLine2DToAWTArea(Line2D line, int lineWidth) {

        final double x1 = line.getOrigin().getX();
        final double x2 = line.getEndPoint().getX();
        final double y1 = line.getOrigin().getY();
        final double y2 = line.getEndPoint().getY();

        final double length = Math.hypot(x2 - x1, y2 - y1);

        final double ldx = (y2 - y1) * lineWidth / 2 / length;
        final double ldy = (x1 - x2) * lineWidth / 2 / length;

        final double rdx = (y1 - y2) * lineWidth / 2 / length;
        final double rdy = (x2 - x1) * lineWidth / 2 / length;

        final Point2D p1 = new Point2D(x1 + ldx, y1 + ldy);
        final Point2D p2 = new Point2D(x2 + ldx, y2 + ldy);
        final Point2D p3 = new Point2D(x2 + rdx, y2 + rdy);
        final Point2D p4 = new Point2D(x1 + rdx, y1 + rdy);

        return this.makeAWTArea(new Point2D[] { p1, p2, p3, p4 });
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

    private Point2D getPolygonCentroid(Point2D[] polygon) {
        int n = polygon.length;
        if (n == 0)
            return null;
        if (n == 1)
            return polygon[0];
        if (n == 2) {
            return new Point2D(
                    (polygon[0].getX() + polygon[1].getX()) / 2,
                    (polygon[0].getY() + polygon[1].getY()) / 2);
        }

        double area = 0;
        double cx = 0, cy = 0;

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double xi = polygon[i].getX();
            double yi = polygon[i].getY();
            double xj = polygon[j].getX();
            double yj = polygon[j].getY();

            double cross = xi * yj - xj * yi;
            area += cross;
            cx += (xi + xj) * cross;
            cy += (yi + yj) * cross;
        }

        area /= 2.0;
        if (Math.abs(area) < 1e-10)
            return null;

        cx /= (6.0 * area);
        cy /= (6.0 * area);
        return new Point2D(cx, cy);
    }

    private List<Point2D[]> areaToPolygons(java.awt.geom.Area area) {
        List<Point2D[]> polygons = new ArrayList<>();
        List<Point2D> currentPolygon = new ArrayList<>();

        PathIterator it = area.getPathIterator(null);
        double[] coords = new double[6];

        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:

                    currentPolygon = new ArrayList<>();
                    currentPolygon.add(new Point2D(coords[0], coords[1]));
                    break;

                case PathIterator.SEG_LINETO:
                    currentPolygon.add(new Point2D(coords[0], coords[1]));
                    break;

                case PathIterator.SEG_CLOSE:

                    if (!currentPolygon.isEmpty()) {
                        polygons.add(currentPolygon.toArray(new Point2D[0]));
                    }
                    currentPolygon = new ArrayList<>();
                    break;

                case PathIterator.SEG_QUADTO:
                case PathIterator.SEG_CUBICTO:

                    currentPolygon.add(new Point2D(coords[0], coords[1]));
                    break;
            }
            it.next();
        }

        return polygons;
    }

    public Point2D getAreaCentroid(java.awt.geom.Area area) {
        List<Point2D[]> polygons = this.areaToPolygons(area);

        Point2D[] outerBoundary = polygons.stream()
                .max(Comparator.comparingInt(p -> p.length))
                .orElse(new Point2D[0]);

        double cx = 0, cy = 0;
        for (Point2D p : outerBoundary) {
            cx += p.getX();
            cy += p.getY();
        }
        return new Point2D(cx / outerBoundary.length, cy / outerBoundary.length);
    }

}
