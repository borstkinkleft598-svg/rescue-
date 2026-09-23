package gis2.scenario;

import java.awt.Color;

/**
 * Defines colour constants for rendering entities in the scenario editor.
 * This is a utility class and cannot be instantiated.
 */
public class EntityColours {

    /** The colour for civilians. */
    public static final Color CIVILIAN_COLOUR         = Color.GREEN;
    /** The colour for fire brigades. */
    public static final Color FIRE_BRIGADE_COLOUR     = Color.RED;
    /** The colour for ambulance teams. */
    public static final Color AMBULANCE_TEAM_COLOUR   = Color.WHITE;
    /** The colour for police forces. */
    public static final Color POLICE_FORCE_COLOUR     = Color.BLUE;
    /** The colour for fire stations. */
    public static final Color FIRE_STATION_COLOUR     = new Color(255, 255,   0);
    /** The colour for ambulance centres. */
    public static final Color AMBULANCE_CENTRE_COLOUR = new Color(255, 255, 255);
    /** The colour for police offices. */
    public static final Color POLICE_OFFICE_COLOUR    = new Color(  0,   0, 255);
    /** The colour for refuges. */
    public static final Color REFUGE_COLOUR           = new Color(  0, 128,   0);
    /** The colour for fires. */
    public static final Color FIRE_COLOUR             = new Color(255,   0,   0, 128);
    /** The colour for gas stations. */
    public static final Color GAS_STATION_COLOUR      = new Color(255, 128,   0);
    /** The colour for hydrants. */
    public static final Color HYDRANT_COLOUR          = new Color(128, 128,   0);

    private EntityColours() {}

}
