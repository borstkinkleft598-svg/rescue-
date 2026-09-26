package AndroidRoboTeam.module.complex.SEUAT;

import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.world.SEUWorldService;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SEUHumanDetector extends HumanDetector {

    private EntityID result;

    private Clustering clustering;

    private PathPlanning pathPlanning;

    private SEUWorldService world;

    private SEUATHumanService SEUATHumanService;

    private static final int AGENT_MOVEMENT = 7000;

    public SEUHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
                        "adf.impl.module.algorithm.KMeansClustering");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
                        "adf.impl.module.algorithm.KMeansClustering");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.impl.module.algorithm.DijkstraPathPlanning");
                this.clustering = moduleManager.getModule("SampleRoadDetector.Clustering",
                        "adf.impl.module.algorithm.KMeansClustering");
                break;
        }

        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;

        world = moduleManager.getModule("WorldService.Default", SEUConstants.WORLD_HELPER_DEFAULT_PATH);
        this.SEUATHumanService = new SEUATHumanService(world, ai, wi, si, moduleManager, developData, clustering,
                pathPlanning);
        this.registerModule(SEUATHumanService);

    }

    @Override
    public HumanDetector calc() {
        Set<EntityID> changedEntities = this.worldInfo.getChanged().getChangedEntities();
        StandardEntity myPosEntity = this.worldInfo.getPosition((Human) this.agentInfo.me());
        if (myPosEntity instanceof Building && !(myPosEntity instanceof Refuge)) {

            Building building = (Building) myPosEntity;
            if (building.isBrokennessDefined() && building.getBrokenness() > 0) {

                for (EntityID entityID : changedEntities) {

                    StandardEntity entity = this.worldInfo.getEntity(entityID);

                    if (entityID.equals(this.agentInfo.getID())) {
                        continue;
                    }

                    if (entity instanceof Civilian civilian) {

                        if (civilian.getPosition().equals(myPosEntity.getID())
                                && this.isHumanNeedTransport(civilian)) {

                            this.result = civilian.getID();
                            return this;
                        }
                    }
                }
            }
        }

        result = SEUATHumanService.getTargetByDistance();
        if (result == null) {
            result = SEUATHumanService.getTargetByTimeAndBuriedness();
        }
        return this;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        return this;
    }

    private boolean isHumanValid(Human human) {
        if (human == null) {
            return false;
        }
        return human.isHPDefined() && human.getHP() > 0;
    }

    private boolean isHumanNeedTransport(Human human) {
        if (!this.isHumanValid(human)) {
            return false;
        }
        return human.isBuriednessDefined() && human.getBuriedness() == 0;
    }
}
