package AndroidRoboTeam.world;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.AbstractModule;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SEUFBWorldService extends SEUWorldService {
    private static final int RESCUE_TIMEOUT_TURNS = 20;

    private final Map<EntityID, SEUFBRescueStatus> rescueStatsByAgent;
    private PathPlanning pathPlanning;

    public SEUFBWorldService(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.rescueStatsByAgent = new HashMap<>();
    }

    @Override
    public AbstractModule precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        return this;
    }

    @Override
    public SEUWorldService resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        return this;
    }

    @Override
    public SEUWorldService preparate() {
        super.preparate();
        if (this.pathPlanning == null) {
            this.pathPlanning = this.moduleManager.getModule("PathPlanning.Default", SEUConstants.PATH_PLANNING_PATH);
        }
        return this;
    }

    @Override
    public SEUWorldService updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        this.updateRescueStats();
        return this;
    }

    public void recordRescueSuccess(EntityID targetId) {
        EntityID brigadeId = this.agentInfo.getID();
        SEUFBRescueStatus stats = this.getOrCreateRescueStats(brigadeId);
        stats.updateRescueSuccess(this.agentInfo.getTime(), targetId);

        if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
            System.out.println("[FB Rescue Success] " + brigadeId + " rescued " + targetId
                    + " at turn " + this.agentInfo.getTime());
        }
    }

    public boolean shouldBreakClusterRestriction() {
        SEUFBRescueStatus stats = this.rescueStatsByAgent.get(this.agentInfo.getID());
        return stats != null && stats.isRescueTimeout(RESCUE_TIMEOUT_TURNS);
    }

    public SEUFBRescueStatus getRescueStats() {
        return this.rescueStatsByAgent.get(this.agentInfo.getID());
    }

    public EntityID getPriorityRescueTargetWhenTimeout() {
        if (!this.shouldBreakClusterRestriction()) {
            return null;
        }

        FireBrigade me = (FireBrigade) this.agentInfo.me();
        EntityID myPosition = me.getPosition();
        Collection<StandardEntity> allHumans = this.worldInfo.getEntitiesOfType(
                StandardEntityURN.CIVILIAN,
                StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.POLICE_FORCE);

        EntityID bestTarget = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (StandardEntity entity : allHumans) {
            Human human = (Human) entity;
            if (!human.isPositionDefined()) {
                continue;
            }
            if (!human.isBuriednessDefined() || human.getBuriedness() <= 0) {
                continue;
            }
            if (!human.isHPDefined() || human.getHP() <= 0) {
                continue;
            }

            StandardEntity position = this.worldInfo.getEntity(human.getPosition());
            if (!(position instanceof Area)) {
                continue;
            }

            double distance = this.getDistance(myPosition, human.getPosition());
            if (distance <= 0) {
                distance = 1.0;
            }

            double urgency = 1.0 + (human.getBuriedness() * 0.01);
            if (human.isDamageDefined()) {
                urgency += human.getDamage() * 0.1;
            }

            double score = urgency / distance;
            if (score > bestScore) {
                bestScore = score;
                bestTarget = human.getID();
            }
        }
        return bestTarget;
    }

    public void sendPoliceRescueRequest(MessageManager messageManager, EntityID target) {
        EntityID targetArea = this.resolveAreaTarget(target);
        if (targetArea == null) {
            return;
        }

        Collection<Blockade> blockades = this.worldInfo.getBlockades(targetArea);
        if (blockades == null || blockades.isEmpty()) {
            return;
        }

        for (Blockade blockade : blockades) {
            if (blockade.isRepairCostDefined() && blockade.getRepairCost() > 0) {
                messageManager.addMessage(new CommandPolice(
                        true,
                        StandardMessagePriority.HIGH,
                        null,
                        targetArea,
                        CommandPolice.ACTION_CLEAR));

                if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
                    System.out.println("[FB Police Request] " + this.agentInfo.getID()
                            + " requested police to clear path to " + targetArea);
                }
                return;
            }
        }
    }

    public boolean isTargetReachable(EntityID target) {
        EntityID targetArea = this.resolveAreaTarget(target);
        if (targetArea == null) {
            return false;
        }

        FireBrigade me = (FireBrigade) this.agentInfo.me();
        EntityID myPosition = me.getPosition();
        if (myPosition.equals(targetArea)) {
            return true;
        }

        if (this.pathPlanning == null) {
            this.pathPlanning = this.moduleManager.getModule("PathPlanning.Default", SEUConstants.PATH_PLANNING_PATH);
        }

        List<EntityID> path = this.pathPlanning.getResult(myPosition, targetArea);
        return path != null && !path.isEmpty();
    }

    private void updateRescueStats() {
        EntityID brigadeId = this.agentInfo.getID();
        SEUFBRescueStatus stats = this.getOrCreateRescueStats(brigadeId);
        stats.updateNoRescue(this.agentInfo.getTime());

        if (stats.isRescueTimeout(RESCUE_TIMEOUT_TURNS) && !stats.isTimeout()) {
            stats.markTimeoutStart(this.agentInfo.getTime());
            if (SEUConstants.DEBUG_FB_WORLD_HELPER) {
                System.out.println("[FB Rescue Timeout] " + brigadeId + " has no rescue for "
                        + stats.getNoRescueTurnCount() + " turns.");
            }
        }
    }

    private SEUFBRescueStatus getOrCreateRescueStats(EntityID brigadeId) {
        return this.rescueStatsByAgent.computeIfAbsent(brigadeId, SEUFBRescueStatus::new);
    }

    private EntityID resolveAreaTarget(EntityID target) {
        if (target == null) {
            return null;
        }

        StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity instanceof Area) {
            return target;
        }
        if (entity instanceof Human human && human.isPositionDefined()) {
            return human.getPosition();
        }
        if (entity instanceof Blockade blockade && blockade.isPositionDefined()) {
            return blockade.getPosition();
        }
        return null;
    }
}
