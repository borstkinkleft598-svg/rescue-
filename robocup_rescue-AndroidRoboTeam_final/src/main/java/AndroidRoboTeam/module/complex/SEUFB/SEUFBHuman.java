package AndroidRoboTeam.module.complex.SEUFB;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.MessageFireBrigade;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import java.util.List;

public class SEUFBHuman {
    public static final double MEAN_VELOCITY_OF_MOVING = 31445.392;
    private Human human;
    private PathPlanning pathPlanning;
    private Clustering clustering;
    private AgentInfo agentInfo;
    private WorldInfo worldInfo;
    private ScenarioInfo scenarioInfo;
    private double value;
    private boolean isReachable;

    private double arriveTime;
    private double deadTime;
    private boolean canRescue;
    private Integer numOfFireBrigade;
    private Integer numOfRescueFireBrigade;
    private Integer rescueTime;
    private Integer totalTime;
    private final long MAX_ARRIVE_TIME = 40;

    public double getDistanceToMe() {
        return this.distanceToMe;
    }

    private double distanceToMe;
    private double distanceToRefuge;

    public SEUFBHuman(Human human, AgentInfo ai, WorldInfo wi, ScenarioInfo si, PathPlanning pathPlanning,
            Clustering clustering) {
        this.human = human;
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;
        this.pathPlanning = pathPlanning;
        this.clustering = clustering;
    }

    public double getDeadTime() {
        return this.deadTime;
    }

    public void updateInfo(MessageManager messageManager) {
        EntityID from = this.agentInfo.getPosition();
        List<EntityID> result = this.pathPlanning.setFrom(from).setDestination(this.human.getPosition()).calc()
                .getResult();
        EntityID entityID = this.human.getPosition();
        if (from.getValue() == this.human.getPosition().getValue()) {
            this.isReachable = true;
        } else {
            this.isReachable = result != null;
        }
        this.numOfFireBrigade = 0;
        this.numOfRescueFireBrigade = 0;
        for (CommunicationMessage cm : messageManager.getReceivedMessageList(MessageFireBrigade.class)) {
            if (cm instanceof MessageFireBrigade messageFireBrigade) {
                if (messageFireBrigade.getAction() == MessageFireBrigade.ACTION_RESCUE
                        && messageFireBrigade.getTargetID().equals(this.getHuman().getID())) {
                    this.numOfRescueFireBrigade++;
                }
            }
        }
        for (StandardEntity standardEntity : this.worldInfo.getEntitiesOfType(StandardEntityURN.FIRE_BRIGADE)) {
            FireBrigade fireBrigade = (FireBrigade) standardEntity;
            if (fireBrigade.getPosition().getValue() == this.getHuman().getPosition().getValue()) {
                numOfFireBrigade++;
            }
        }
        if (numOfFireBrigade != 0) {
            this.rescueTime = this.getHuman().getBuriedness() / numOfFireBrigade;
        } else {
            this.rescueTime = 60;
        }

        if (this.isReachable) {
            this.distanceToMe = this.pathPlanning.getDistance();
            this.arriveTime = this.distanceToMe / MEAN_VELOCITY_OF_MOVING;
            if (this.human.getDamage() == 0) {

                this.deadTime = (double) this.human.getHP() / 20;
            } else {
                this.deadTime = (double) this.human.getHP() / this.human.getDamage();
            }

            if ((this.arriveTime > deadTime) || (this.arriveTime > this.MAX_ARRIVE_TIME)) {
                this.canRescue = false;

            } else {
                if (this.numOfRescueFireBrigade != 0
                        && !this.agentInfo.getPosition().equals(this.getHuman().getPosition())
                        && (double) this.getHuman().getBuriedness() / numOfRescueFireBrigade < (deadTime - 40)) {
                    this.canRescue = false;
                } else {
                    this.canRescue = true;
                }
            }
            this.value = 1 / (arriveTime);
        } else {
            this.distanceToMe = 10000000;
            this.arriveTime = 1000;
            this.value = 0;
            this.canRescue = false;
        }
    }

    public double getValue() {
        return this.value;
    }

    public Human getHuman() {
        return this.human;
    }

    public boolean isReachable() {
        return isReachable;
    }

    public boolean isCanRescue() {
        return canRescue;
    }

    public double getArriveTime() {
        return arriveTime;
    }

}
