package AndroidRoboTeam.module.complex.SEUFB;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.Search;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static rescuecore2.standard.entities.StandardEntityURN.AMBULANCE_CENTRE;
import static rescuecore2.standard.entities.StandardEntityURN.BUILDING;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_BRIGADE;
import static rescuecore2.standard.entities.StandardEntityURN.FIRE_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.GAS_STATION;
import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class SEUFBSearch extends Search {
    private final PathPlanning pathPlanning;
    private final Clustering clustering;
    private final Set<EntityID> visitedAreas;
    private final Collection<EntityID> unsearchedBuildingIDs;
    private final SEUSearchService searchService;

    private EntityID result;
    private int stayCount = 0;

    public SEUFBSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        if (ai.me().getStandardURN() != FIRE_BRIGADE) {
            throw new IllegalStateException("SEUFBSearch can only be used by FireBrigade.");
        }

        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            default:
                throw new IllegalStateException("Unsupported scenario mode: " + si.getMode());
        }

        this.visitedAreas = new HashSet<>();
        this.unsearchedBuildingIDs = new HashSet<>();
        this.searchService = moduleManager.getModule("SearchService.Default");
        registerModule(this.pathPlanning);
        registerModule(this.clustering);
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        this.searchService.updateInfo(messageManager);
        this.removeObservedBuildings();
        if (this.shouldDropCurrentTarget()) {
            this.result = null;
        }

        if (this.unsearchedBuildingIDs.isEmpty()) {
            this.reset();
            this.removeObservedBuildings();
        }
        return this;
    }

    @Override
    public Search calc() {
        EntityID helperTarget = this.searchService.getBestTarget();
        if (helperTarget != null) {
            this.result = helperTarget;
            this.stayCount = 0;
            this.visitedAreas.clear();
            return this;
        }

        if (this.isActiveTarget(this.result)) {
            return this;
        }

        EntityID buildingTarget = this.selectUnsearchedBuilding();
        if (buildingTarget != null) {
            this.result = buildingTarget;
            this.stayCount = 0;
            this.visitedAreas.clear();
            return this;
        }

        EntityID areaTarget = this.selectExplorationArea();
        this.result = areaTarget;
        return this;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public Search precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public Search resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.worldInfo.requestRollback();
        return this;
    }

    @Override
    public Search preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.worldInfo.requestRollback();
        return this;
    }

    private void removeObservedBuildings() {
        Set<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();
        this.unsearchedBuildingIDs.removeAll(changed);

        StandardEntity currentPosition = this.worldInfo.getEntity(this.agentInfo.getPosition());
        if (currentPosition instanceof Building && currentPosition.getStandardURN() != REFUGE) {
            this.unsearchedBuildingIDs.remove(currentPosition.getID());
        }
    }

    private boolean shouldDropCurrentTarget() {
        if (this.result == null) {
            return false;
        }
        if (this.agentInfo.getPosition().equals(this.result)) {
            return true;
        }

        StandardEntity entity = this.worldInfo.getEntity(this.result);
        if (!(entity instanceof Area)) {
            return true;
        }
        if (entity instanceof Building && this.worldInfo.getChanged().getChangedEntities().contains(this.result)) {
            return true;
        }
        return !this.isReachable(this.result);
    }

    private boolean isActiveTarget(EntityID target) {
        if (target == null) {
            return false;
        }
        if (this.agentInfo.getPosition().equals(target)) {
            return false;
        }

        StandardEntity entity = this.worldInfo.getEntity(target);
        if (!(entity instanceof Area)) {
            return false;
        }
        if (entity instanceof Building && this.worldInfo.getChanged().getChangedEntities().contains(target)) {
            return false;
        }
        return this.isReachable(target);
    }

    private EntityID selectUnsearchedBuilding() {
        EntityID from = this.agentInfo.getPosition();
        List<EntityID> candidates = new ArrayList<>(this.unsearchedBuildingIDs);
        candidates.sort(Comparator.comparingInt(id -> this.worldInfo.getDistance(from, id)));

        for (EntityID candidate : candidates) {
            if (candidate.equals(from)) {
                continue;
            }
            if (this.isReachable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private EntityID selectExplorationArea() {
        this.stayCount++;
        if (this.stayCount > 6) {
            this.stayCount = 0;
            this.visitedAreas.clear();
        }
        this.visitedAreas.add(this.agentInfo.getPosition());

        EntityID changedAreaTarget = this.selectChangedAreaTarget();
        if (changedAreaTarget != null) {
            return changedAreaTarget;
        }
        return this.selectNeighborAreaTarget();
    }

    private EntityID selectChangedAreaTarget() {
        Set<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();
        for (EntityID entityID : changed) {
            StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
            if (!(standardEntity instanceof Area)) {
                continue;
            }
            if (this.agentInfo.getPosition().equals(entityID) || this.visitedAreas.contains(entityID)) {
                continue;
            }
            if (this.isReachable(entityID)) {
                return entityID;
            }
        }
        return null;
    }

    private EntityID selectNeighborAreaTarget() {
        StandardEntity current = this.worldInfo.getEntity(this.agentInfo.getPosition());
        if (!(current instanceof Area area)) {
            return null;
        }

        List<EntityID> neighbours = new ArrayList<>(area.getNeighbours());
        neighbours.sort(Comparator.comparingInt(id -> this.worldInfo.getDistance(this.agentInfo.getPosition(), id)));
        for (EntityID neighbour : neighbours) {
            if (this.visitedAreas.contains(neighbour)) {
                continue;
            }
            StandardEntity entity = this.worldInfo.getEntity(neighbour);
            if (entity instanceof Area && entity.getStandardURN() != REFUGE && this.isReachable(neighbour)) {
                return neighbour;
            }
        }
        return null;
    }

    private boolean isReachable(EntityID target) {
        if (target == null) {
            return false;
        }
        EntityID from = this.agentInfo.getPosition();
        if (from.equals(target)) {
            return true;
        }
        List<EntityID> resultPath = this.pathPlanning.setFrom(from).setDestination(target).calc().getResult();
        return resultPath != null && !resultPath.isEmpty();
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();

        Collection<StandardEntity> clusterEntities = null;
        int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
        if (clusterIndex >= 0) {
            clusterEntities = this.clustering.getClusterEntities(clusterIndex);
        }

        if (clusterEntities != null && !clusterEntities.isEmpty()) {
            for (StandardEntity entity : clusterEntities) {
                if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                    this.unsearchedBuildingIDs.add(entity.getID());
                }
            }
            return;
        }

        this.unsearchedBuildingIDs.addAll(this.worldInfo.getEntityIDsOfType(
                BUILDING,
                GAS_STATION,
                AMBULANCE_CENTRE,
                FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE));
    }
}
