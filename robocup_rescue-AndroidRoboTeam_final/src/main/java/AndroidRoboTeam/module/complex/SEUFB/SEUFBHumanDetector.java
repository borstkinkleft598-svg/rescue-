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
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;

public class SEUFBHumanDetector extends HumanDetector {

    private EntityID result;
    private Clustering clustering;

    private PathPlanning pathPlanning;

    private EntityID lastPosition;
    private EntityID nowPosition;
    private Map<EntityID, Integer> hangUpMap;
    private Set<EntityID> deadHumanSet;

    private Map<EntityID, StandardEntity> invalidHumanPosition;
    private SEUFBHumanService SEUFBHumanService;

    public SEUFBHumanDetector(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("SampleRoadDetector.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.clustering = moduleManager.getModule("SampleHumanDetector.Clustering",
                        "adf.core.sample.module.algorithm.SampleKMeans");
                break;
        }
        this.hangUpMap = new HashMap<>();
        this.invalidHumanPosition = new HashMap<>();
        this.deadHumanSet = new HashSet<>();
        this.SEUFBHumanService = new SEUFBHumanService(ai, wi, si, moduleManager, developData, clustering, pathPlanning);
    }

    @Override
    public HumanDetector calc() {
        FireBrigade fireBrigade = (FireBrigade) this.agentInfo.me();
        if (fireBrigade.isBuriednessDefined() && fireBrigade.getBuriedness() > 0) {
            return this;
        }
        Set<SEUFBHuman> seenCivilian = new HashSet<>();
        for (EntityID entityID : this.agentInfo.getChanged().getChangedEntities()) {
            StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
            if (standardEntity instanceof Civilian) {
                Civilian civilian = (Civilian) standardEntity;
                if (civilian.isPositionDefined() && civilian.getPosition().equals(this.agentInfo.getPosition())) {
                    if (civilian.isHPDefined() && civilian.getHP() > 0 && civilian.isBuriednessDefined()
                            && civilian.getBuriedness() > 0) {
                        SEUFBHuman fbHuman = this.SEUFBHumanService.getAllHuman().get(civilian.getID());
                        if (fbHuman != null) {
                            seenCivilian.add(fbHuman);
                        }
                    }
                }
            }
        }
        if (!seenCivilian.isEmpty()) {
            TreeSet<SEUFBHuman> objects = new TreeSet<>(
                    ((o1, o2) -> Double.compare(o1.getDeadTime(), o2.getDeadTime())));
            objects.addAll(seenCivilian);
            this.result = objects.first().getHuman().getID();
        } else {
            this.result = SEUFBHumanService.getTargetByDistance();
        }
        return this;
    }

    @Override
    public EntityID getTarget() {
        return this.result;
    }

    @Override
    public HumanDetector precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);

        if (this.getCountPrecompute() >= 2) {
            return this;
        }

        this.clustering.precompute(precomputeData);
        return this;
    }

    @Override
    public HumanDetector resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);

        this.clustering.resume(precomputeData);
        return this;
    }

    @Override
    public HumanDetector preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.clustering.preparate();
        return this;
    }

    @Override
    public HumanDetector updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        lastPosition = nowPosition;
        nowPosition = agentInfo.getPosition();
        this.clustering.updateInfo(messageManager);
        this.SEUFBHumanService.updateInfo(messageManager);
        passHangUp(messageManager);
        updateInvalidPositionMap();
        return this;
    }

    private boolean isReachable(EntityID targetID) {
        EntityID from = agentInfo.getPosition();
        StandardEntity entity = worldInfo.getPosition(targetID);

        if (entity != null) {
            EntityID destination = entity.getID();
            if (from.equals(destination)) {
                return true;
            }
            List<EntityID> result = pathPlanning.setFrom(from).setDestination(destination).calc().getResult();
            return result != null;
        } else {
            return false;
        }

    }

    private void passHangUp(MessageManager messageManager) {
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID id : hangUpMap.keySet()) {
            int i = hangUpMap.get(id) - 1;
            if (i <= 0) {
                toRemove.add(id);
            } else {
                hangUpMap.put(id, i);
            }
        }
        hangUpMap.keySet().removeAll(toRemove);
    }

    private boolean isTargetPositionValid(EntityID entityID) {
        StandardEntity entity = worldInfo.getPosition(entityID);
        if (agentInfo.getPosition().equals(entity.getID())) {
            Set<EntityID> set = worldInfo.getChanged().getChangedEntities();
            if (!set.contains(entityID)) {
                invalidHumanPosition.put(entityID, worldInfo.getPosition(entityID));
                return false;
            }
        }
        return true;
    }

    private void updateInvalidPositionMap() {
        Set<EntityID> toRemove = new HashSet<>();
        for (EntityID target : invalidHumanPosition.keySet()) {
            StandardEntity entity = worldInfo.getPosition(target);
            if (!entity.equals(invalidHumanPosition.get(target))) {
                toRemove.add(target);
            }
        }
        invalidHumanPosition.keySet().removeAll(toRemove);
    }
}
