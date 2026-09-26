package AndroidRoboTeam.world;

import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.Road;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.util.HashSet;
import java.util.Set;

public class SEUEdge {

    private Edge underlyingEdge;

    private Pair<EntityID, EntityID> neighbours = null;

    private Point2D start, end, openPartStart, openPartEnd, middlePoint;

    private Line2D line, openPartLine;

    private boolean isBlocked = false, isPassable;

    private SEUWorldService worldService;

    public SEUEdge(Point2D start, Point2D end, boolean passable) {
        this.underlyingEdge = null;

        this.start = start;
        this.end = end;

        this.isPassable = passable;

        this.middlePoint = new Point2D((start.getX() + end.getX()) / 2.0, (start.getY() + end.getY()) / 2.0);

        this.openPartStart = start;
        this.openPartEnd = end;
        this.line = new Line2D(start, end);
        this.openPartLine = new Line2D(start, end);
    }

    public SEUEdge(SEUWorldService worldService, Edge underlyingEdge, EntityID owner) {
        this.underlyingEdge = underlyingEdge;
        this.neighbours = new Pair<EntityID, EntityID>(underlyingEdge.getNeighbour(), owner);

        this.isPassable = underlyingEdge.isPassable();

        this.start = underlyingEdge.getStart();
        this.end = underlyingEdge.getEnd();

        this.middlePoint = new Point2D((start.getX() + end.getX()) / 2.0, (start.getY() + end.getY()) / 2.0);

        this.openPartStart = underlyingEdge.getStart();
        this.openPartEnd = underlyingEdge.getEnd();

        this.line = underlyingEdge.getLine();
        this.openPartLine = underlyingEdge.getLine();

        this.worldService = worldService;
    }

    public boolean isPassable() {
        return this.isPassable;
    }

    public boolean isBlocked() {
        return this.isBlocked;
    }

    public void setBlocked(boolean blocked) {
        this.isBlocked = blocked;
    }

    public Line2D getLine() {
        return this.line;
    }

    public Line2D getOpenPart() {
        return this.openPartLine;
    }

    public void setOpenPart(Line2D openPart) {
        if (openPart == null) {
            this.openPartLine = null;
            this.openPartStart = null;
            this.openPartEnd = null;
        } else {
            this.openPartLine = openPart;
            this.openPartStart = openPart.getOrigin();
            this.openPartEnd = openPart.getEndPoint();
        }
    }

    public Point2D getStart() {
        return this.start;
    }

    public Point2D getEnd() {
        return this.end;
    }

    public Point2D getMiddlePoint() {
        return this.middlePoint;
    }

    public Point2D getOpenPartCenter() {
        double x = (openPartStart.getX() + openPartEnd.getX()) / 2;
        double y = (openPartStart.getY() + openPartEnd.getY()) / 2;
        return new Point2D(x, y);
    }

    public Pair<EntityID, EntityID> getNeighbours() {
        return this.neighbours;
    }

    public SEUEdge getOtherSideEdge() {
        Area neighbour = (Area) worldService.getEntity(getNeighbours().second());
        if (neighbour instanceof Road) {
            SEURoad roadNeighbour = worldService.getSEURoad(neighbour.getID());
            return roadNeighbour.getSEUEdgeInPoint(getMiddlePoint());
        }
        return null;
    }
}
