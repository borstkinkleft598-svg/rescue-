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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUSearch extends Search {
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private Set<EntityID> visitedRoad;
    private EntityID result;
    private Collection<EntityID> unsearchedBuildingIDs;
    private SEUSearchService SEUSearchService;
    private MessageManager messageManager;
    private int stayCount = 0;

    public SEUSearch(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        this.unsearchedBuildingIDs = new HashSet<>();

        StandardEntityURN agentURN = ai.me().getStandardURN();
        switch (si.getMode()) {
            case PRECOMPUTATION_PHASE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Ambulance",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Police",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                }
                break;
            case PRECOMPUTED:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Ambulance",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Police",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                }
                break;
            case NON_PRECOMPUTE:
                if (agentURN == AMBULANCE_TEAM) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Ambulance",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Ambulance",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == FIRE_BRIGADE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Fire",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Fire",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                } else if (agentURN == POLICE_FORCE) {
                    this.pathPlanning = moduleManager.getModule("SampleSearch.PathPlanning.Police",
                            "adf.core.sample.module.algorithm.SamplePathPlanning");
                    this.clustering = moduleManager.getModule("SampleSearch.Clustering.Police",
                            "adf.core.sample.module.algorithm.SampleKMeans");
                }
                break;
        }
        visitedRoad = new HashSet<>();
        this.SEUSearchService = moduleManager.getModule("SearchService.Default");
        registerModule(this.pathPlanning);
        registerModule(this.clustering);
    }

    @Override
    public Search updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.SEUSearchService.updateInfo(messageManager);
        this.messageManager = messageManager;
        this.result = null;
        this.unsearchedBuildingIDs.removeAll(this.worldInfo.getChanged().getChangedEntities());

        if (this.unsearchedBuildingIDs.isEmpty()) {
            this.reset();
            this.unsearchedBuildingIDs.removeAll(this.worldInfo.getChanged().getChangedEntities());
        }
        return this;
    }

    @Override
    public Search calc() {
        EntityID target = this.SEUSearchService.getBestTarget();
        if (target != null) {
            this.stayCount = 0;
            visitedRoad.clear();
            this.result = target;
        } else {
            this.stayCount++;
            if (stayCount > 6) {
                this.stayCount = 0;
                visitedRoad.clear();
            }
            visitedRoad.add(this.agentInfo.getPosition());
            Set<EntityID> changed = this.worldInfo.getChanged().getChangedEntities();
            for (EntityID entityID : changed) {
                StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
                if (standardEntity instanceof Area && this.agentInfo.getPosition().getValue() != entityID.getValue()
                        && (!visitedRoad.contains(entityID))) {
                    List<EntityID> path = this.pathPlanning.setFrom(this.agentInfo.getPosition())
                            .setDestination(entityID).calc().getResult();
                    if (path != null && !path.isEmpty()) {
                        this.result = entityID;
                        break;
                    }
                }
            }
        }

        return this;
    }

    private void reset() {
        this.unsearchedBuildingIDs.clear();

        Collection<StandardEntity> clusterEntities = null;
        if (this.clustering != null) {
            int clusterIndex = this.clustering.getClusterIndex(this.agentInfo.getID());
            clusterEntities = this.clustering.getClusterEntities(clusterIndex);

        }
        if (clusterEntities != null && clusterEntities.size() > 0) {
            for (StandardEntity entity : clusterEntities) {
                if (entity instanceof Building && entity.getStandardURN() != REFUGE) {
                    this.unsearchedBuildingIDs.add(entity.getID());
                }
            }
        } else {
            this.unsearchedBuildingIDs.addAll(this.worldInfo.getEntityIDsOfType(
                    BUILDING,
                    GAS_STATION,
                    AMBULANCE_CENTRE,
                    FIRE_STATION,
                    POLICE_OFFICE));
        }
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public Search precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
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
}