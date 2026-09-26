package AndroidRoboTeam.world;

import AndroidRoboTeam.module.algorithm.Ruler;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.geometry.Vector2D;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.AbstractEntity;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.io.*;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

public class Util {

    public static Polygon getPolygon(int[] apexes) {
        Polygon polygon = new Polygon();
        for (int i = 0; i < apexes.length; i += 2) {
            polygon.addPoint(apexes[i], apexes[i + 1]);
        }

        return polygon;
    }

    public static boolean hasIntersectLine(Polygon polygon, Line2D line) {
        List<Line2D> polyLines = getLines(polygon);
        if (polygon.contains(line.getOrigin().getX(), line.getOrigin().getY()) ||
                polygon.contains(line.getEndPoint().getX(), line.getEndPoint().getY())) {
            return true;
        }
        for (Line2D ln : polyLines) {
            Point2D intersectPoint = GeometryTools2D.getSegmentIntersectionPoint(line, ln);
            if (intersectPoint != null) {
                return true;
            }
        }
        return false;
    }

    public static List<Line2D> getLine2DOfPolygon(Polygon polygon) {
        List<Line2D> allLines = new LinkedList<>();
        int count = polygon.npoints;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            Point2D first = new Point2D(polygon.xpoints[i], polygon.ypoints[i]);
            Point2D second = new Point2D(polygon.xpoints[j], polygon.ypoints[j]);
            Line2D line = new Line2D(first, second);
            allLines.add(line);
        }
        return allLines;
    }

    public static Point2D getIntersection(Line2D line1, Line2D line2) {
        final double x1, y1, x2, y2, x3, y3, x4, y4;
        x1 = line1.getOrigin().getX();
        y1 = line1.getOrigin().getY();
        x2 = line1.getEndPoint().getX();
        y2 = line1.getEndPoint().getY();
        x3 = line2.getOrigin().getX();
        y3 = line2.getOrigin().getY();
        x4 = line2.getEndPoint().getX();
        y4 = line2.getEndPoint().getY();
        final double x = ((x2 - x1) * (x3 * y4 - x4 * y3) - (x4 - x3) * (x1 * y2 - x2 * y1))
                / ((x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4));
        final double y = ((y3 - y4) * (x1 * y2 - x2 * y1) - (y1 - y2) * (x3 * y4 - x4 * y3))
                / ((x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4));

        return new Point2D(x, y);
    }

    public static boolean isSegmentIntersecting(Line2D l1, Line2D l2) {
        final double x1, y1, x2, y2, x3, y3, x4, y4;
        x1 = l1.getOrigin().getX();
        y1 = l1.getOrigin().getY();
        x2 = l1.getEndPoint().getX();
        y2 = l1.getEndPoint().getY();
        x3 = l2.getOrigin().getX();
        y3 = l2.getOrigin().getY();
        x4 = l2.getEndPoint().getX();
        y4 = l2.getEndPoint().getY();

        if ((Math.max(x1, x2)) < (Math.min(x3, x4)) ||
                (Math.max(y1, y2)) < (Math.min(y3, y4)) ||
                (Math.max(x3, x4)) < (Math.min(x1, x2)) ||
                (Math.max(y3, y4)) < (Math.min(y1, y2))) {
            return false;
        }

        if ((((x1 - x3) * (y4 - y3) - (y1 - y3) * (x4 - x3)) *
                ((x2 - x3) * (y4 - y3) - (y2 - y3) * (x4 - x3))) > 0 ||
                (((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) *
                        ((x4 - x1) * (y2 - y1) - (y4 - y1) * (x2 - x1))) > 0) {
            return false;
        }
        return true;
    }

    public static List<Point2D> getSegmentIntersections(Set<SEULineOfSightPerception.SEURay> rays1,
            Set<SEULineOfSightPerception.SEURay> rays2) {
        ArrayList<Point2D> result = new ArrayList<>();
        for (SEULineOfSightPerception.SEURay ray1 : rays1) {
            for (SEULineOfSightPerception.SEURay ray2 : rays2) {
                if (isSegmentIntersecting(ray1.getRay(), ray2.getRay())) {
                    Point2D intersection = getIntersection(ray1.getRay(), ray2.getRay());
                    result.add(intersection);
                }
            }
        }
        return result;
    }

    public static Line2D improveLine(Line2D line, double size) {
        double molecular = line.getEndPoint().getY() - line.getOrigin().getY();
        double denominaor = line.getEndPoint().getX() - line.getOrigin().getX();
        double slope;
        if (denominaor != 0) {
            slope = molecular / denominaor;
        } else {
            if (molecular > 0)
                slope = Double.MAX_VALUE;
            else
                slope = -Double.MAX_VALUE;
        }

        double newPointX, newPointY;
        double theta = Math.atan(slope);
        if (denominaor > 0) {
            newPointX = line.getEndPoint().getX() + size * Math.abs(Math.cos(theta));
        } else {
            newPointX = line.getEndPoint().getX() - size * Math.abs(Math.cos(theta));
        }
        if (molecular > 0) {
            newPointY = line.getEndPoint().getY() + Math.abs(Math.sin(theta)) * size;
        } else {
            newPointY = line.getEndPoint().getY() - Math.abs(Math.sin(theta)) * size;
        }

        Point2D newEndPoint = new Point2D(newPointX, newPointY);

        return new Line2D(line.getOrigin(), newEndPoint);
    }

    public static Line2D improveLineBothSides(Line2D line, double size) {
        double molecular = line.getEndPoint().getY() - line.getOrigin().getY();
        double denominaor = line.getEndPoint().getX() - line.getOrigin().getX();
        double slope;
        if (denominaor != 0) {
            slope = molecular / denominaor;
        } else {
            if (molecular > 0)
                slope = Double.MAX_VALUE;
            else
                slope = -Double.MAX_VALUE;
        }

        double newOriginPointX, newOriginPointY, newEndPointX, newEndPointY;
        double theta = Math.atan(slope);
        if (denominaor > 0) {
            newOriginPointX = line.getOrigin().getX() - size * Math.abs(Math.cos(theta));
            newEndPointX = line.getEndPoint().getX() + size * Math.abs(Math.cos(theta));
        } else {
            newOriginPointX = line.getOrigin().getX() + size * Math.abs(Math.cos(theta));
            newEndPointX = line.getEndPoint().getX() - size * Math.abs(Math.cos(theta));
        }
        if (molecular > 0) {
            newOriginPointY = line.getOrigin().getY() - size * Math.abs(Math.sin(theta));
            newEndPointY = line.getEndPoint().getY() + size * Math.abs(Math.sin(theta));
        } else {
            newOriginPointY = line.getOrigin().getY() + size * Math.abs(Math.sin(theta));
            newEndPointY = line.getEndPoint().getY() - size * Math.abs(Math.sin(theta));
        }

        Point2D newOriginPoint = new Point2D(newOriginPointX, newOriginPointY);
        Point2D newEndPoint = new Point2D(newEndPointX, newEndPointY);

        return new Line2D(newOriginPoint, newEndPoint);
    }

    public static Line2D clipLine(Line2D line, double size) {
        double length = Ruler.getLength(line);
        return improveLine(line, size - length);
    }

    public static Set<Point2D> getIntersections(Polygon poly, Line2D line) {
        Set<Point2D> result = new HashSet<>();
        List<Line2D> polyLine = getLine2DOfPolygon(poly);
        Point2D point = null;
        for (Line2D next : polyLine) {
            point = GeometryTools2D.getSegmentIntersectionPoint(next, line);
            if (point != null)
                result.add(point);
        }

        return result;
    }

    public static Polygon scaleBySize(Polygon polygon, double size) {
        Polygon result = new Polygon();
        Point2D center = new Point2D(polygon.getBounds().getCenterX(), polygon.getBounds().getCenterY());
        List<Line2D> polyLines = getLines(polygon);

        for (Line2D line2D : polyLines) {

            Point2D p1 = closestPoint(line2D, center);
            Line2D ln = new Line2D(center, p1);
            ln = improveLine(ln, size);
            Point2D p2 = ln.getEndPoint();
            double dx = p2.getX() - p1.getX();
            double dy = p2.getY() - p1.getY();

            Point2D origin = new Point2D(
                    line2D.getOrigin().getX() + dx,
                    line2D.getOrigin().getY() + dy);
            result.addPoint((int) origin.getX(), (int) origin.getY());

            Point2D end = new Point2D(
                    line2D.getEndPoint().getX() + dx,
                    line2D.getEndPoint().getY() + dy);
            result.addPoint((int) end.getX(), (int) end.getY());
        }
        return result;

    }

    public static List<Line2D> getLines(Polygon polygon) {
        List<Line2D> lines = new ArrayList<Line2D>();
        int count = polygon.npoints;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            Point2D p1 = new Point2D(polygon.xpoints[i], polygon.ypoints[i]);
            Point2D p2 = new Point2D(polygon.xpoints[j], polygon.ypoints[j]);
            Line2D line = new Line2D(p1, p2);
            lines.add(line);
        }
        return lines;
    }

    public static Point2D closestPoint(Line2D line, Point2D point) {
        return GeometryTools2D.getClosestPoint(line, point);
    }

    public static double getAngle(Vector2D v1, Vector2D v2) {
        double flag = (v1.getX() * v2.getY()) - (v1.getY() * v2.getX());
        double angle = Math
                .acos(((v1.getX() * v2.getX()) + (v1.getY() * v2.getY())) / (v1.getLength() * v2.getLength()));
        if (flag > 0) {
            return angle;
        }
        if (flag < 0) {
            return -1 * angle;
        }
        return 0.0D;
    }

    public static double getAngle(Line2D l1, Line2D l2) {
        Vector2D v1 = l1.getDirection();
        Vector2D v2 = l2.getDirection();
        double temp = (v1.getX() * v2.getX() + v1.getY() * v2.getY()) / (v1.getLength() * v2.getLength());
        if (temp > 0) {
            return Math.acos(temp);
        }
        if (temp < 0) {
            return Math.acos(-1 * temp);
        }
        return 0.0D;
    }

    public static math.geom2d.line.Line2D convertLine(Line2D line2D) {
        double x1 = line2D.getOrigin().getX();
        double y1 = line2D.getOrigin().getY();
        double x2 = line2D.getEndPoint().getX();
        double y2 = line2D.getEndPoint().getY();
        return new math.geom2d.line.Line2D(x1, y1, x2, y2);
    }

    public static boolean isCollinear(math.geom2d.line.Line2D l1, math.geom2d.line.Line2D l2, double threshold) {
        if (!isCollinear(l1.getVector(), l2.getVector(), threshold)) {
            return false;
        } else {
            double dx = l1.getX2() - l1.getX1();
            double dy = l1.getY2() - l1.getY1();
            if (Math.abs(dx) > Math.abs(dy)) {
                return Math.abs((l2.getX1() - l1.getX1()) * dy / dx + l1.getY1()
                        - l2.getY1()) <= SEUConstants.COLLINEAR_THRESHOLD;
            } else {
                return Math.abs((l2.getY1() - l1.getY1()) * dx / dy + l1.getX1()
                        - l2.getX1()) <= SEUConstants.COLLINEAR_THRESHOLD;
            }
        }
    }

    public static boolean containsEach(Collection collection1, Collection collection2) {
        for (Object object : collection1) {
            if (collection2.contains(object)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPassable(Polygon polygon, Polygon polygon1, int agentPassingThreshold) {

        int count = polygon1.npoints;
        int j;
        double tempDistance;
        boolean isPassable = false;
        for (int i = 0; i < count; i++) {
            j = (i + 1) % count;
            Point2D startPoint = new Point2D(polygon1.xpoints[i], polygon1.ypoints[i]);
            Point2D endPoint = new Point2D(polygon1.xpoints[j], polygon1.ypoints[j]);
            if (startPoint.equals(endPoint)) {
                continue;
            }
            Line2D poly2Line = new Line2D(startPoint, endPoint);
            tempDistance = Ruler.getDistance(poly2Line, polygon);
            if (tempDistance < agentPassingThreshold) {
                isPassable = true;
                break;
            }
        }
        return isPassable;

    }

    public static boolean isCollinear(math.geom2d.Vector2D v1, math.geom2d.Vector2D v2, double threshold) {
        v1 = v1.getNormalizedVector();
        v2 = v2.getNormalizedVector();
        return Math.abs(v1.getX() * v2.getY() - v1.getY() * v2.getX()) < threshold;
    }

    public static class DistanceComparator implements Comparator<Pair<Point2D, Point2D>> {
        private Point2D reference;

        public DistanceComparator(Point2D reference) {
            this.reference = reference;
        }

        @Override
        public int compare(Pair<Point2D, Point2D> a, Pair<Point2D, Point2D> b) {
            double d1 = Ruler.getDistance(reference, a.first());
            double d2 = Ruler.getDistance(reference, b.first());
            return Double.compare(d1, d2);
        }
    }

    public static class LengthComparator implements Comparator<Line2D> {

        public LengthComparator() {
        }

        @Override
        public int compare(Line2D a, Line2D b) {
            double l1 = Ruler.getLength(a);
            double l2 = Ruler.getLength(b);
            return Double.compare(l1, l2);
        }
    }

    public static class AngleComparator implements Comparator<Pair<SEUEdge, Line2D>> {
        private Line2D reference;

        public AngleComparator(Line2D reference) {
            this.reference = reference;
        }

        @Override
        public int compare(Pair<SEUEdge, Line2D> a, Pair<SEUEdge, Line2D> b) {
            double angle1 = Math.abs(getAngle(reference, a.second()));
            double angle2 = Math.abs(getAngle(reference, b.second()));
            return Double.compare(angle1, angle2);
        }
    }

    public static int getdistance(Pair<Integer, Integer> position1, Pair<Integer, Integer> position2) {
        float x1 = position1.first();
        float y1 = position1.second();
        float x2 = position2.first();
        float y2 = position2.second();
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

}
