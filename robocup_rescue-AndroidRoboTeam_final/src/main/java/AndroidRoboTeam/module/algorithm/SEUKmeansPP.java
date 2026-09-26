package AndroidRoboTeam.module.algorithm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.module.algorithm.Clustering;
import adf.core.component.module.algorithm.StaticClustering;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.util.List;
import java.util.*;

public class SEUKmeansPP extends StaticClustering {
    private int entityClusterIdx = -1;
    private int entityIdsClusterIdx = -1;
    private Collection<StandardEntity> lastClusterEntitiesQueryResult = new ArrayList<>();
    private Collection<EntityID> lastClusterEntityIDsQueryResult = new ArrayList<>();
    private List<StandardEntity> centerEntityList;
    private List<EntityID> centerIDList;
    private Map<Integer, List<StandardEntity>> clusterEntitiesList;
    private List<List<EntityID>> clusterEntityIDsList;

    private int repeatPrecompute;
    private int repeatPreparate;
    private boolean calced = false;
    private boolean assignAgentsFlag;

    private int clusterNumber;
    private int agentSize;
    private int clusterNumberParamAT = 1;
    private int clusterNumberParamFB = 1;
    private Collection<StandardEntity> entities;
    private ArrayList<ClusterNode> clusterNodeList = new ArrayList<>();
    private ArrayList<StandardEntity> sortedTeamAgents = new ArrayList<>();

    private int allocations[] = null;

    private final int maxIterations = 30;

    class ClusterNode implements Clusterable {
        public Area area = null;
        public int clusterIndex = 0;
        double point[] = new double[2];

        @Override
        public double[] getPoint() {
            return point;
        }

        public ClusterNode(Area area, int clusterIndex) {
            this.area = area;
            this.clusterIndex = clusterIndex;
            this.point[0] = area.getX();
            this.point[1] = area.getY();
        }

    }

    public SEUKmeansPP(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.repeatPrecompute = developData.getInteger("sample.module.SampleKMeans.repeatPrecompute", 7);
        this.repeatPreparate = developData.getInteger("sample.module.SampleKMeans.repeatPreparate", 30);
        this.clusterNumber = developData.getInteger("sample.module.SampleKMeans.clusterSize", 10);
        this.assignAgentsFlag = developData.getBoolean("sample.module.SampleKMeans.assignAgentsFlag", true);
        this.clusterEntityIDsList = new ArrayList<>();
        this.centerIDList = new ArrayList<>();
        this.clusterEntitiesList = new HashMap<>();
        this.centerEntityList = new ArrayList<>();
        this.entities = wi.getEntitiesOfType(
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT,
                StandardEntityURN.BUILDING,
                StandardEntityURN.REFUGE,
                StandardEntityURN.GAS_STATION,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE);

        if (agentInfo.me().getStandardURN().equals(StandardEntityURN.POLICE_FORCE)) {
            this.entities = wi.getEntitiesOfType(
                    StandardEntityURN.ROAD,
                    StandardEntityURN.HYDRANT,
                    StandardEntityURN.BUILDING,
                    StandardEntityURN.REFUGE,
                    StandardEntityURN.GAS_STATION,
                    StandardEntityURN.AMBULANCE_CENTRE,
                    StandardEntityURN.FIRE_STATION,
                    StandardEntityURN.POLICE_OFFICE);
        } else {
            this.entities = wi.getEntitiesOfType(
                    StandardEntityURN.BUILDING,
                    StandardEntityURN.REFUGE,
                    StandardEntityURN.AMBULANCE_CENTRE,
                    StandardEntityURN.FIRE_STATION,
                    StandardEntityURN.POLICE_OFFICE);
        }

        if (agentInfo.me().getStandardURN().equals(StandardEntityURN.AMBULANCE_TEAM)) {
            agentSize = scenarioInfo.getScenarioAgentsAt();
        } else if (agentInfo.me().getStandardURN().equals(StandardEntityURN.FIRE_BRIGADE)) {
            agentSize = scenarioInfo.getScenarioAgentsFb();
        } else if (agentInfo.me().getStandardURN().equals(StandardEntityURN.POLICE_FORCE)) {
            agentSize = scenarioInfo.getScenarioAgentsPf();
        }

        clusterNumber = Math.min(30, agentSize);
    }

    @Override

    public Clustering updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }
        this.centerEntityList.clear();
        this.clusterEntitiesList.clear();
        return this;
    }

    @Override

    public Clustering precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);

        if (this.calced) {
            return this;
        }

        this.calcClusterAssignEntities();
        return this;
    }

    @Override
    public Clustering resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.calced) {
            return this;
        }
        this.calcClusterAssignEntities();

        return this;
    }

    @Override
    public Clustering preparate() {
        super.preparate();

        if (this.calced) {
            return this;
        }

        this.calcClusterAssignEntities();

        return this;
    }

    private void assignAgent() {
        allocations = new int[sortedTeamAgents.size()];
        double clusterCenters[][] = new double[clusterNumber][3];
        for (int i = 0; i < clusterNumber; i++) {
            clusterCenters[i][0] = 0;
            clusterCenters[i][1] = 0;
            clusterCenters[i][2] = 0;
        }
        for (ClusterNode clusterNode : clusterNodeList) {
            clusterCenters[clusterNode.clusterIndex][0] += clusterNode.point[0];
            clusterCenters[clusterNode.clusterIndex][1] += clusterNode.point[1];
            clusterCenters[clusterNode.clusterIndex][2] += 1;
        }
        for (int i = 0; i < clusterNumber; i++) {
            if (clusterCenters[i][2] != 0) {
                clusterCenters[i][0] /= clusterCenters[i][2];
                clusterCenters[i][1] /= clusterCenters[i][2];
            }
        }

        double[][] costs = new double[sortedTeamAgents.size()][clusterNumber];
        for (int i = 0; i < costs.length; i++) {
            for (int j = 0; j < costs[0].length; j++) {
                StandardEntity agentStd = sortedTeamAgents.get(i);
                double ax = (int) (agentStd.getProperty(4614).getValue());
                double ay = (int) (agentStd.getProperty(4615).getValue());

                double dist = dist(ax, ay, clusterCenters[j][0], clusterCenters[j][1]);
                costs[i][j] = dist;
            }
        }
        SEUHungarian SEUHungarian = new SEUHungarian(costs);

        allocations = SEUHungarian.execute();

    }

    private void calcClusterAssignEntities() {
        clusterNodeList.clear();
        sortedTeamAgents.clear();
        sortedTeamAgents.addAll(worldInfo.getEntitiesOfType(agentInfo.me().getStandardURN()));
        sortedTeamAgents.sort(new Comparator<StandardEntity>() {
            @Override
            public int compare(StandardEntity se1, StandardEntity se2) {
                return se1.getID().getValue() - se2.getID().getValue();
            }
        });
        this.clusterNumber = Math.max(1, sortedTeamAgents.size());

        if (agentInfo.me().getStandardURN().equals(StandardEntityURN.AMBULANCE_TEAM)) {
            this.clusterNumber = (this.clusterNumber + clusterNumberParamAT - 1) / clusterNumberParamAT;
        }

        if (agentInfo.me().getStandardURN().equals(StandardEntityURN.FIRE_BRIGADE)) {
            this.clusterNumber = (this.clusterNumber + clusterNumberParamFB - 1) / clusterNumberParamFB;
        }

        Collection<StandardEntity> allNonAgentSE = this.worldInfo.getEntitiesOfType(
                StandardEntityURN.FIRE_STATION,
                StandardEntityURN.POLICE_OFFICE,
                StandardEntityURN.AMBULANCE_CENTRE,
                StandardEntityURN.REFUGE,
                StandardEntityURN.GAS_STATION,
                StandardEntityURN.BUILDING,
                StandardEntityURN.ROAD,
                StandardEntityURN.HYDRANT);

        for (StandardEntity se : allNonAgentSE) {
            clusterNodeList.add(new ClusterNode((Area) se, 0));
        }

        KMeansPlusPlusClusterer<ClusterNode> kmeanspp = new KMeansPlusPlusClusterer<ClusterNode>(
                clusterNumber,
                maxIterations);
        kmeanspp.getRandomGenerator().setSeed(agentInfo.me().getStandardURN().ordinal() + 1);

        List<CentroidCluster<ClusterNode>> dbscanCluster = kmeanspp.cluster(clusterNodeList);
        int clusterIndex = 0;

        for (CentroidCluster<ClusterNode> centroidCluster : dbscanCluster) {
            for (ClusterNode clusterNode : centroidCluster.getPoints()) {
                clusterNode.clusterIndex = clusterIndex;
            }
            clusterIndex++;
        }
        if (!agentInfo.me().getStandardURN().equals(StandardEntityURN.POLICE_FORCE)) {
            assignAT_FB();
        } else
            assignAgent();
        this.calced = true;

    }

    private void assignAT_FB() {
        ArrayList<StandardEntity> agentList = new ArrayList<>(sortedTeamAgents);
        allocations = new int[sortedTeamAgents.size()];
        double clusterCenters[][] = new double[clusterNumber][3];
        for (int i = 0; i < clusterNumber; i++) {
            clusterCenters[i][0] = 0;
            clusterCenters[i][1] = 0;
            clusterCenters[i][2] = 0;
        }
        for (ClusterNode clusterNode : clusterNodeList) {
            clusterCenters[clusterNode.clusterIndex][0] += clusterNode.point[0];
            clusterCenters[clusterNode.clusterIndex][1] += clusterNode.point[1];
            clusterCenters[clusterNode.clusterIndex][2] += 1;
        }
        for (int i = 0; i < clusterNumber; i++) {
            if (clusterCenters[i][2] != 0) {
                clusterCenters[i][0] /= clusterCenters[i][2];
                clusterCenters[i][1] /= clusterCenters[i][2];
            }
        }
        double costs[][] = new double[sortedTeamAgents.size()][clusterNumber];
        for (int i = 0; i < costs.length; i++) {
            for (int j = 0; j < costs[0].length; j++) {
                StandardEntity agentStd = sortedTeamAgents.get(i);

                double ax = (int) (agentStd.getProperty(4614).getValue());
                double ay = (int) (agentStd.getProperty(4615).getValue());

                double dist = dist(ax, ay, clusterCenters[j][0], clusterCenters[j][1]);
                costs[i][j] = dist;
            }
        }
        int clusterIndex = 0;
        while (!agentList.isEmpty()) {
            StandardEntity agent = this.getNearestAgent(costs, agentList, clusterIndex);
            int agent_number = sortedTeamAgents.indexOf(agent);
            allocations[agent_number] = clusterIndex;

            agentList.remove(agent);
            clusterIndex++;
            if (clusterIndex >= this.clusterNumber) {
                clusterIndex = 0;
            }
        }
    }

    private StandardEntity getNearestAgent(double[][] costMatrix, ArrayList<StandardEntity> srcAgentList,
            int ClusterIdx) {
        StandardEntity result = null;
        double cost = Integer.MAX_VALUE;
        for (StandardEntity agent : srcAgentList) {
            if (result == null) {
                result = agent;
            } else {
                if (costMatrix[sortedTeamAgents.indexOf(agent)][ClusterIdx] < cost) {
                    result = agent;
                    cost = costMatrix[sortedTeamAgents.indexOf(agent)][ClusterIdx];
                }
            }
        }
        return result;
    }

    @Override
    public int getClusterNumber() {
        return clusterNumber;
    }

    @Override
    public int getClusterIndex(StandardEntity entity) {
        if (sortedTeamAgents.contains(entity)) {
            return getAgentInitialClusterIndex(entity);
        }
        return getClusterIndex(entity.getID());
    }

    @Override
    public int getClusterIndex(EntityID id) {
        StandardEntity entity = this.worldInfo.getEntity(id);
        if (entity != null && sortedTeamAgents.contains(entity)) {
            return getAgentInitialClusterIndex(entity);
        }
        for (ClusterNode clusterNode : clusterNodeList) {
            if (clusterNode.area.getID().equals(id)) {
                return clusterNode.clusterIndex;
            }
        }
        return -1;
    }

    private int getAgentInitialClusterIndex(StandardEntity agent) {
        if (allocations == null) {
            return -1;
        }
        return allocations[sortedTeamAgents.indexOf(agent)];
    }

    @Override
    public Collection<EntityID> getClusterEntityIDs(int index) {
        if (entityIdsClusterIdx != index) {
            lastClusterEntityIDsQueryResult.clear();
            for (ClusterNode clusterNode : clusterNodeList) {
                if (clusterNode.clusterIndex == index) {
                    lastClusterEntityIDsQueryResult.add(clusterNode.area.getID());
                }
            }
        }
        entityIdsClusterIdx = index;
        return new ArrayList<>(lastClusterEntityIDsQueryResult);
    }

    @Override
    public Collection<StandardEntity> getClusterEntities(int index) {
        if (entityClusterIdx != index) {
            lastClusterEntitiesQueryResult.clear();
            for (ClusterNode clusterNode : clusterNodeList) {
                if (clusterNode.clusterIndex == index) {
                    lastClusterEntitiesQueryResult.add(clusterNode.area);
                }
            }
        }
        entityClusterIdx = index;
        return new ArrayList<>(lastClusterEntitiesQueryResult);
    }

    @Override
    public Clustering calc() {
        return this;
    }

    public static double dist(double Ax, double Ay, double Bx, double By) {
        return Math.hypot(Ax - Bx, Ay - By);
    }

}
