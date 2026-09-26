package AndroidRoboTeam.module.algorithm;

import math.geom2d.Point2D;
import math.geom2d.line.Line2D;
import math.geom2d.polygon.Polygon2D;
import math.geom2d.polygon.convhull.JarvisMarch2D;

import java.awt.*;
import java.util.List;
import java.util.*;

public class CompositeConvexHull {

    private List<Point> points;

    private Set<Point> addedPoints;

    private Set<Point> removedPoints;

    private Polygon convexHullPolygon;

    private JarvisMarch2D jarvisMarchCalculator;

    @SuppressWarnings("unused")
    private Long guid;

    public CompositeConvexHull() {
        points = new ArrayList<Point>();
        jarvisMarchCalculator = new JarvisMarch2D();

        addedPoints = new HashSet<Point>();
        removedPoints = new HashSet<Point>();
    }

    @SuppressWarnings("unused")

    public Long getGuid() {
        return guid;
    }

    public Polygon getConvexPolygon() {
        if (isDataUpdated()) {
            convexHullPolygon = updateConvexPolygon();
        }
        return convexHullPolygon;
    }

    public Polygon updateConvexPolygon() {
        Polygon returnValue;
        if (convexHullPolygon != null) {
            Set<Point> onVerticesRemovedPoints = new HashSet<Point>();
            for (Point removedPoint : removedPoints) {
                for (int i = 0; i < convexHullPolygon.npoints; i++) {
                    if (removedPoint.x == convexHullPolygon.xpoints[i]
                            && removedPoint.y == convexHullPolygon.ypoints[i]) {
                        onVerticesRemovedPoints.add(removedPoint);
                        break;
                    }
                }
            }

            removedPoints.removeAll(onVerticesRemovedPoints);
            points.removeAll(removedPoints);
            removedPoints = onVerticesRemovedPoints;

            Set<Point> onVerticesAddedPoints = new HashSet<Point>();
            Set<Point> outerAddedPoints = new HashSet<Point>();
            Set<Point> innerAddedPoints = new HashSet<Point>();

            for (Point addedPoint : addedPoints) {
                for (int i = 0; i < convexHullPolygon.npoints; i++) {
                    if (addedPoint.x == convexHullPolygon.xpoints[i]
                            && addedPoint.y == convexHullPolygon.ypoints[i]) {
                        onVerticesAddedPoints.add(addedPoint);
                    } else if (convexHullPolygon.contains(addedPoint)) {
                        innerAddedPoints.add(addedPoint);
                    } else {
                        outerAddedPoints.add(addedPoint);
                    }
                }
            }

            addedPoints.removeAll(innerAddedPoints);
            addedPoints.removeAll(onVerticesAddedPoints);

            points.addAll(innerAddedPoints);
            points.addAll(onVerticesAddedPoints);

            addedPoints = outerAddedPoints;
        }

        if (removedPoints.isEmpty() && addedPoints.isEmpty()) {
            returnValue = convexHullPolygon;
        } else if (convexHullPolygon != null && removedPoints.isEmpty() && !addedPoints.isEmpty()) {
            addPointsToConvexHull(addedPoints);
            addedPoints.clear();
            returnValue = convexHullPolygon;
        } else {
            points.removeAll(removedPoints);
            points.addAll(addedPoints);

            addedPoints.clear();
            removedPoints.clear();

            List<Point2D> point2ds = convertPoints(points);
            Polygon2D polygon2d = jarvisMarchCalculator.convexHull(point2ds);
            returnValue = convertPolygon2d(polygon2d);
        }

        return returnValue;
    }

    private void addPointsToConvexHull(Set<Point> addedPoints) {
        for (Point addedPoint : addedPoints) {
            Point centroid = new Point();
            for (int i = 0; i < convexHullPolygon.npoints; i++) {
                centroid.x += convexHullPolygon.xpoints[i];
                centroid.y += convexHullPolygon.ypoints[i];
            }
            centroid.x /= convexHullPolygon.npoints;
            centroid.y /= convexHullPolygon.npoints;

            Line2D anchor = new Line2D(centroid.x, centroid.y, addedPoint.x, addedPoint.y);
            double anchorAngle = anchor.getHorizontalAngle();

            double minAngle = 2 * Math.PI;
            double maxAngle = -2 * Math.PI;
            int minAngleVertexIndex = -1;
            int maxAngleVertexIndex = -1;
            for (int i = 0; i < convexHullPolygon.npoints; i++) {
                double angle = anchorAngle - new Line2D(convexHullPolygon.xpoints[i], convexHullPolygon.ypoints[i],
                        addedPoint.x, addedPoint.y).getHorizontalAngle();
                if (angle > 2 * Math.PI) {
                    angle -= (2 * Math.PI);
                }
                if (angle < 0) {
                    angle += (2 * Math.PI);
                }

                if (angle > Math.PI / 2 && angle < 1.5 * Math.PI) {
                    angle = angle - Math.PI;
                } else if (angle >= 1.5 * Math.PI && angle <= 2 * Math.PI) {
                    angle = angle - 2 * Math.PI;
                }

                if (angle > maxAngle) {
                    maxAngle = angle;
                    maxAngleVertexIndex = i;
                }
                if (angle < minAngle) {
                    minAngle = angle;
                    minAngleVertexIndex = i;
                }
            }

            int[] xpoints = new int[convexHullPolygon.npoints + 2
                    - Math.abs(maxAngleVertexIndex - minAngleVertexIndex)];
            int[] ypoints = new int[convexHullPolygon.npoints + 2
                    - Math.abs(maxAngleVertexIndex - minAngleVertexIndex)];

            int newPointsIndex = 0;

            points.clear();

            for (int i = 0; i <= Math.min(minAngleVertexIndex, maxAngleVertexIndex); i++) {
                xpoints[newPointsIndex] = convexHullPolygon.xpoints[i];
                ypoints[newPointsIndex] = convexHullPolygon.ypoints[i];
                points.add(new Point(xpoints[newPointsIndex], ypoints[newPointsIndex]));
                newPointsIndex++;
            }

            xpoints[newPointsIndex] = addedPoint.x;
            ypoints[newPointsIndex] = addedPoint.y;
            points.add(new Point(xpoints[newPointsIndex], ypoints[newPointsIndex]));
            newPointsIndex++;

            for (int i = Math.max(minAngleVertexIndex, maxAngleVertexIndex); i < convexHullPolygon.npoints; i++) {
                xpoints[newPointsIndex] = convexHullPolygon.xpoints[i];
                ypoints[newPointsIndex] = convexHullPolygon.ypoints[i];
                points.add(new Point(xpoints[newPointsIndex], ypoints[newPointsIndex]));
                newPointsIndex++;
            }

            convexHullPolygon = new Polygon(xpoints, ypoints, newPointsIndex);
        }
    }

    public void addPoint(int x, int y) {
        addPoint(new Point(x, y));
    }

    public void addPoint(Point point) {
        if (removedPoints.contains(point)) {
            removedPoints.remove(point);
        } else {
            addedPoints.add(point);
        }
    }

    public void removePoint(int x, int y) {
        removePoint(new Point(x, y));
    }

    public void removePoint(Point point) {
        if (addedPoints.contains(point)) {
            addedPoints.remove(point);
        } else {
            removedPoints.add(point);
        }
    }

    private boolean isDataUpdated() {
        return !addedPoints.isEmpty() || !removedPoints.isEmpty();
    }

    public void updatePoints(Collection<Point> addedPoints, Collection<Point> removedPoints) {
        if (addedPoints != null) {
            for (Point addedPoint : addedPoints) {
                addPoint(addedPoint);
            }
        }

        if (removedPoints != null) {
            for (Point removedPoint : removedPoints) {
                removePoint(removedPoint);
            }
        }
    }

    private static List<Point2D> convertPoints(List<Point> points) {
        List<Point2D> point2ds = new ArrayList<Point2D>();
        for (Point point : points) {
            point2ds.add(new Point2D(point.x, point.y));
        }
        return point2ds;
    }

    private static Polygon convertPolygon2d(Polygon2D polygon2d) {
        Collection<Point2D> vertices = polygon2d.getVertices();
        int[] xPoints = new int[vertices.size()];
        int[] yPoints = new int[vertices.size()];
        int i = 0;
        for (Point2D point2d : vertices) {
            xPoints[i] = (int) point2d.x;
            yPoints[i] = (int) point2d.y;
            i++;
        }
        return new Polygon(xPoints, yPoints, vertices.size());
    }

}
