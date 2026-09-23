package maps.gml.debug;

import maps.osm.OSMNode;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.misc.gui.ShapeDebugFrame;

import java.awt.Color;

/**
 * A ShapeInfo that knows how to draw OSMNodes for debugging purposes.
 */
public class OSMNodeShapeInfo extends ShapeDebugFrame.Point2DShapeInfo {
    private final OSMNode node;

    /**
     * Construct a new OSMNodeShapeInfo.
     * @param node   The node to draw.
     * @param name   The name of the shape.
     * @param colour The colour to draw with.
     * @param square Whether to draw a square or cross
     */
    public OSMNodeShapeInfo(OSMNode node, String name, Color colour, boolean square) {
        super(osmNodeToPoint(node), name, colour, square);
        this.node = node;
    }

    @Override
    public Object getObject() {
        return node;
    }

    private static Point2D osmNodeToPoint(OSMNode node) {
        return new Point2D(node.getLongitude(), node.getLatitude());
    }
}
