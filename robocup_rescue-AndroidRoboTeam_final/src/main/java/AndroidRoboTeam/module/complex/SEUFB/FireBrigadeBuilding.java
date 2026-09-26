package AndroidRoboTeam.module.complex.SEUFB;

import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.world.SEUWorldService;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Building;
import rescuecore2.worldmodel.EntityID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FireBrigadeBuilding {
    public static final double MEAN_VELOCITY_OF_MOVING = 31445.392;
    private Building me;
    private boolean visited = false;
    private Map<Integer, EntityID> civilians;
    private boolean isReachable = true;
    private double value = 0;
    private AgentInfo agentInfo;
    private WorldInfo worldInfo;
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private double distance = 0;
    private ScenarioInfo scenarioInfo;
    private SEUWorldService SEUWorldService;

    public FireBrigadeBuilding(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            PathPlanning pathPlanning, Clustering clustering, EntityID id) {
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;
        this.pathPlanning = pathPlanning;
        this.clustering = clustering;

        this.civilians = new HashMap<>();
        this.me = (Building) wi.getEntity(id);
        this.SEUWorldService = moduleManager.getModule("WorldService.Default", SEUConstants.WORLD_HELPER_DEFAULT_PATH);
    }

    public void remove(EntityID entityID) {
        this.civilians.remove(entityID);
    }

    public void updateInfo(MessageManager messageManager) {
        EntityID from = this.agentInfo.getPosition();
        List<EntityID> result = this.pathPlanning.setFrom(from).setDestination(this.getId()).calc().getResult();
        this.isReachable = result != null;
        if (this.isReachable) {
            this.distance = this.pathPlanning.getDistance();
        } else {
            this.distance = this.worldInfo.getDistance(this.agentInfo.getPosition(), this.me.getID());
        }
        double time = this.distance / MEAN_VELOCITY_OF_MOVING;
        double mayCivilian = 0;
        for (Integer integer : civilians.keySet()) {
            mayCivilian += 1 / (double) integer;
        }
        if (mayCivilian != 0) {
            this.value = (mayCivilian * 100) / time;
        } else {
            this.value = 1 / (time * 2);
        }

    }

    public boolean isReachable() {
        return this.isReachable;
    }

    public EntityID getId() {
        return this.me.getID();
    }

    public void addCivilian(Integer num, EntityID entityID) {
        this.civilians.put(num, entityID);
    }

    public Building getBuilding() {
        return this.me;
    }

    public boolean isNoBrokenness() {
        return this.me.isBrokennessDefined() && this.me.getBrokenness() <= 0;
    }

    public boolean isVisited() {
        return this.visited;
    }

    public void setVisited(Boolean visited) {
        this.visited = visited;
    }

    public int getCivilianSize() {
        return this.civilians.size();
    }

    public boolean isCivilianEmpty() {
        return civilians.isEmpty();
    }

    public double getValue() {
        return this.value;
    }
}
