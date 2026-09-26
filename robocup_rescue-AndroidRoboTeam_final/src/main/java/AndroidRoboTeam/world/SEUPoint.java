package AndroidRoboTeam.world;

import rescuecore2.misc.geometry.Line2D;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SEUPoint {
    private List<SEUBlockade> realteBlockades = new ArrayList<>();;

    private Point underlyingPoint;

    private Line2D line;

    public SEUPoint(Point point, Line2D line, SEUBlockade... blockade) {
        this.setUnderlyingPoint(point);
        this.setLine(line);

        this.realteBlockades.addAll(Arrays.asList(blockade));
    }

    public List<SEUBlockade> getRelateBlockade() {
        return this.realteBlockades;
    }

    public void addSEUBlockade(SEUBlockade blockade) {
        this.realteBlockades.add(blockade);
    }

    public Point getUnderlyingPoint() {
        return underlyingPoint;
    }

    public void setUnderlyingPoint(Point underlyingPoint) {
        this.underlyingPoint = underlyingPoint;
    }

    public Line2D getLine() {
        return line;
    }

    public void setLine(Line2D line) {
        this.line = line;
    }
}
