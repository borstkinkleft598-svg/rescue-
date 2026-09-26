package AndroidRoboTeam.module.complex.SEUFB;

import AndroidRoboTeam.world.SEUConstants;

import AndroidRoboTeam.world.SEUFBWorldService;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUSearchService extends AbstractModule {
    private Clustering clustering;
    private FireBrigadeBuildingService fireBrigadeBuildingService;
    private Set<FireBrigadeBuilding> tempBuildings;
    private PathPlanning pathPlanning;
    private EntityID target;
    private MessageManager messageManager;
    private SEUFBWorldService fireBrigadeWorld;

    public SEUSearchService(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
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

        this.fireBrigadeBuildingService = new FireBrigadeBuildingService(this.agentInfo, this.worldInfo,
                this.scenarioInfo, moduleManager, this.pathPlanning, this.clustering);
        this.tempBuildings = new HashSet<>();
    }

    public AbstractModule updateInfo(MessageManager messageManager) {
        this.messageManager = messageManager;
        fireBrigadeBuildingService.updateInfo(messageManager);
        target = null;

        if (fireBrigadeWorld == null && agentInfo.me().getStandardURN() == StandardEntityURN.FIRE_BRIGADE) {
            fireBrigadeWorld = (SEUFBWorldService) moduleManager.getModule(
                    "WorldService.FireBrigade", SEUConstants.FIRE_BRIGADE_WORLD_HELPER_PATH);
        }

        return this;
    }

    @Override
    public AbstractModule calc() {
        return null;
    }

    public EntityID getBestTarget() {

        if (fireBrigadeWorld != null && fireBrigadeWorld.shouldBreakClusterRestriction()) {
            if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
                System.out.println("[FB Rescue Timeout] " + agentInfo.getID() +
                        " breaking cluster restriction to find priority target");
            }

            EntityID priorityTarget = fireBrigadeWorld.getPriorityRescueTargetWhenTimeout();
            if (priorityTarget != null) {
                if (fireBrigadeWorld.isTargetReachable(priorityTarget)) {
                    target = priorityTarget;
                    if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
                        System.out.println("[FB Priority Target] " + agentInfo.getID() +
                                " selected priority target " + priorityTarget + " (reachable)");
                    }
                } else {
                    if (messageManager != null) {
                        fireBrigadeWorld.sendPoliceRescueRequest(messageManager, priorityTarget);
                        if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
                            System.out.println("[FB Police Request] " + agentInfo.getID() +
                                    " requested police to clear path to " + priorityTarget);
                        }
                    }
                }

                if (target != null) {
                    this.tempBuildings.clear();

                    return target;
                }
            }
        }

        Set<FireBrigadeBuilding> targets = this.fireBrigadeBuildingService.getTargetBuildings();
        TreeSet<FireBrigadeBuilding> objects = new TreeSet<>(
                ((o1, o2) -> Double.compare(o2.getValue(), o1.getValue())));
        objects.addAll(targets);
        for (FireBrigadeBuilding fireBrigadeBuilding : objects) {
            if (fireBrigadeBuilding.getBuilding() instanceof Refuge) {
                continue;
            }
            if (this.isReachable(fireBrigadeBuilding)) {
                target = fireBrigadeBuilding.getBuilding().getID();
                break;
            } else {
            }

        }
        if (target != null) {
            this.tempBuildings.clear();

        }

        return target;
    }

    public Boolean isReachable(FireBrigadeBuilding fireBrigadeBuilding) {
        EntityID from = this.agentInfo.getPosition();
        List<EntityID> result = this.pathPlanning.setFrom(from).setDestination(fireBrigadeBuilding.getId()).calc()
                .getResult();
        return result != null;
    }

    public Boolean isReachable(Area area) {
        EntityID from = this.agentInfo.getPosition();
        List<EntityID> result = this.pathPlanning.setFrom(from).setDestination(area.getID()).calc().getResult();
        return result != null;
    }

    public Collection<StandardEntity> getNoKMeansBuildings() {
        Set<FireBrigadeBuilding> noKMeasBuildings = this.fireBrigadeBuildingService.getNoKMeasBuildings();
        Collection<StandardEntity> result = new HashSet<>();
        for (FireBrigadeBuilding fireBrigadeBuilding : noKMeasBuildings) {
            result.add(this.worldInfo.getEntity(fireBrigadeBuilding.getId()));
        }
        return result;
    }

    public class SEUValueSorter implements Comparator<StandardEntity> {
        private StandardEntity reference;
        private WorldInfo worldInfo;

        public SEUValueSorter(WorldInfo wi, StandardEntity reference) {
            this.reference = reference;
            this.worldInfo = wi;
        }

        public int compare(StandardEntity a, StandardEntity b) {
            int d1 = this.worldInfo.getDistance(this.reference, a);
            int d2 = this.worldInfo.getDistance(this.reference, b);
            return d1 - d2;
        }
    }
}
