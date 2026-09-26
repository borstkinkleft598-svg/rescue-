package AndroidRoboTeam.centralized.SEUFB;

import adf.core.agent.action.Action;
import adf.core.agent.action.common.ActionMove;
import adf.core.agent.action.common.ActionRest;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.centralized.CommandFire;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.centralized.CommandExecutor;
import adf.core.component.extaction.ExtAction;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static rescuecore2.standard.entities.StandardEntityURN.REFUGE;

public class DefaultCommandExecutorFire extends CommandExecutor<CommandFire> {

    private static final int ACTION_UNKNOWN = -1;
    private static final int ACTION_REST = CommandFire.ACTION_REST;
    private static final int ACTION_MOVE = CommandFire.ACTION_MOVE;
    private static final int ACTION_EXTINGUISH = CommandFire.ACTION_EXTINGUISH;
    private static final int ACTION_REFILL = CommandFire.ACTION_REFILL;
    private static final int ACTION_AUTONOMY = CommandFire.ACTION_AUTONOMY;

    private PathPlanning pathPlanning;

    private ExtAction actionFireRescue;
    private ExtAction actionExtMove;

    private int type;
    private EntityID target;
    private EntityID commanderID;

    public DefaultCommandExecutorFire(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.type = ACTION_UNKNOWN;
        switch (scenarioInfo.getMode()) {
            case PRECOMPUTATION_PHASE:
                this.pathPlanning = moduleManager.getModule("CommandExecutorFire.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionFireRescue = moduleManager.getExtAction("CommandExecutorFire.ActionFireRescue",
                        "AndroidRoboTeam.extaction.fb.SEUActionFireRescue");
                this.actionExtMove = moduleManager.getExtAction("CommandExecutorFire.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
            case PRECOMPUTED:
                this.pathPlanning = moduleManager.getModule("CommandExecutorFire.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionFireRescue = moduleManager.getExtAction("CommandExecutorFire.ActionFireRescue",
                        "AndroidRoboTeam.extaction.fb.SEUActionFireRescue");
                this.actionExtMove = moduleManager.getExtAction("CommandExecutorFire.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
            case NON_PRECOMPUTE:
                this.pathPlanning = moduleManager.getModule("CommandExecutorFire.PathPlanning",
                        "adf.core.sample.module.algorithm.SamplePathPlanning");
                this.actionFireRescue = moduleManager.getExtAction("CommandExecutorFire.ActionFireRescue",
                        "AndroidRoboTeam.extaction.fb.SEUActionFireRescue");
                this.actionExtMove = moduleManager.getExtAction("CommandExecutorFire.ActionExtMove",
                        "adf.core.sample.extaction.ActionExtMove");
                break;
        }
    }

    @Override
    public CommandExecutor setCommand(CommandFire command) {
        EntityID agentID = this.agentInfo.getID();
        if (command.isToIDDefined() && Objects.requireNonNull(command.getToID()).getValue() == agentID.getValue()) {
            this.type = command.getAction();
            this.target = command.getTargetID();
            this.commanderID = command.getSenderID();
        }
        return this;
    }

    @Override
    public CommandExecutor updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.pathPlanning.updateInfo(messageManager);
        this.actionFireRescue.updateInfo(messageManager);
        this.actionExtMove.updateInfo(messageManager);

        if (this.isCommandCompleted() && this.type != ACTION_UNKNOWN) {
            messageManager.addMessage(new MessageReport(true, true, false, this.commanderID));
            this.type = ACTION_UNKNOWN;
            this.target = null;
            this.commanderID = null;
        }
        return this;
    }

    @Override
    public CommandExecutor precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }
        this.pathPlanning.precompute(precomputeData);
        this.actionFireRescue.precompute(precomputeData);
        this.actionExtMove.precompute(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }
        this.pathPlanning.resume(precomputeData);
        this.actionFireRescue.resume(precomputeData);
        this.actionExtMove.resume(precomputeData);
        return this;
    }

    @Override
    public CommandExecutor preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }
        this.pathPlanning.preparate();
        this.actionFireRescue.preparate();
        this.actionExtMove.preparate();
        return this;
    }

    @Override
    public CommandExecutor calc() {
        this.result = null;
        switch (this.type) {
            case ACTION_REST:
                this.result = this.calcRestAction();
                return this;
            case ACTION_MOVE:
                if (this.target != null) {
                    this.result = this.actionExtMove.setTarget(this.target).calc().getAction();
                }
                return this;
            case ACTION_EXTINGUISH:
                this.result = this.calcRescueOrMoveAction(this.target);
                return this;
            case ACTION_REFILL:
                return this;
            case ACTION_AUTONOMY:
                this.result = this.calcAutonomyAction(this.target);
                return this;
            default:
                return this;
        }
    }

    private Action calcRestAction() {
        EntityID position = this.agentInfo.getPosition();
        if (this.target == null) {
            Collection<EntityID> refuges = this.worldInfo.getEntityIDsOfType(REFUGE);
            if (refuges.contains(position)) {
                return new ActionRest();
            }
            this.pathPlanning.setFrom(position);
            this.pathPlanning.setDestination(refuges);
            List<EntityID> path = this.pathPlanning.calc().getResult();
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
            return new ActionRest();
        }
        if (position.getValue() != this.target.getValue()) {
            List<EntityID> path = this.pathPlanning.getResult(position, this.target);
            if (path != null && !path.isEmpty()) {
                return new ActionMove(path);
            }
        }
        return new ActionRest();
    }

    private Action calcAutonomyAction(EntityID targetId) {
        if (targetId == null) {
            return null;
        }
        StandardEntity targetEntity = this.worldInfo.getEntity(targetId);
        if (targetEntity == null) {
            return null;
        }
        if (targetEntity.getStandardURN() == REFUGE) {
            FireBrigade agent = (FireBrigade) this.agentInfo.me();
            if (agent.getDamage() > 0) {
                return this.calcRestAction();
            }
            return this.actionExtMove.setTarget(targetId).calc().getAction();
        }
        return this.calcRescueOrMoveAction(targetId);
    }

    private Action calcRescueOrMoveAction(EntityID targetId) {
        if (targetId == null) {
            return null;
        }
        StandardEntity targetEntity = this.worldInfo.getEntity(targetId);
        if (targetEntity instanceof Human) {
            return this.actionFireRescue.setTarget(targetId).calc().getAction();
        }
        EntityID areaTarget = this.resolveAreaTarget(targetId);
        if (areaTarget != null) {
            return this.actionExtMove.setTarget(areaTarget).calc().getAction();
        }
        return null;
    }

    private EntityID resolveAreaTarget(EntityID targetId) {
        if (targetId == null) {
            return null;
        }
        StandardEntity targetEntity = this.worldInfo.getEntity(targetId);
        if (targetEntity instanceof Area) {
            return targetId;
        }
        if (targetEntity instanceof Human human && human.isPositionDefined()) {
            return human.getPosition();
        }
        if (targetEntity instanceof Blockade blockade && blockade.isPositionDefined()) {
            return blockade.getPosition();
        }
        return null;
    }

    private boolean isCommandCompleted() {
        FireBrigade agent = (FireBrigade) this.agentInfo.me();
        switch (this.type) {
            case ACTION_REST:
                if (this.target == null) {
                    return agent.getDamage() == 0;
                }
                StandardEntity refuge = this.worldInfo.getEntity(this.target);
                if (refuge != null && refuge.getStandardURN() == REFUGE && agent.getPosition().equals(this.target)) {
                    return agent.getDamage() == 0;
                }
                return false;
            case ACTION_MOVE:
                return this.target == null || this.agentInfo.getPosition().equals(this.target);
            case ACTION_EXTINGUISH:
                if (this.target == null) {
                    return true;
                }
                StandardEntity targetEntity = this.worldInfo.getEntity(this.target);
                if (targetEntity instanceof Human human) {
                    return (human.isHPDefined() && human.getHP() == 0)
                            || (human.isBuriednessDefined() && human.getBuriedness() <= 0);
                }
                EntityID areaTarget = this.resolveAreaTarget(this.target);
                return areaTarget == null || this.agentInfo.getPosition().equals(areaTarget);
            case ACTION_REFILL:
                return true;
            case ACTION_AUTONOMY:
                if (this.target == null) {
                    return true;
                }
                StandardEntity autonomyTarget = this.worldInfo.getEntity(this.target);
                if (autonomyTarget != null && autonomyTarget.getStandardURN() == REFUGE) {
                    this.type = agent.getDamage() > 0 ? ACTION_REST : ACTION_MOVE;
                    return this.isCommandCompleted();
                }
                this.type = ACTION_EXTINGUISH;
                return this.isCommandCompleted();
            default:
                return true;
        }
    }

}
