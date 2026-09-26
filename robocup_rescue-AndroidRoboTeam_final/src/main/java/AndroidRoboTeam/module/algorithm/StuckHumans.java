package AndroidRoboTeam.module.algorithm;

import AndroidRoboTeam.world.SEUConstants;

import AndroidRoboTeam.extaction.DefaultExtActionMove;

import AndroidRoboTeam.module.complex.SEUPF.GuidelineCreator;
import AndroidRoboTeam.world.Util;
import AndroidRoboTeam.world.SEUWorldService;
import AndroidRoboTeam.world.SEUEdge;
import AndroidRoboTeam.world.SEULineOfSightPerception;
import AndroidRoboTeam.world.SEURoad;
import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.info.WorldInfo;

import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.util.List;
import java.util.*;

public class StuckHumans {

    private SEUWorldService world;

    private SEULineOfSightPerception lineOfSightPerception;

    private WorldInfo worldInfo;

    public StuckHumans(SEUWorldService world, WorldInfo worldInfo) {
        this.world = world;
        this.worldInfo = worldInfo;
        this.lineOfSightPerception = new SEULineOfSightPerception(world);
    }

    private Point2D getMidPoint(Edge edge) {
        if (edge != null) {
            double midX = (double) (edge.getStartX() + edge.getEndX()) / 2;
            double midY = (double) (edge.getStartY() + edge.getEndY()) / 2;
            return new Point2D(midX, midY);
        }
        return null;
    }

    private double getDistance(double fromX, double fromY, double toX, double toY) {
        double dx = fromX - toX;
        double dy = fromY - toY;
        return Math.hypot(dx, dy);
    }

    public Action calc(List<EntityID> path) {
        if (path == null || path.size() < 2) {
            return null;
        }

        List<EntityID> selfAndNeighborBlockades = getSelfAndNeighborBlockades();
        EntityID nearestBlockade = getNearestBlockade();
        List<StandardEntity> nearBlockades = getBlockadesInRange(
                nearestBlockade, selfAndNeighborBlockades, SEUConstants.AGENT_PASSING_THRESHOLD);
        if (nearBlockades.isEmpty()) {
            return null;
        }
        CompositeConvexHull blockadesConvexHull = getConvexByBlockades(nearBlockades);

        StandardEntity selfPosition = world.getSelfPosition();
        Pair<Integer, Integer> selfLocation = world.getSelfLocation();
        Point2D locationPoint = new Point2D(selfLocation.first(), selfLocation.second());
        Point2D openPartCenter = null;
        Point2D edgeStart = null;
        Point2D edgeEnd = null;
        SEUEdge targetEdge = null;

        if (selfPosition instanceof Road) {
            SEURoad SEURoad = world.getSEURoad(selfPosition);
            for (SEUEdge SEUEdge : SEURoad.getSEUEdgesTo(path.get(1))) {
                if (!SEUEdge.isBlocked()) {
                    openPartCenter = SEUEdge.getOpenPartCenter();
                    edgeStart = SEUEdge.getStart();
                    edgeEnd = SEUEdge.getEnd();
                    targetEdge = SEUEdge;
                    break;
                }
            }
        }
        if (selfPosition instanceof Building sd) {
            for (EntityID ne : sd.getNeighbours()) {
                StandardEntity neigbor = world.getEntity(ne);
                if (neigbor instanceof Road) {
                    SEURoad SEURoad = world.getSEURoad(neigbor);
                    for (SEUEdge SEUEdge : SEURoad.getSEUEdgesTo(sd.getID())) {
                        if (!SEUEdge.isBlocked()) {
                            openPartCenter = SEUEdge.getOpenPartCenter();
                            edgeStart = SEUEdge.getStart();
                            edgeEnd = SEUEdge.getEnd();
                            targetEdge = SEUEdge;
                            break;
                        }
                    }
                }
            }
        }
        if (openPartCenter == null) {
            return null;
        }

        double x_dis = 0, y_dist = 0;
        if (edgeStart != null) {
            x_dis = (edgeEnd.getX() - edgeStart.getX()) / 8;
            y_dist = (edgeEnd.getY() - edgeStart.getY()) / 8;
        }

        Line2D guideLine = new Line2D(locationPoint, openPartCenter);
        Point2D target = null;
        Set<Set<SEULineOfSightPerception.SEURay>> raysNotHits = new HashSet<>();
        Set<SEULineOfSightPerception.SEURay> raysNotHit1 = new HashSet<>();
        Collection<SEURoad> targetValidRoads = new HashSet<>();

        if (!Util.hasIntersectLine(blockadesConvexHull.getConvexPolygon(), guideLine)) {

            target = Util.clipLine(guideLine, world.getConfig().maxRayDistance).getEndPoint();
            if (target.getX() > world.getMaxX() || target.getX() < 0
                    || target.getY() > world.getMaxY() || target.getY() < 0) {
                target = Util.improveLine(guideLine, SEUConstants.AGENT_SIZE).getEndPoint();
            }
        } else {
            SEURoad SEURoad, oppositePassableEdgeRoad = null;

            if (selfPosition instanceof Road) {
                SEURoad = world.getSEURoad(selfPosition);
                targetValidRoads.add(SEURoad);
                oppositePassableEdgeRoad = SEURoad.getOppositePassableEdgeRoad(targetEdge);
            }
            if (selfPosition instanceof Building) {
                Building sd = (Building) selfPosition;
                for (EntityID ne : sd.getNeighbours()) {
                    StandardEntity neigbor = world.getEntity(ne);
                    if (neigbor instanceof Road) {
                        SEURoad = world.getSEURoad(neigbor);
                        for (SEUEdge SEUEdge : SEURoad.getSEUEdgesTo(sd.getID())) {
                            if (!SEUEdge.isBlocked()) {
                                targetValidRoads.add(SEURoad);
                                break;
                            }
                        }
                    }
                }
            }
            if (oppositePassableEdgeRoad != null) {
                targetValidRoads.add(oppositePassableEdgeRoad);
            }

            int distance = 30000000;

            double x_dis_2 = 0, y_dis_2 = 0;
            raysNotHit1 = lineOfSightPerception.findRaysNotHit(locationPoint, nearBlockades, distance);
            Set<SEULineOfSightPerception.SEURay> raysNotHit2 = lineOfSightPerception.findRaysNotHit(openPartCenter,
                    nearBlockades, distance);

            for (int i = 0; i <= 8; i++) {
                Point2D now_point = new Point2D(
                        edgeStart.getX() + x_dis * i,
                        edgeStart.getY() + y_dist * i);
                Set<SEULineOfSightPerception.SEURay> raysNotHit3 = lineOfSightPerception.findRaysNotHit(now_point,
                        nearBlockades, distance);
                raysNotHits.add(raysNotHit3);
            }
            raysNotHits.add(raysNotHit2);

            for (Set<SEULineOfSightPerception.SEURay> next : raysNotHits) {
                List<Point2D> validIntersections = getValidIntersections(raysNotHit1, next, targetValidRoads);
                if (!validIntersections.isEmpty()) {
                    validIntersections.sort(new DistanceComparator(locationPoint));
                    target = Util.improveLine(
                            new Line2D(locationPoint, validIntersections.get(0)),
                            SEUConstants.AGENT_PASSING_THRESHOLD).getEndPoint();
                }
            }
        }

        return moveToPoint(target);
    }

    private List<Point2D> getValidIntersections(
            Set<SEULineOfSightPerception.SEURay> a,
            Set<SEULineOfSightPerception.SEURay> b,
            Collection<SEURoad> targetValidRoads) {
        List<Point2D> intersections = Util.getSegmentIntersections(a, b);
        if (!intersections.isEmpty()) {
            filterIntersectionsNotInRoads(intersections, targetValidRoads);
        }
        return intersections;
    }

    private void filterIntersectionsNotInRoads(Collection<Point2D> points, Collection<SEURoad> roads) {
        Collection<Point2D> toRemove = new HashSet<>();
        for (Point2D point : points) {
            boolean contain = false;
            for (SEURoad road : roads) {
                Polygon polygon = road.getPolygon();
                if (polygon.contains(point.getX(), point.getY())) {
                    contain = true;
                    break;
                }
            }
            if (!contain) {
                toRemove.add(point);
            }
        }
        points.removeAll(toRemove);
    }

    private Action moveToPoint(Point2D target) {
        if (target == null) {
            return null;
        }
        List<EntityID> path = new ArrayList<>();
        path.add(world.getSelfPosition().getID());
        return new ActionMove(path, (int) target.getX(), (int) target.getY());
    }

    private CompositeConvexHull getConvexByBlockades(List<StandardEntity> blockades) {
        CompositeConvexHull convexHull = new CompositeConvexHull();
        for (StandardEntity entity : blockades) {
            Blockade blockade;
            if (entity instanceof Blockade) {
                blockade = (Blockade) entity;
                for (int i = 0; i < blockade.getApexes().length; i += 2) {
                    convexHull.addPoint(blockade.getApexes()[i], blockade.getApexes()[i + 1]);
                }
            }
        }
        return convexHull;
    }

    private List<EntityID> getSelfAndNeighborBlockades() {
        StandardEntity selfPosition = world.getSelfPosition();
        List<EntityID> blockadeIDs = new ArrayList<>();

        if (selfPosition instanceof Road) {
            Road road = (Road) selfPosition;
            for (StandardEntity next : world.getEntities(road.getNeighbours())) {
                if (next instanceof Road) {
                    Road neighbour = (Road) next;
                    if (neighbour.isBlockadesDefined()) {
                        blockadeIDs.addAll(neighbour.getBlockades());
                    }
                }
            }
            blockadeIDs.addAll(road.getBlockades());
            return blockadeIDs;
        }
        if (selfPosition instanceof Building) {
            Building building = (Building) selfPosition;
            for (StandardEntity next : world.getEntities(building.getNeighbours())) {
                if (next instanceof Road) {
                    Road neighbour = (Road) next;
                    if (neighbour.isBlockadesDefined()) {
                        blockadeIDs.addAll(neighbour.getBlockades());
                    }
                }
            }
            return blockadeIDs;
        }
        return null;
    }

    private EntityID getNearestBlockade() {
        List<EntityID> selfAndNeighborBlockades = getSelfAndNeighborBlockades();
        EntityID nearest = null;
        if (selfAndNeighborBlockades != null) {
            nearest = this.getNearest(world, selfAndNeighborBlockades, world.getSelfHuman().getID());
        }
        return nearest;
    }

    public EntityID getNearest(SEUWorldService world, List<EntityID> locations, EntityID start) {
        EntityID result = null;
        double minDistance = Double.MAX_VALUE;
        for (EntityID next : locations) {
            double distance = world.getDistance(start, next);
            if (distance < minDistance) {
                minDistance = distance;
                result = next;
            }
        }
        return result;
    }

    private List<StandardEntity> getBlockadesInRange(
            EntityID entity, List<EntityID> entities, int range) {
        List<StandardEntity> inRangeBlockades = new ArrayList<>();
        if (entities == null || entities.isEmpty()) {
            return inRangeBlockades;
        }
        Blockade anchorBlockade = (Blockade) world.getEntity(entity);
        for (EntityID blockadeID : entities) {
            StandardEntity standardEntity = world.getEntity(blockadeID);
            if (!(standardEntity instanceof Blockade)) {
                continue;
            }
            Blockade blockade = (Blockade) standardEntity;
            double dist = Ruler.getDistance(
                    Util.getPolygon(blockade.getApexes()),
                    Util.getPolygon(anchorBlockade.getApexes()));
            if (dist < range) {
                inRangeBlockades.add(blockade);
            }
        }
        return inRangeBlockades;
    }

    private static class DistanceComparator implements Comparator<Point2D> {
        private Point2D reference;

        public DistanceComparator(Point2D reference) {
            this.reference = reference;
        }

        @Override
        public int compare(Point2D a, Point2D b) {
            int d1 = (int) Ruler.getDistance(reference, a);
            int d2 = (int) Ruler.getDistance(reference, b);
            return d1 - d2;
        }
    }
}
