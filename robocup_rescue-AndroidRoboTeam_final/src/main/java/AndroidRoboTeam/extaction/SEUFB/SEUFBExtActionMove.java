package AndroidRoboTeam.extaction.SEUFB;

import AndroidRoboTeam.world.SEUConstants;

import AndroidRoboTeam.module.algorithm.StuckHumans;
import AndroidRoboTeam.world.SEUWorldService;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;

import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;

import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;

public class SEUFBExtActionMove extends ExtAction {
    private static final int ACTION_HISTORY_SIZE = 15;
    private static final int POINT_HISTORY_SIZE = 15;
    private static final int AREA_HISTORY_SIZE = 15;
    private static final int STUCK_THRESHOLD = 2000;
    private static final int WALL_STILL_DISTANCE = 1000;
    private static final int WALL_JUDGE_LENGTH = 3;
    private static final int MOVE_COUNT_THRESHOLD = 1;
    private static final int AGENT_RADIUS = 500;

    private final PathPlanning pathPlanning;
    private final int thresholdRest;
    private final SEUWorldService world;
    private final StuckHumans stuckHumans;
    private final Random random = new Random(42);

    private int kernelTime;
    private EntityID target;
    private Pair<Integer, Integer> selfLocation;
    private Point lastPosition;
    private int lastMoveTime;
    private boolean stuck;
    private int lastStuckUpdateTime = -1;
    private int wallEscapeState = 0;
    private int isMovingCount = 0;

    private final List<Action> actionHistory = new LinkedList<>();
    private final List<Point2D> pointHistory = new LinkedList<>();
    private final List<Area> areaHistory = new LinkedList<>();

    public SEUFBExtActionMove(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
            ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionExtMove.rest", 100);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionMove.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionMove.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionMove.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                break;
            default:
                throw new IllegalStateException("Unsupported scenario mode: " + scenarioInfo.getMode());
        }

        this.world = moduleManager.getModule("WorldService.Default", SEUConstants.WORLD_HELPER_DEFAULT_PATH);
        this.stuckHumans = new StuckHumans(this.world, worldInfo);
        this.selfLocation = worldInfo.getLocation(agentInfo.getID());
        this.lastPosition = this.toPoint(this.selfLocation);
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        this.world.precompute(precomputeData);
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.world.resume(precomputeData);
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.world.preparate();
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        this.world.updateInfo(messageManager);
        this.selfLocation = this.worldInfo.getLocation(this.agentInfo.getID());
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        if (target == null) {
            return this;
        }

        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity != null) {
            if (entity.getStandardURN() == BLOCKADE) {
                entity = this.worldInfo.getEntity(((Blockade) entity).getPosition());
            } else if (entity instanceof Human) {
                entity = this.worldInfo.getPosition((Human) entity);
            }
            if (entity instanceof Area) {
                this.target = entity.getID();
            }
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        Human agent = (Human) this.agentInfo.me();

        this.recordHistory();

        if (this.needRest(agent)) {
            this.result = this.calcRest(agent, this.pathPlanning, this.target);
            if (this.result != null) {
                return this;
            }
        }

        Action wallEscapeAction = this.tryWallEscape();
        if (wallEscapeAction != null) {
            this.result = wallEscapeAction;
            return this;
        }

        if (this.target == null) {
            return this;
        }

        this.pathPlanning.setFrom(agent.getPosition());
        this.pathPlanning.setDestination(this.target);
        List<EntityID> path = this.pathPlanning.calc().getResult();
        if (path != null && !path.isEmpty()) {
            this.result = this.moveOnPath(path);
        }
        return this;
    }

    private Action tryWallEscape() {
        if (this.wallEscapeState == 1) {
            Action randomWalkAction = this.randomWalk();
            if (randomWalkAction != null) {
                this.wallEscapeState = 0;
                return randomWalkAction;
            }
        }
        this.wallEscapeState = 0;

        if (!this.isMoving(WALL_JUDGE_LENGTH)
                || (!this.isStill(WALL_JUDGE_LENGTH, WALL_STILL_DISTANCE) && !this.isStuckInArea(WALL_JUDGE_LENGTH))) {
            this.isMovingCount = 0;
            return null;
        }

        ++this.isMovingCount;
        if (this.isMovingCount <= MOVE_COUNT_THRESHOLD) {
            return null;
        }

        Area currentArea = this.agentInfo.getPositionArea();
        Point2D currentPoint = this.getPoint();
        if (currentPoint == null) {
            return this.randomWalk();
        }

        List<Point2D> passableMidPoints = currentArea.getEdges()
                .stream()
                .filter(Edge::isPassable)
                .map(Edge::getLine)
                .filter(line -> this.getLength(line) > AGENT_RADIUS)
                .map(this::getMiddlePoint)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(point -> GeometryTools2D.getDistance(currentPoint, point)))
                .toList();

        if (passableMidPoints.isEmpty()) {
            return this.randomWalk();
        }

        Point2D midPoint = passableMidPoints.get(this.random.nextInt(passableMidPoints.size()));
        this.wallEscapeState = 1;
        return new ActionMove(List.of(currentArea.getID()), (int) midPoint.getX(), (int) midPoint.getY());
    }

    private Action moveOnPath(List<EntityID> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        List<EntityID> movePath = new LinkedList<>(path);
        EntityID selfPositionId = this.world.getSelfPositionId();
        if (movePath.isEmpty() || !movePath.getFirst().equals(selfPositionId)) {
            movePath.addFirst(selfPositionId);
        }

        Action action = null;
        boolean stuckFlag = this.agentInfo.getTime() >= this.scenarioInfo.getKernelAgentsIgnoreuntil()
                && this.isStuck(movePath);
        if (stuckFlag) {
            action = this.stuckHumans.calc(movePath);

        }

        this.lastMoveTime = this.agentInfo.getTime();
        if (action == null) {
            action = new ActionMove(path);
        }
        return action;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) {
            return false;
        }

        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == 0) {
            this.kernelTime = this.resolveKernelTime();
        }
        return damage >= this.thresholdRest
                || (this.kernelTime != -1 && (activeTime + this.agentInfo.getTime()) < this.kernelTime);
    }

    private Action calcRest(Human human, PathPlanning pathPlanning, EntityID target) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = new HashSet<>(this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE));
        int currentSize = refuges.size();
        if (refuges.contains(position)) {
            return new ActionRest();
        }

        List<EntityID> firstResult = null;
        while (!refuges.isEmpty()) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path == null || path.isEmpty()) {
                break;
            }
            if (firstResult == null) {
                firstResult = new ArrayList<>(path);
                if (target == null) {
                    break;
                }
            }

            EntityID refugeID = path.getLast();
            pathPlanning.setFrom(refugeID);
            pathPlanning.setDestination(target);
            List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
            if (fromRefugeToTarget != null && !fromRefugeToTarget.isEmpty()) {
                return new ActionMove(path);
            }

            refuges.remove(refugeID);
            if (currentSize == refuges.size()) {
                break;
            }
            currentSize = refuges.size();
        }
        return firstResult != null ? new ActionMove(firstResult) : null;
    }

    public boolean isStuck(List<EntityID> path) {
        if (this.lastStuckUpdateTime == this.agentInfo.getTime()) {
            return this.stuck;
        }
        this.lastStuckUpdateTime = this.agentInfo.getTime();

        if (this.lastMoveTime < this.scenarioInfo.getKernelAgentsIgnoreuntil()) {
            this.stuck = false;
            return false;
        }
        if (!path.isEmpty() && path.getLast().equals(this.agentInfo.getPosition())) {
            this.stuck = false;
            return false;
        }

        Point currentPosition = this.toPoint(this.selfLocation);
        if (currentPosition == null) {
            this.stuck = false;
            return false;
        }

        int moveDistance = this.getDistance(currentPosition, this.lastPosition);
        this.lastPosition = currentPosition;

        Collection<Blockade> blockadesInRange = this.world.getBlockadesInRange(STUCK_THRESHOLD);
        this.stuck = moveDistance <= STUCK_THRESHOLD && blockadesInRange != null && !blockadesInRange.isEmpty();
        return this.stuck;
    }

    private void recordHistory() {
        Point2D point = this.getPoint();
        Area area = this.agentInfo.getPositionArea();
        if (point != null) {
            this.pointHistory.add(point);
            if (this.pointHistory.size() > POINT_HISTORY_SIZE) {
                this.pointHistory.removeFirst();
            }
        }
        this.areaHistory.add(area);
        if (this.areaHistory.size() > AREA_HISTORY_SIZE) {
            this.areaHistory.removeFirst();
        }

        try {
            Action executedAction = this.agentInfo.getExecutedAction(-1);
            if (executedAction != null) {
                this.actionHistory.add(executedAction);
                if (this.actionHistory.size() > ACTION_HISTORY_SIZE) {
                    this.actionHistory.removeFirst();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private Action randomWalk() {
        Area myArea = this.agentInfo.getPositionArea();

        List<EntityID> scope = new ArrayList<>(myArea.getNeighbours());
        for (EntityID neighbour : myArea.getNeighbours()) {
            StandardEntity entity = this.worldInfo.getEntity(neighbour);
            if (entity instanceof Area) {
                for (EntityID next : ((Area) entity).getNeighbours()) {
                    if (!scope.contains(next)) {
                        scope.add(next);
                    }
                }
            }
        }
        if (scope.isEmpty()) {
            return null;
        }

        EntityID randomArea = scope.get(this.random.nextInt(scope.size()));
        List<EntityID> path = this.getPath(this.agentInfo.getPosition(), randomArea);
        if (path == null || path.isEmpty()) {
            return null;
        }
        return new ActionMove(path);
    }

    private List<EntityID> getPath(EntityID from, EntityID dest) {
        if (from == null || dest == null) {
            return null;
        }
        this.pathPlanning.setFrom(this.getNonnullPosition(from));
        this.pathPlanning.setDestination(this.getNonnullPosition(dest));
        return this.pathPlanning.calc().getResult();
    }

    private EntityID getNonnullPosition(EntityID entityID) {
        StandardEntity entity = this.worldInfo.getEntity(entityID);
        if (entity instanceof Human || entity instanceof Blockade) {
            StandardEntity position = this.worldInfo.getPosition(entityID);
            return position != null ? position.getID() : entityID;
        }
        return entityID;
    }

    private boolean isMoving(int judgeLength) {
        if (this.actionHistory.size() < judgeLength) {
            return false;
        }
        for (int i = this.actionHistory.size() - judgeLength; i < this.actionHistory.size(); i++) {
            if (!(this.actionHistory.get(i) instanceof ActionMove)) {
                return false;
            }
        }
        return true;
    }

    private boolean isStill(int judgeLength, double range) {
        if (this.pointHistory.size() < judgeLength) {
            return false;
        }
        Point2D currentPoint = this.getPoint();
        if (currentPoint == null) {
            return false;
        }
        for (int i = this.pointHistory.size() - judgeLength; i < this.pointHistory.size(); i++) {
            if (GeometryTools2D.getDistance(this.pointHistory.get(i), currentPoint) > range) {
                return false;
            }
        }
        return true;
    }

    private boolean isStuckInArea(int judgeLength) {
        if (this.areaHistory.size() < judgeLength) {
            return false;
        }
        Area currentArea = this.agentInfo.getPositionArea();
        for (int i = this.areaHistory.size() - judgeLength; i < this.areaHistory.size(); i++) {
            if (!currentArea.getID().equals(this.areaHistory.get(i).getID())) {
                return false;
            }
        }
        return true;
    }

    private Point2D getPoint() {
        if (!(this.agentInfo.me() instanceof Human human) || !human.isXDefined() || !human.isYDefined()) {
            return null;
        }
        return new Point2D(this.agentInfo.getX(), this.agentInfo.getY());
    }

    private Point2D getMiddlePoint(Line2D line) {
        return line == null ? null : line.getPoint(0.5);
    }

    private double getLength(Line2D line) {
        return line == null ? 0.0 : GeometryTools2D.getDistance(line.getOrigin(), line.getEndPoint());
    }

    private int getDistance(Point p1, Point p2) {
        if (p1 == null || p2 == null) {
            return Integer.MAX_VALUE;
        }
        double dx = p1.getX() - p2.getX();
        double dy = p1.getY() - p2.getY();
        return (int) Math.hypot(dx, dy);
    }

    private Point toPoint(Pair<Integer, Integer> location) {
        if (location == null) {
            return null;
        }
        return new Point(location.first(), location.second());
    }

    private int resolveKernelTime() {
        try {
            return this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            return -1;
        }
    }
}
