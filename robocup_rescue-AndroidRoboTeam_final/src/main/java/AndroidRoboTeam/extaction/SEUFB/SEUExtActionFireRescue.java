package AndroidRoboTeam.extaction.SEUFB;

import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.world.SEUFBWorldService;
import adf.core.agent.action.Action;
import adf.core.agent.action.ambulance.ActionUnload;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.action.fire.ActionRescue;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.config.NoSuchConfigOptionException;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static rescuecore2.standard.entities.StandardEntityURN.BLOCKADE;

public class SEUExtActionFireRescue extends ExtAction {
    private final PathPlanning pathPlanning;
    private final ExtAction actionExtMove;
    private final int thresholdRest;

    private int kernelTime;
    private EntityID target;

    public SEUExtActionFireRescue(AgentInfo agentInfo, WorldInfo worldInfo, ScenarioInfo scenarioInfo,
            ModuleManager moduleManager, DevelopData developData) {
        super(agentInfo, worldInfo, scenarioInfo, moduleManager, developData);
        this.target = null;
        this.thresholdRest = developData.getInteger("ActionFireRescue.rest", 100);

        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionFireRescue.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionFireRescue.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionFireRescue.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionFireRescue.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("DefaultExtActionFireRescue.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionExtMove = moduleManager.getExtAction("DefaultExtActionFireRescue.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
            default:
                throw new IllegalStateException("Unsupported scenario mode: " + scenarioInfo.getMode());
        }
    }

    @Override
    public ExtAction precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.kernelTime = this.resolveKernelTime();
        return this;
    }

    @Override
    public ExtAction updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        return this;
    }

    @Override
    public ExtAction setTarget(EntityID target) {
        this.target = null;
        if (target == null) {
            return this;
        }

        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity instanceof Human || entity instanceof Area) {
            this.target = target;
        }
        return this;
    }

    @Override
    public ExtAction calc() {
        this.result = null;
        FireBrigade me = (FireBrigade) this.agentInfo.me();

        if (this.needRest(me)) {
            EntityID areaTarget = this.convertArea(this.target);
            ArrayList<EntityID> targets = new ArrayList<>();
            if (areaTarget != null) {
                targets.add(areaTarget);
            }
            this.result = this.calcRefugeAction(me, this.pathPlanning, targets, false);
            if (this.result != null) {
                return this;
            }
        }

        if (this.target != null) {
            this.result = this.calcRescue(me, this.pathPlanning, this.target);
        }
        return this;
    }

    private Action calcRescue(FireBrigade agent, PathPlanning pathPlanning, EntityID target) {
        StandardEntity targetEntity = this.worldInfo.getEntity(target);
        if (targetEntity == null) {
            return null;
        }

        EntityID agentPosition = agent.getPosition();
        if (targetEntity instanceof Human human) {
            if (!human.isPositionDefined()) {
                return null;
            }
            if (human.isHPDefined() && human.getHP() <= 0) {
                return null;
            }

            EntityID targetPosition = this.worldInfo.getPosition(human).getID();
            if (agentPosition.equals(targetPosition)) {
                if (human.isBuriednessDefined() && human.getBuriedness() > 0) {
                    return new ActionRescue(human);
                }
                if (human.isBuriednessDefined() && human.getBuriedness() == 0
                        && human.isHPDefined() && human.getHP() > 0) {
                    SEUFBWorldService world = (SEUFBWorldService) this.moduleManager.getModule(
                            "WorldService.FireBrigade", SEUConstants.FIRE_BRIGADE_WORLD_HELPER_PATH);
                    world.recordRescueSuccess(human.getID());
                }
                return null;
            }

            List<EntityID> path = pathPlanning.setFrom(agentPosition).setDestination(targetPosition).calc().getResult();
            return this.getMoveAction(path);
        }

        if (targetEntity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) targetEntity;
            if (blockade.isPositionDefined()) {
                targetEntity = this.worldInfo.getEntity(blockade.getPosition());
            }
        }

        if (targetEntity instanceof Area) {
            List<EntityID> path = pathPlanning.getResult(agentPosition, targetEntity.getID());
            return this.getMoveAction(path);
        }
        return null;
    }

    private boolean needRest(Human agent) {
        int hp = agent.getHP();
        int damage = agent.getDamage();
        if (hp == 0 || damage == 0) {
            return false;
        }

        int activeTime = (hp / damage) + ((hp % damage) != 0 ? 1 : 0);
        if (this.kernelTime == 0) {
            this.kernelTime = this.resolveKernelTime();
        }
        return damage >= this.thresholdRest
                || (this.kernelTime != -1 && (activeTime + this.agentInfo.getTime() + 20) < this.kernelTime);
    }

    private int resolveKernelTime() {
        try {
            return this.scenarioInfo.getKernelTimesteps();
        } catch (NoSuchConfigOptionException e) {
            return -1;
        }
    }

    private EntityID convertArea(EntityID targetID) {
        if (targetID == null) {
            return null;
        }

        StandardEntity entity = this.worldInfo.getEntity(targetID);
        if (entity == null) {
            return null;
        }
        if (entity instanceof Human human && human.isPositionDefined()) {
            EntityID position = human.getPosition();
            if (this.worldInfo.getEntity(position) instanceof Area) {
                return position;
            }
        } else if (entity instanceof Area) {
            return targetID;
        } else if (entity.getStandardURN() == BLOCKADE) {
            Blockade blockade = (Blockade) entity;
            if (blockade.isPositionDefined()) {
                return blockade.getPosition();
            }
        }
        return null;
    }

    private Action calcRefugeAction(Human human, PathPlanning pathPlanning, Collection<EntityID> targets,
            boolean isUnload) {
        EntityID position = human.getPosition();
        Collection<EntityID> refuges = new HashSet<>(this.worldInfo.getEntityIDsOfType(StandardEntityURN.REFUGE));
        int refugeCount = refuges.size();
        if (refuges.contains(position)) {
            return isUnload ? new ActionUnload() : new ActionRest();
        }

        List<EntityID> firstResult = null;
        while (!refuges.isEmpty()) {
            pathPlanning.setFrom(position);
            pathPlanning.setDestination(refuges);
            List<EntityID> path = pathPlanning.calc().getResult();
            if (path == null || path.isEmpty()) {
                break;
            }

            if (firstResult == null) {
                firstResult = new ArrayList<>(path);
                if (targets == null || targets.isEmpty()) {
                    break;
                }
            }

            EntityID refugeID = path.get(path.size() - 1);
            pathPlanning.setFrom(refugeID);
            pathPlanning.setDestination(targets);
            List<EntityID> fromRefugeToTarget = pathPlanning.calc().getResult();
            if (fromRefugeToTarget != null && !fromRefugeToTarget.isEmpty()) {
                return this.getMoveAction(path);
            }

            refuges.remove(refugeID);
            if (refugeCount == refuges.size()) {
                break;
            }
            refugeCount = refuges.size();
        }
        return firstResult != null ? this.getMoveAction(firstResult) : null;
    }

    private Action getMoveAction(List<EntityID> path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return (ActionMove) this.actionExtMove.setTarget(path.get(path.size() - 1)).calc().getAction();
    }
}
