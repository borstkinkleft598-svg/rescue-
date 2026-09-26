package AndroidRoboTeam.module.complex.SEUPF;

import AndroidRoboTeam.extaction.SEUPF.SEUGuideline;

import AndroidRoboTeam.world.visualizer.GuidelineVisualizer;

import adf.core.agent.communication.MessageManager;

import adf.core.agent.develop.DevelopData;

import adf.core.agent.info.AgentInfo;

import adf.core.agent.info.ScenarioInfo;

import adf.core.agent.info.WorldInfo;

import adf.core.agent.module.ModuleManager;

import adf.core.agent.precompute.PrecomputeData;

import adf.core.component.module.AbstractModule;

import adf.core.component.module.algorithm.Clustering;

import adf.core.component.module.algorithm.PathPlanning;

import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;

import rescuecore2.standard.entities.*;

import rescuecore2.worldmodel.EntityID;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.HYDRANT;
import static rescuecore2.standard.entities.StandardEntityURN.POLICE_OFFICE;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;
import static rescuecore2.standard.entities.StandardEntityURN.ROAD;

import java.util.*;
import java.util.stream.Collectors;

import adf.core.debug.DefaultLogger;
import org.apache.log4j.Logger;

import AndroidRoboTeam.world.SEUConstants;

public class GuidelineCreator extends AbstractModule {

    private PathPlanning pathPlanning;

    private Clustering clustering;

    private List<SEUGuideline> guidelineList = new ArrayList<>();

    private Map<EntityID, Line2D> guidelineMap = new HashMap<>();

    private Set<EntityID> countedRoad = new HashSet<>();

    private Set<EntityID> countedEntrance = new HashSet<>();

    private int roadsize;

    private GuidelineVisualizer guidelineVisualizer;

    private final Logger logger = DefaultLogger.getLogger("GuidelineCreator");

    public static final String KEY_GUIDELINE = "GuidelineCreator.guideline";
    public static final String KEY_START_X = "GuidelineCreator.start_x";
    public static final String KEY_START_Y = "GuidelineCreator.start_y";
    public static final String KEY_END_X = "GuidelineCreator.end_x";
    public static final String KEY_END_Y = "GuidelineCreator.end_y";
    public static final String KEY_ROAD_SIZE = "GuidelineCreator.road_size";
    public static final String KEY_ISENTRANCE = "GuidelineCreator.is_entrance";

    public GuidelineCreator(AgentInfo ai, WorldInfo wi, ScenarioInfo si,
            ModuleManager moduleManager, DevelopData developData) {

        super(ai, wi, si, moduleManager, developData);

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule(
                        "SampleRoadDetector.PathPlanning");
                this.clustering = moduleManager.getModule(
                        "SampleRoadDetector.Clustering");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule(
                        "SampleRoadDetector.PathPlanning");
                this.clustering = moduleManager.getModule(
                        "SampleRoadDetector.Clustering");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule(
                        "SampleRoadDetector.PathPlanning");
                this.clustering = moduleManager.getModule(
                        "SampleRoadDetector.Clustering");
                break;
        }
    }

    private class DistanceIDSorter implements Comparator<EntityID> {
        private WorldInfo worldInfo;
        private EntityID reference;

        DistanceIDSorter(WorldInfo worldInfo, EntityID reference) {
            this.worldInfo = worldInfo;
            this.reference = reference;
        }

        DistanceIDSorter(WorldInfo worldInfo, StandardEntity reference) {
            this.worldInfo = worldInfo;
            this.reference = reference.getID();
        }

        public int compare(EntityID a, EntityID b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d2 - d1;
        }
    }

    @Override
    public AbstractModule calc() {

        return this;
    }

    @Override
    public AbstractModule updateInfo(MessageManager messageManager) {

        super.updateInfo(messageManager);

        if (getCountUpdateInfo() >= 2) {
            return this;
        }

        this.clustering.updateInfo(messageManager);
        this.pathPlanning.updateInfo(messageManager);
        return null;
    }

    @Override
    public AbstractModule precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);

        if (getCountPrecompute() >= 2) {
            return this;
        }

        System.out.println("Creating guidelines...");

        this.createGuideline();

        this.roadsize = this.guidelineList.size();

        precomputeData.setInteger(KEY_ROAD_SIZE, this.roadsize);

        ArrayList<java.awt.geom.Line2D> line2DS = new ArrayList<>();

        for (int i = 0; i < this.roadsize; ++i) {

            line2DS.add(new java.awt.geom.Line2D.Double(
                    this.guidelineList.get(i).getStartPoint().getX(),
                    this.guidelineList.get(i).getStartPoint().getY(),
                    this.guidelineList.get(i).getEndPoint().getX(),
                    this.guidelineList.get(i).getEndPoint().getY()));

            precomputeData.setDouble(KEY_START_X + i, this.guidelineList.get(i).getStartPoint().getX());
            precomputeData.setDouble(KEY_START_Y + i, this.guidelineList.get(i).getStartPoint().getY());
            precomputeData.setDouble(KEY_END_X + i, this.guidelineList.get(i).getEndPoint().getX());
            precomputeData.setDouble(KEY_END_Y + i, this.guidelineList.get(i).getEndPoint().getY());

            precomputeData.setEntityID(KEY_GUIDELINE + i, this.guidelineList.get(i).getSelfID());
            precomputeData.setBoolean(KEY_ISENTRANCE + i, this.guidelineList.get(i).getEntranceState());
        }

        this.clustering.precompute(precomputeData);
        this.pathPlanning.precompute(precomputeData);

        if (SEUConstants.DEBUG_GUIDELINE_VISUALIZATION) {
            List<StandardEntity> entities = new ArrayList<>(
                    this.worldInfo.getEntitiesOfType(
                            ROAD, HYDRANT,
                            BUILDING, GAS_STATION,
                            REFUGE,
                            POLICE_OFFICE, FIRE_STATION, AMBULANCE_CENTRE));
            guidelineVisualizer = new GuidelineVisualizer(this, entities);
            guidelineVisualizer.visualize();
        }
        return this;
    }

    @Override
    public AbstractModule resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);

        if (getCountResume() >= 2) {
            return this;
        }

        this.roadsize = precomputeData.getInteger(KEY_ROAD_SIZE);

        ArrayList<java.awt.geom.Line2D> line2DS = new ArrayList<>();

        for (int i = 0; i < this.roadsize; ++i) {

            double startx = precomputeData.getDouble(KEY_START_X + i);
            double starty = precomputeData.getDouble(KEY_START_Y + i);
            Point2D start = new Point2D(startx, starty);

            double endx = precomputeData.getDouble(KEY_END_X + i);
            double endy = precomputeData.getDouble(KEY_END_Y + i);

            Boolean is_entrance = precomputeData.getBoolean(KEY_ISENTRANCE + i);
            Point2D end = new Point2D(endx, endy);

            Road road = (Road) this.worldInfo.getEntity(
                    precomputeData.getEntityID(KEY_GUIDELINE + i));

            SEUGuideline line = new SEUGuideline(road, start, end, is_entrance);

            if (!this.guidelineList.contains(line)) {
                this.guidelineList.add(line);

                this.guidelineMap.put(line.getSelfID(), line.getGuideline());
                line2DS.add(new java.awt.geom.Line2D.Double(startx, starty, endx, endy));
            }
        }

        this.clustering.resume(precomputeData);
        this.pathPlanning.resume(precomputeData);
        return this;
    }

    @Override
    public AbstractModule preparate() {
        super.preparate();

        if (getCountPreparate() >= 2) {
            return this;
        }

        this.createGuideline();

        this.clustering.preparate();
        this.pathPlanning.preparate();
        return this;
    }

    public List<SEUGuideline> getGuidelineList() {
        return this.guidelineList;
    }

    public Map<EntityID, Line2D> getGuidelineMap() {
        return this.guidelineMap;
    }

    private Line2D getGuideline(Road road) {

        List<Edge> nonEntranceEdges = new ArrayList<>();

        List<Edge> passableEdges = new ArrayList<>();

        for (Edge edge : road.getEdges()) {
            boolean isEntranceEdge = false;

            for (EntityID neighbour : road.getNeighbours()) {
                Edge neighbourEdge = road.getEdgeTo(neighbour);
                if (this.countedEntrance.contains(neighbour) &&
                        this.getMidPoint(edge).equals(this.getMidPoint(neighbourEdge))) {
                    isEntranceEdge = true;
                    break;
                }
            }

            if (edge.isPassable()) {
                passableEdges.add(edge);
                if (!isEntranceEdge) {
                    nonEntranceEdges.add(edge);
                }
            }
        }

        if (nonEntranceEdges.size() > 1) {
            Line2D guideline = this.getPassibleLongestLine(road, nonEntranceEdges);
            if (guideline != null) {
                return guideline;
            }
        } else if (nonEntranceEdges.size() == 1) {
            Edge only = nonEntranceEdges.get(0);
            Point2D onlyMid = this.getMidPoint(only);

            Edge oppositeEdge = this.getOppositeEdge(road, only, false);

            Point2D answerPoint = this.getMidPoint(oppositeEdge);

            Line2D guideline = new Line2D(onlyMid, answerPoint);
            return guideline;
        } else {

            if (passableEdges.size() > 1) {
                Line2D guideline = this.getPassibleLongestLine(road, passableEdges);
                if (guideline != null) {
                    return guideline;
                }
            } else if (passableEdges.size() == 1) {
                Edge only = passableEdges.get(0);
                Point2D onlyMid = this.getMidPoint(only);

                Edge oppositeEdge = this.getOppositeEdge(road, only, false);
                if (oppositeEdge != null) {
                    Point2D answerPoint = this.getMidPoint(oppositeEdge);
                    Line2D guideline = new Line2D(onlyMid, answerPoint);
                    return guideline;
                }
            }
        }

        if (SEUConstants.DEBUG_GUIDELINE_TERMINAL_OUTPUT) {
            System.out.println("Can't find RoadID:" + road.getID().getValue());
            System.out.println("Passible edges num: " + nonEntranceEdges.size());
        }
        return null;
    }

    private void createGuideline() {

        for (StandardEntity se : this.worldInfo.getEntitiesOfType(
                StandardEntityURN.ROAD, StandardEntityURN.HYDRANT)) {
            Road road = (Road) se;
            for (EntityID neighbour : road.getNeighbours()) {

                if (this.worldInfo.getEntity(neighbour) instanceof Building) {
                    Edge edgeToBuilding = road.getEdgeTo(neighbour);
                    Point2D startPoint = this.getMidPoint(edgeToBuilding);
                    Edge oppositeEdge = this.getOppositeEdge(road, edgeToBuilding);
                    if (oppositeEdge != null) {
                        Point2D endPoint = this.getMidPoint(oppositeEdge);
                        SEUGuideline guideline = new SEUGuideline(
                                road, startPoint, endPoint, true);
                        if (!this.guidelineList.contains(guideline)) {
                            this.guidelineList.add(guideline);
                            this.countedRoad.add(road.getID());
                            this.countedEntrance.add(road.getID());
                        }
                    }
                }
            }
        }

        List<EntityID> allRoadIDs = this.worldInfo.getEntityIDsOfType(
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT)
                .stream()
                .filter(id -> !this.countedRoad.contains(id))
                .sorted(new DistanceIDSorter(this.worldInfo, this.agentInfo.getID())).toList();
        List<StandardEntity> allRoad = allRoadIDs
                .stream()
                .map(id -> this.worldInfo.getEntity(id))
                .toList();

        StandardEntity posEntity = this.worldInfo.getEntity(this.agentInfo.getPosition());

        if (posEntity instanceof Road || posEntity instanceof Hydrant) {

            if (SEUConstants.DEBUG_GUIDELINE_TERMINAL_OUTPUT) {
                System.out.println("Current Position " + posEntity);
            }
            Road pos = (Road) posEntity;

            SEUGuideline guideline = new SEUGuideline(
                    this.getGuideline(pos), pos, false);

            if (!this.guidelineList.contains(guideline)) {
                this.guidelineList.add(guideline);
                this.countedRoad.add(pos.getID());
            }

            for (EntityID neighbour : pos.getNeighbours()) {
                if (this.worldInfo.getEntity(neighbour) instanceof Road
                        || this.worldInfo.getEntity(neighbour) instanceof Hydrant) {
                    Road road = (Road) this.worldInfo.getEntity(neighbour);
                    Edge edge = pos.getEdgeTo(neighbour);
                    Point2D startPoint = this.getMidPoint(edge);

                    Edge oppositeEdge = this.getOppositeEdge(road, edge, true);
                    if (oppositeEdge == null) {
                        oppositeEdge = this.getOppositeEdge(road, edge, false);
                    }

                    Point2D endPoint = this.getMidPoint(oppositeEdge);
                    SEUGuideline line = new SEUGuideline(
                            road, startPoint, endPoint, false);
                    if (!this.guidelineList.contains(line)) {
                        this.guidelineList.add(line);
                        this.countedRoad.add(road.getID());
                    }
                }
            }

            for (StandardEntity se : allRoad) {

                if (this.countedRoad.contains(se.getID())) {
                    continue;
                }

                this.pathPlanning.setFrom(pos.getID());
                this.pathPlanning.setDestination(se.getID());
                List<EntityID> path = this.pathPlanning.calc().getResult();

                if (path != null && path.size() > 2) {

                    for (int i = 1; i < path.size() - 1; ++i) {
                        StandardEntity entity = this.worldInfo.getEntity(path.get(i));

                        if (!(entity instanceof Road))
                            continue;
                        Road road = (Road) entity;

                        Area before = (Area) this.worldInfo.getEntity(path.get(i - 1));
                        Area after = (Area) this.worldInfo.getEntity(path.get(i + 1));

                        if (i > 2 && i < path.size() - 2) {

                            if (this.countedEntrance.contains(path.get(i - 1))) {
                                StandardEntity beforeBefore = this.worldInfo.getEntity(path.get(i - 2));
                                if (beforeBefore instanceof Road &&
                                        road.getEdgeTo(beforeBefore.getID()) != null) {
                                    before = (Area) beforeBefore;
                                }
                            }

                            if (this.countedEntrance.contains(path.get(i + 1))) {
                                StandardEntity afterAfter = this.worldInfo.getEntity(path.get(i + 2));
                                if (afterAfter instanceof Road &&
                                        road.getEdgeTo(afterAfter.getID()) != null) {
                                    after = (Area) afterAfter;
                                }
                            }
                        }

                        Edge edge1 = before.getEdgeTo(road.getID());
                        Edge edge2 = road.getEdgeTo(after.getID());
                        Point2D start = this.getMidPoint(edge1);
                        Point2D end = this.getMidPoint(edge2);
                        SEUGuideline line = new SEUGuideline(road, start, end, false);

                        if (!this.guidelineList.contains(line)) {
                            this.guidelineList.add(line);
                            this.countedRoad.add(entity.getID());
                        }
                    }
                }
            }
        } else if (posEntity instanceof Building building) {

            for (StandardEntity se : allRoad) {
                if (this.countedRoad.contains(se.getID())) {
                    continue;
                }

                this.pathPlanning.setFrom(building.getID());
                this.pathPlanning.setDestination(se.getID());
                List<EntityID> path = this.pathPlanning.calc().getResult();

                if (path != null && path.size() > 2) {

                    for (int i = 1; i < path.size() - 1; ++i) {
                        StandardEntity entity = this.worldInfo.getEntity(path.get(i));
                        if (!(entity instanceof Road) && !(entity instanceof Hydrant))
                            continue;
                        Road road = (Road) entity;

                        Area before = (Area) this.worldInfo.getEntity(path.get(i - 1));
                        Area after = (Area) this.worldInfo.getEntity(path.get(i + 1));

                        if (i > 2 && i < path.size() - 2) {
                            if (this.countedEntrance.contains(path.get(i - 1))) {
                                StandardEntity beforeBefore = this.worldInfo.getEntity(path.get(i - 2));
                                if (beforeBefore instanceof Road &&
                                        road.getEdgeTo(beforeBefore.getID()) != null) {
                                    before = (Area) beforeBefore;
                                }
                            }
                            if (this.countedEntrance.contains(path.get(i + 1))) {
                                StandardEntity afterAfter = this.worldInfo.getEntity(path.get(i + 2));
                                if (afterAfter instanceof Road &&
                                        road.getEdgeTo(afterAfter.getID()) != null) {
                                    after = (Area) afterAfter;
                                }
                            }
                        }

                        Edge edge1 = before.getEdgeTo(road.getID());
                        Edge edge2 = road.getEdgeTo(after.getID());
                        Point2D start = this.getMidPoint(edge1);
                        Point2D end = this.getMidPoint(edge2);
                        SEUGuideline line = new SEUGuideline(road, start, end, false);

                        if (!this.guidelineList.contains(line)) {
                            this.guidelineList.add(line);
                            this.countedRoad.add(entity.getID());
                        }
                    }
                }
            }
        }

        List<StandardEntity> remoteRoad = this.worldInfo.getEntityIDsOfType(
                StandardEntityURN.ROAD, StandardEntityURN.HYDRANT)
                .stream()
                .filter(se -> !countedRoad.contains(se))
                .map(id -> this.worldInfo.getEntity(id))
                .collect(Collectors.toList());

        for (StandardEntity se : remoteRoad) {
            Road road = (Road) se;
            Line2D createLine = this.getGuideline(road);
            if (createLine != null) {
                SEUGuideline line = new SEUGuideline(createLine, road, false);
                if (!this.guidelineList.contains(line)) {
                    this.guidelineList.add(line);
                }
            }
        }

        for (SEUGuideline guideline : this.guidelineList) {
            guidelineMap.put(guideline.getSelfID(), guideline.getGuideline());
        }

    }

    private Edge getOppositeEdge(Road road, Edge original) {
        return getOppositeEdge(road, original, true);
    }

    private Edge getOppositeEdge(Road road, Edge original, Boolean isPassable) {
        Point2D originalMid = this.getMidPoint(original);

        List<Edge> edges = road.getEdges()
                .stream()
                .filter(e -> !isPassable || e.isPassable())
                .filter(e -> !e.equals(original))
                .toList();

        if (!edges.isEmpty()) {
            Point2D roadCenter = new Point2D(road.getX(), road.getY());

            Vector2D standardDirection = new Vector2D(
                    roadCenter.getX() - originalMid.getX(),
                    roadCenter.getY() - originalMid.getY());

            Edge answerEdge = null;
            double minAngle = Double.MAX_VALUE;

            for (Edge e : edges) {
                Point2D mid = this.getMidPoint(e);
                Vector2D testDirection = new Vector2D(
                        mid.getX() - originalMid.getX(),
                        mid.getY() - originalMid.getY());
                double angle = GeometryTools2D.getAngleBetweenVectors(
                        standardDirection, testDirection);
                if (angle < minAngle) {
                    minAngle = angle;
                    answerEdge = e;
                }
            }
            return answerEdge;
        }
        return null;
    }

    private Point2D getMidPoint(Edge edge) {
        if (edge != null) {
            return edge.getLine().getPoint(0.5);
        }
        return null;
    }

    private Line2D getPassibleLongestLine(Road road, List<Edge> edges) {
        Point2D start = null;
        Point2D end = null;
        double max = Double.MIN_VALUE;
        for (Edge edge : edges) {
            Edge opposite = this.getOppositeEdge(road, edge);

            if (opposite == null) {
                continue;
            }
            Point2D p1 = this.getMidPoint(edge);
            Point2D p2 = this.getMidPoint(opposite);
            double dist = GeometryTools2D.getDistance(p1, p2);
            if (dist > max) {
                max = dist;
                start = p1;
                end = p2;
            }
        }
        if (start != null && end != null) {
            return new Line2D(start, end);
        }
        return null;
    }
}
