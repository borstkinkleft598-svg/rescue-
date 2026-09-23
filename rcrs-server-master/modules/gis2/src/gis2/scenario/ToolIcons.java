package gis2.scenario;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static gis2.scenario.EntityColours.AMBULANCE_CENTRE_COLOUR;
import static gis2.scenario.EntityColours.AMBULANCE_TEAM_COLOUR;
import static gis2.scenario.EntityColours.CIVILIAN_COLOUR;
import static gis2.scenario.EntityColours.FIRE_BRIGADE_COLOUR;
import static gis2.scenario.EntityColours.FIRE_COLOUR;
import static gis2.scenario.EntityColours.FIRE_STATION_COLOUR;
import static gis2.scenario.EntityColours.GAS_STATION_COLOUR;
import static gis2.scenario.EntityColours.HYDRANT_COLOUR;
import static gis2.scenario.EntityColours.POLICE_FORCE_COLOUR;
import static gis2.scenario.EntityColours.POLICE_OFFICE_COLOUR;
import static gis2.scenario.EntityColours.REFUGE_COLOUR;

public class ToolIcons {

    private static final int ICON_SIZE = 16;
    private static final Color HUMAN_STROKE_COLOUR = new Color(160, 160, 160);
    private static final Color AREA_STROKE_COLOUR  = Color.BLACK;
    private static final Color REMOVE_MARK_COLOUR  = Color.BLACK;

    public static final Icon PLACE_CIVILIAN          = createPlaceCivilianIcon();
    public static final Icon PLACE_FIRE_BRIGADE      = createPlaceFireBrigadeIcon();
    public static final Icon PLACE_AMBULANCE_TEAM    = createPlaceAmbulanceTeamIcon();
    public static final Icon PLACE_POLICE_FORCE      = createPlacePoliceForceIcon();
    public static final Icon PLACE_FIRE_STATION      = createPlaceFireStationIcon();
    public static final Icon PLACE_AMBULANCE_CENTRE  = createPlaceAmbulanceCentreIcon();
    public static final Icon PLACE_POLICE_OFFICE     = createPlacePoliceOfficeIcon();
    public static final Icon PLACE_REFUGE            = createPlaceRefugeIcon();
    public static final Icon PLACE_GAS_STATION       = createPlaceGasStationIcon();
    public static final Icon PLACE_HYDRANT           = createPlaceHydrantIcon();
    public static final Icon PLACE_FIRE              = createPlaceFireIcon();

    public static final Icon REMOVE_CIVILIAN         = createRemoveCivilianIcon();
    public static final Icon REMOVE_FIRE_BRIGADE     = createRemoveFireBrigadeIcon();
    public static final Icon REMOVE_AMBULANCE_TEAM   = createRemoveAmbulanceTeamIcon();
    public static final Icon REMOVE_POLICE_FORCE     = createRemovePoliceForceIcon();
    public static final Icon REMOVE_FIRE_STATION     = createRemoveFireStationIcon();
    public static final Icon REMOVE_AMBULANCE_CENTRE = createRemoveAmbulanceCentreIcon();
    public static final Icon REMOVE_POLICE_OFFICE    = createRemovePoliceOfficeIcon();
    public static final Icon REMOVE_REFUGE           = createRemoveRefugeIcon();
    public static final Icon REMOVE_GAS_STATION      = createRemoveGasStationIcon();
    public static final Icon REMOVE_HYDRANT          = createRemoveHydrantIcon();
    public static final Icon REMOVE_FIRE             = createRemoveFireIcon();

    private ToolIcons() {}

    private static Icon createPlaceCivilianIcon() {
        return createPlaceHumanIcon(CIVILIAN_COLOUR);
    }

    private static Icon createPlaceFireBrigadeIcon() {
        return createPlaceHumanIcon(FIRE_BRIGADE_COLOUR);
    }

    private static Icon createPlaceAmbulanceTeamIcon() {
        return createPlaceHumanIcon(AMBULANCE_TEAM_COLOUR);
    }

    private static Icon createPlacePoliceForceIcon() {
        return createPlaceHumanIcon(POLICE_FORCE_COLOUR);
    }

    private static Icon createPlaceFireStationIcon() {
        return createPlaceAreaIcon(FIRE_STATION_COLOUR);
    }

    private static Icon createPlaceAmbulanceCentreIcon() {
        return createPlaceAreaIcon(AMBULANCE_CENTRE_COLOUR);
    }

    private static Icon createPlacePoliceOfficeIcon() {
        return createPlaceAreaIcon(POLICE_OFFICE_COLOUR);
    }

    private static Icon createPlaceRefugeIcon() {
        return createPlaceAreaIcon(REFUGE_COLOUR);
    }

    private static Icon createPlaceGasStationIcon() {
        return createPlaceAreaIcon(GAS_STATION_COLOUR);
    }

    private static Icon createPlaceHydrantIcon() {
        return createPlaceAreaIcon(HYDRANT_COLOUR);
    }

    private static Icon createPlaceFireIcon() {
        return createPlaceAreaIcon(FIRE_COLOUR);
    }

    private static Icon createRemoveCivilianIcon() {
        return createRemoveHumanIcon(CIVILIAN_COLOUR);
    }

    private static Icon createRemoveFireBrigadeIcon() {
        return createRemoveHumanIcon(FIRE_BRIGADE_COLOUR);
    }

    private static Icon createRemoveAmbulanceTeamIcon() {
        return createRemoveHumanIcon(AMBULANCE_TEAM_COLOUR);
    }

    private static Icon createRemovePoliceForceIcon() {
        return createRemoveHumanIcon(POLICE_FORCE_COLOUR);
    }

    private static Icon createRemoveFireStationIcon() {
        return createRemoveAreaIcon(FIRE_STATION_COLOUR);
    }

    private static Icon createRemoveAmbulanceCentreIcon() {
        return createRemoveAreaIcon(AMBULANCE_CENTRE_COLOUR);
    }

    private static Icon createRemovePoliceOfficeIcon() {
        return createRemoveAreaIcon(POLICE_OFFICE_COLOUR);
    }

    private static Icon createRemoveRefugeIcon() {
        return createRemoveAreaIcon(REFUGE_COLOUR);
    }

    private static Icon createRemoveGasStationIcon() {
        return createRemoveAreaIcon(GAS_STATION_COLOUR);
    }

    private static Icon createRemoveHydrantIcon() {
        return createRemoveAreaIcon(HYDRANT_COLOUR);
    }

    private static Icon createRemoveFireIcon() {
        return createRemoveAreaIcon(FIRE_COLOUR);
    }

    private static Icon createPlaceHumanIcon(Color colour) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawHumanIcon(g2d, colour);
        g2d.dispose();
        return new ImageIcon(image);
    }

    private static Icon createPlaceAreaIcon(Color colour) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawAreaIcon(g2d, colour);
        g2d.dispose();
        return new ImageIcon(image);
    }

    private static Icon createRemoveHumanIcon(Color colour) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawHumanIcon(g2d, colour);
        drawRemoveMark(g2d);
        g2d.dispose();
        return new ImageIcon(image);
    }

    private static Icon createRemoveAreaIcon(Color colour) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawAreaIcon(g2d, colour);
        drawRemoveMark(g2d);
        g2d.dispose();
        return new ImageIcon(image);
    }

    private static void drawHumanIcon(Graphics2D g2d, Color colour) {
        g2d.setColor(colour);
        g2d.fillOval(1, 1, ICON_SIZE - 2, ICON_SIZE - 2);
        g2d.setColor(HUMAN_STROKE_COLOUR);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(1, 1, ICON_SIZE - 2, ICON_SIZE - 2);
    }

    private static void drawAreaIcon(Graphics2D g2d, Color colour) {
        g2d.setColor(colour);
        g2d.fillRect(1, 1, ICON_SIZE - 3, ICON_SIZE - 3);
        g2d.setColor(AREA_STROKE_COLOUR);
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRect(1, 1, ICON_SIZE - 3, ICON_SIZE - 3);
    }

    private static void drawRemoveMark(Graphics2D g2d) {
        g2d.setColor(REMOVE_MARK_COLOUR);
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(0, 0, ICON_SIZE, ICON_SIZE);
    }

}
