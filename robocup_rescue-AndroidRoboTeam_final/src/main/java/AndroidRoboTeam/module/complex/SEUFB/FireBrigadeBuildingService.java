package AndroidRoboTeam.module.complex.SEUFB;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.util.List;
import java.util.*;

public class FireBrigadeBuildingService {
    private Map<EntityID, FireBrigadeBuilding> allBuildingsMap;
    private Set<FireBrigadeBuilding> unReachableBuildings;
    private Set<FireBrigadeBuilding> noBrokennessBuildings;
    private List<Integer> visitedCluster;
    private Set<FireBrigadeBuilding> visitedBuildings;
    private Set<FireBrigadeBuilding> targetBuildings;
    private Set<FireBrigadeBuilding> noKMeansBuildings;
    private Set<FireBrigadeBuilding> clusteringBuildings;
    private Set<EntityID> heardCivilian;
    private Map<EntityID, SEUHeardCivilian> heardCivilianMap;
    private Set<Civilian> newCivilian;
    private AgentInfo agentInfo;
    private WorldInfo worldInfo;
    private ScenarioInfo scenarioInfo;
    private Clustering clustering;
    private PathPlanning pathPlanning;
    private Set<FireBrigadeBuilding> fireBrigadeBuildingSet;
    private Boolean initClusteringBuildings = true;
    private MessageManager messageManager;

    public FireBrigadeBuildingService(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            PathPlanning pathPlanning, Clustering clustering) {
        this.agentInfo = ai;
        this.worldInfo = wi;
        this.scenarioInfo = si;
        this.clustering = clustering;
        this.pathPlanning = pathPlanning;

        this.visitedCluster = new ArrayList<>();
        this.allBuildingsMap = new HashMap<>();
        this.unReachableBuildings = new HashSet<>();
        this.noBrokennessBuildings = new HashSet<>();
        this.visitedBuildings = new HashSet<>();
        this.targetBuildings = new HashSet<>();
        this.noKMeansBuildings = new HashSet<>();
        this.newCivilian = new HashSet<>();
        this.clusteringBuildings = new HashSet<>();
        this.heardCivilianMap = new HashMap<>();
        this.heardCivilian = new HashSet<>();
        this.fireBrigadeBuildingSet = new HashSet<>();
        Set<EntityID> allBuildings = new HashSet<>();
        allBuildings.addAll(wi.getEntityIDsOfType(
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.REFUGE,
                StandardEntityURN.BUILDING,
                StandardEntityURN.GAS_STATION));
        for (EntityID id : allBuildings) {
            FireBrigadeBuilding fireBrigadeBuilding = new FireBrigadeBuilding(ai, wi, si, moduleManager, pathPlanning,
                    clustering, id);
            this.allBuildingsMap.put(id, fireBrigadeBuilding);
        }

    }

    public void updateInfo(MessageManager messageManager) {
        if (this.messageManager == null) {
            this.messageManager = messageManager;
        }
        this.unReachableBuildings.clear();
        this.noKMeansBuildings.clear();
        this.targetBuildings.clear();
        this.newCivilian.clear();
        this.heardCivilian.clear();
        this.fireBrigadeBuildingSet.clear();
        if (initClusteringBuildings) {
            Collection<StandardEntity> cB = this.clustering
                    .getClusterEntities(this.clustering.getClusterIndex(this.agentInfo.getID()));
            this.visitedCluster.add(this.clustering.getClusterIndex(this.agentInfo.getID()));
            for (StandardEntity standardEntity : cB) {
                if (this.allBuildingsMap.containsKey(standardEntity.getID())) {
                    this.clusteringBuildings.add(allBuildingsMap.get(standardEntity.getID()));
                }
            }
            initClusteringBuildings = false;
        }

        Collection<StandardEntity> agents = this.worldInfo.getEntitiesOfType(
                StandardEntityURN.AMBULANCE_TEAM,
                StandardEntityURN.FIRE_BRIGADE,
                StandardEntityURN.POLICE_FORCE);
        for (StandardEntity standardEntity : agents) {
            StandardEntity position = this.worldInfo.getEntity(((Human) standardEntity).getPosition());
            if (position instanceof Building) {
                if (this.allBuildingsMap.containsKey(position.getID())) {
                    FireBrigadeBuilding fireBrigadeBuilding = this.allBuildingsMap.get(position.getID());
                    fireBrigadeBuilding.setVisited(true);
                    this.visitedBuildings.add(fireBrigadeBuilding);
                }
            }
            Human human = (Human) standardEntity;
            Pair<Integer, Integer> location = human.getLocation(this.worldInfo.getRawWorld());
            Point point = new Point(location.first(), location.second());

            int range = (int) scenarioInfo.getRawConfig().getIntValue("perception.los.max-distance");
            Collection<StandardEntity> ens = this.worldInfo.getObjectsInRange(human.getID(), range);
            for (StandardEntity standardEntity1 : ens) {
                if (standardEntity1 instanceof Building && !(standardEntity1 instanceof Refuge)) {
                    FireBrigadeBuilding fireBrigadeBuilding = this.allBuildingsMap.get(standardEntity1.getID());
                }
            }
        }
        for (EntityID entityID : this.worldInfo.getChanged().getChangedEntities()) {
            StandardEntity standardEntity = this.worldInfo.getEntity(entityID);
            if (standardEntity instanceof Civilian) {
                Civilian civilian = (Civilian) standardEntity;
                if (civilian.isPositionDefined()) {
                    this.newCivilian.add(civilian);
                }
            } else if (standardEntity instanceof Building) {
                if (this.allBuildingsMap.containsKey(standardEntity.getID())) {
                }
            }
        }
        for (CommunicationMessage message : messageManager
                .getReceivedMessageList(StandardMessage.class)) {
            StandardEntity entity = null;
            entity = MessageUtil.reflectMessage(worldInfo,
                    (StandardMessage) message);
            if (entity instanceof Civilian) {
                Civilian civilian = (Civilian) entity;
                if (civilian.isPositionDefined()) {
                    newCivilian.add(civilian);
                }
            }
        }
        for (StandardEntity standardEntity : newCivilian) {
            Civilian civilian = (Civilian) standardEntity;
            if (civilian.isPositionDefined()) {
                SEUHeardCivilian SEUHeardCivilian = this.heardCivilianMap.get(civilian.getID());
                if (SEUHeardCivilian != null) {
                    for (FireBrigadeBuilding fireBrigadeBuilding : SEUHeardCivilian.getPossibleBuilding()) {
                        fireBrigadeBuilding.remove(civilian.getID());
                    }
                }

            }
        }
        for (Command command : this.agentInfo.getHeard()) {
            if (command instanceof AKSpeak && ((AKSpeak) command).getChannel() == 0
                    && command.getAgentID() != this.agentInfo.getID()) {
                byte[] receivedData = ((AKSpeak) command).getContent();
                String voiceString = new String(receivedData);
                if ("Help".equalsIgnoreCase(voiceString) || "Ouch".equalsIgnoreCase(voiceString)) {

                    StandardEntity standardEntity = this.worldInfo.getEntity(command.getAgentID());
                    if (standardEntity == null || (standardEntity instanceof Civilian
                            && !((Civilian) standardEntity).isPositionDefined())) {
                        heardCivilian.add(command.getAgentID());
                        if (!this.heardCivilianMap.containsKey(command.getAgentID())) {
                            SEUHeardCivilian SEUHeardCivilian = new SEUHeardCivilian(command.getAgentID(),
                                    this.agentInfo, this.pathPlanning, this.messageManager);
                            this.heardCivilianMap.put(command.getAgentID(), SEUHeardCivilian);
                        }
                    }
                }
            }
        }
        int range = (int) scenarioInfo.getRawConfig().getIntValue("comms.channels.0.range");
        Collection<StandardEntity> ens = this.worldInfo.getObjectsInRange(this.agentInfo.getID(), range);
        int x1 = (int) this.agentInfo.getX() - range;
        int x2 = (int) this.agentInfo.getX() + range;
        int y1 = (int) this.agentInfo.getY() - range;
        int y2 = (int) this.agentInfo.getY() + range;
        for (StandardEntity standardEntity : ens) {
            if (standardEntity instanceof Building) {
                Building building = (Building) standardEntity;
                if (building.getX() < x1 || building.getX() > x2 || building.getY() < y1 || building.getY() > y2) {
                    continue;
                }
                FireBrigadeBuilding fireBrigadeBuilding = this.allBuildingsMap.get(standardEntity.getID());
                if (this.visitedBuildings.contains(fireBrigadeBuilding) ||
                        (fireBrigadeBuilding.getBuilding().isBrokennessDefined()
                                && fireBrigadeBuilding.getBuilding().getBrokenness() == 0)) {
                    continue;
                }
                fireBrigadeBuildingSet.add(this.allBuildingsMap.get(standardEntity.getID()));
            }
        }
        for (EntityID entityID : this.heardCivilian) {
            SEUHeardCivilian civilian = this.heardCivilianMap.get(entityID);
            civilian.merge(fireBrigadeBuildingSet);
        }
        this.targetBuildings.addAll(this.clusteringBuildings);
        this.targetBuildings.removeAll(this.visitedBuildings);
        this.targetBuildings.removeIf(FireBrigadeBuilding::isNoBrokenness);
        if (targetBuildings == null || targetBuildings.size() * 10 <= this.clusteringBuildings.size()) {
            int newCluster = this.getNewCluster();
            if (newCluster != -1) {
                Collection<StandardEntity> cB = this.clustering.getClusterEntities(newCluster);
                for (StandardEntity standardEntity : cB) {
                    if (this.allBuildingsMap.containsKey(standardEntity.getID())) {
                        this.clusteringBuildings.add(allBuildingsMap.get(standardEntity.getID()));
                    }
                }
                this.targetBuildings.addAll(this.clusteringBuildings);
            }
        }
        this.targetBuildings.removeAll(this.visitedBuildings);
        this.targetBuildings.removeIf(FireBrigadeBuilding::isNoBrokenness);

        for (FireBrigadeBuilding fireBrigadeBuilding : this.targetBuildings) {
            fireBrigadeBuilding.updateInfo(messageManager);
        }

        this.noKMeansBuildings.addAll(this.noBrokennessBuildings);
        this.noKMeansBuildings.addAll(this.visitedBuildings);
    }

    private int getNewCluster() {
        Integer Cluster_Index = -1, chosen = -1;
        Integer dis = Integer.MAX_VALUE;
        for (Collection<EntityID> entityIDS : this.clustering.getAllClusterEntityIDs()) {
            Cluster_Index++;
            if (visitedCluster.contains(Cluster_Index))
                continue;
            for (EntityID id : entityIDS) {
                int distance = this.worldInfo.getDistance(this.agentInfo.getID(), id);
                if (distance < dis) {
                    dis = distance;
                    chosen = Cluster_Index;
                }
            }
        }
        visitedCluster.add(chosen);
        return chosen;
    }

    public Set<FireBrigadeBuilding> getTargetBuildings() {

        return this.targetBuildings;
    }

    public Set<FireBrigadeBuilding> getNoKMeasBuildings() {
        return this.noKMeansBuildings;
    }
}
