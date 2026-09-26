package AndroidRoboTeam.module.algorithm;

import adf.core.agent.info.AgentInfo;
import rescuecore2.misc.Pair;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.worldmodel.EntityID;

public class MyEdge {
    private EntityID id;
    private Pair<Node, Node> nodes;
    private EntityID areaId;
    private int weight;
    private boolean isPassable;
    private Pair<EntityID, EntityID> neighbours;
    private AgentInfo agentInfo;
    public static final double BUILDING_EDGE_DEV_WEIGHT = 1.4;
    public static final double ON_TOO_SMALL_EDGE_DEV_WEIGHT = 20;

    public MyEdge(EntityID id, Pair<Node, Node> nodes, EntityID areaId, int weight, AgentInfo agentInfo) {
        this.id = id;
        this.nodes = nodes;
        this.areaId = areaId;
        this.weight = weight;
        this.isPassable = true;
        this.agentInfo = agentInfo;
    }

    public void setPassable(boolean passable) {
        isPassable = passable;
    }

    public EntityID getId() {
        return id;
    }

    public Pair<Node, Node> getNodes() {
        return nodes;
    }

    public Node getOtherNode(Node id) {
        if (nodes.first().equals(id)) {
            return nodes.second();
        } else {
            return nodes.first();
        }
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public void setEntranceEdgeWeight() {
        if (!(agentInfo.me() instanceof PoliceForce))
            this.weight *= BUILDING_EDGE_DEV_WEIGHT;
    }

    public void setOnTooSmallEdgeWeight() {
        if (!(agentInfo.me() instanceof PoliceForce))
            this.weight *= ON_TOO_SMALL_EDGE_DEV_WEIGHT;
    }

    public EntityID getAreaId() {
        return areaId;
    }

    public boolean isPassable() {
        return isPassable;
    }

    public Pair<EntityID, EntityID> getNeighbours() {
        return neighbours;
    }

    public void setNeighbours(EntityID n1, EntityID n2) {
        neighbours = new Pair<>(n1, n2);
    }
}
