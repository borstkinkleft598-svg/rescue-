package AndroidRoboTeam.world;

public interface SEUConstants {
    double MEAN_VELOCITY_OF_MOVING = 31445.392;

    boolean DEBUG_FB_WORLD_HELPER = false;

    boolean DEBUG_STUCK_HELPER = false;

    boolean DEBUG_CHANNEL_SUBSCRIBE = false;

    boolean DEBUG_GUIDELINE_TERMINAL_OUTPUT = false;
    boolean DEBUG_GUIDELINE_VISUALIZATION = false;

    String WORLD_HELPER_DEFAULT_PATH = "AndroidRoboTeam.world.SEUWorldService";
    String FIRE_BRIGADE_WORLD_HELPER_PATH = "AndroidRoboTeam.world.SEUFBWorldService";
    String GRAPH_HELPER_DEFAULT_PATH = "AndroidRoboTeam.module.algorithm.GraphService";
    String PATH_PLANNING_PATH = "AndroidRoboTeam.module.algorithm.SEUPathPlanning";

    int AGENT_SIZE = 1000;
    int AGENT_PASSING_THRESHOLD = 725;
    int AGENT_PASSING_THRESHOLD_SMALL = 500;
    int AGENT_MINIMUM_PASSING_THRESHOLD = 200;
    int COLLINEAR_THRESHOLD = 10;
    int TOO_SMALL_EDGE_THRESHOLD = 600;

    int ROAD_PASSABLY_RESET_TIME_IN_MEDIUM_MAP = 20;

    double MEAN_FB_MESSAGE_BYTE_SIZE = 40;
    double MEAN_PF_MESSAGE_BYTE_SIZE = 44;
    double MEAN_AT_MESSAGE_BYTE_SIZE = 44;

    String FIRE_BRIGADE_COUNT_KEY = "scenario.agents.fb";
    String AMBULANCE_TEAM_COUNT_KEY = "scenario.agents.at";
    String POLICE_FORCE_COUNT_KEY = "scenario.agents.pf";
    String FIRE_STATION_COUNT_KEY = "scenario.agents.fs";
    String AMBULANCE_CENTRE_COUNT_KEY = "scenario.agents.ac";
    String POLICE_OFFICE_COUNT_KEY = "scenario.agents.po";

    double AGENT_RADIUS = 100.0;
    double CIVILIAN_RADIUS = 100.0;

    boolean PF_ROAD_DETECTOR_LOG = true;
    boolean PF_ACTION_EXT_CLEAR_LOG = true;
    boolean AT_ACTION_EXT_TRANSPORT_LOG = true;

}
