package AndroidRoboTeam.module.algorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import AndroidRoboTeam.world.SEUConstants;
import AndroidRoboTeam.world.SEUWorldService;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.information.MessageBuilding;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.communication.util.BitStreamReader;
import adf.core.component.module.algorithm.PathPlanning;
import adf.core.launcher.ConsoleOutput;
import rescuecore2.messages.Command;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Building;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Edge;
import rescuecore2.standard.entities.FireBrigade;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.PoliceForce;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.messages.AKSpeak;
import rescuecore2.worldmodel.EntityID;

public class SEUPathPlanning extends PathPlanning {

    private EntityID from;

    private List<EntityID> targets;

    private List<EntityID> result;

    private EntityID resultTarget;

    private List<EntityID> previousPath = new ArrayList<>();

    private Area previousTarget = null;

    private boolean amIPoliceForce = false;
    private int repeatMovingTime = 0;
    private int pathCost = -1;

    private static final double PASSABLE = 1;

    private static final double UNKNOWN = 1.2;

    private static final double IMPASSABLE = 100;

    protected Map<EntityID, Integer> sendBuildingTimeMap;

    private SEUWorldService world;

    private GraphService graph;

    final Class<?>[] standardMessageArgTypes;

    public SEUPathPlanning(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);

        if (agentInfo.me() instanceof FireBrigade) {
            world = moduleManager.getModule("WorldService.FireBrigade", SEUConstants.FIRE_BRIGADE_WORLD_HELPER_PATH);
        } else {
            world = moduleManager.getModule("WorldService.Default", SEUConstants.WORLD_HELPER_DEFAULT_PATH);
        }

        graph = moduleManager.getModule("GraphService.Default", SEUConstants.GRAPH_HELPER_DEFAULT_PATH);

        if (agentInfo.me() instanceof PoliceForce) {
            amIPoliceForce = true;
        }

        this.standardMessageArgTypes = new Class[] { Boolean.TYPE, Integer.TYPE, Integer.TYPE, BitStreamReader.class };

        this.sendBuildingTimeMap = new HashMap<>();
    }

    @Override
    public PathPlanning precompute(PrecomputeData precomputeData) {
        super.precompute(precomputeData);
        if (this.getCountPrecompute() >= 2) {
            return this;
        }

        this.world.precompute(precomputeData);
        this.graph.precompute(precomputeData);
        return this;
    }

    @Override
    public PathPlanning resume(PrecomputeData precomputeData) {
        super.resume(precomputeData);
        if (this.getCountResume() >= 2) {
            return this;
        }

        this.world.resume(precomputeData);
        this.graph.resume(precomputeData);
        return this;
    }

    @Override
    public PathPlanning preparate() {
        super.preparate();
        if (this.getCountPreparate() >= 2) {
            return this;
        }

        this.world.preparate();
        this.graph.preparate();
        return this;
    }

    @Override
    public PathPlanning updateInfo(MessageManager messageManager) {
        super.updateInfo(messageManager);
        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        this.world.updateInfo(messageManager);

        if (this.world.isDominance(this.agentInfo) && !(this.agentInfo.me() instanceof PoliceForce)) {
            sendCommandPolice(messageManager);
        }

        this.graph.updateInfo(messageManager);
        return this;
    }

    public void sendCommandPolice(MessageManager messageManager) {

        for (EntityID entityID : this.world.getCiviliansSeen()) {
            Civilian civilian = (Civilian) worldInfo.getEntity(entityID);

            if (civilian.isHPDefined() && civilian.getHP() > 0) {
                StandardEntity civPosEntity = worldInfo.getEntity(civilian.getPosition());
                if (this.agentInfo.getPosition().equals(civPosEntity.getID())) {
                    continue;
                }

                if (!(civPosEntity instanceof Refuge) && civPosEntity instanceof Building building) {
                    if (building.isBrokennessDefined() && building.getBrokenness() > 0) {
                        List<EntityID> path = this.setFrom(this.agentInfo.getPosition())
                                .setDestination(civPosEntity.getID()).calc().getResult();
                        if (path == null || path.isEmpty()) {
                            messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, null,
                                    civPosEntity.getID(), CommandPolice.ACTION_CLEAR));
                            sendBuildingTimeMap.put(building.getID(), this.agentInfo.getTime());
                        }
                    }
                }
            }
        }

        Collection<Command> heard = this.agentInfo.getHeard();
        Set<EntityID> lastSenderBuilding = new HashSet<>();
        for (Command command : heard) {
            if (command instanceof AKSpeak received
                    && received.getAgentID().getValue() == this.agentInfo.getID().getValue()) {
                byte[] receivedData = received.getContent();
                boolean isRadio = received.getChannel() != 0;
                if (isRadio && receivedData.length > 0) {
                    MessageBuilding messageBuilding = this.getMessageBuilding(messageManager, Boolean.TRUE,
                            received.getAgentID(), receivedData);
                    if (messageBuilding != null) {
                        lastSenderBuilding.add(messageBuilding.getBuildingID());
                    }
                }
            }
        }

        for (EntityID entityID : this.sendBuildingTimeMap.keySet()) {
            if (this.sendBuildingTimeMap.get(entityID) == this.agentInfo.getTime() - 1) {
                Building building = (Building) this.worldInfo.getEntity(entityID);
                if (!lastSenderBuilding.contains(entityID)) {
                    messageManager.addMessage(new CommandPolice(true, StandardMessagePriority.HIGH, null,
                            building.getID(), CommandPolice.ACTION_CLEAR));
                }
            }
        }
    }

    private MessageBuilding getMessageBuilding(@Nonnull MessageManager messageManager, boolean isRadio,
            @Nonnull EntityID senderID, byte[] data) {
        MessageBuilding messageBuilding = null;
        BitStreamReader bitStreamReader = new BitStreamReader(data);
        int messageClassIndex = bitStreamReader.getBits(5);
        if (messageClassIndex <= 0) {
            ConsoleOutput.out(ConsoleOutput.State.WARN, "ignore Message Class Index (0)");
        } else {
            int messageTTL = isRadio ? -1 : bitStreamReader.getBits(3);
            Object[] args = new Object[] { isRadio, senderID.getValue(), messageTTL, bitStreamReader };

            try {
                CommunicationMessage cm = (CommunicationMessage) messageManager.getMessageClass(messageClassIndex)
                        .getConstructor(this.standardMessageArgTypes).newInstance(args);
                if (cm instanceof MessageBuilding) {
                    messageBuilding = (MessageBuilding) cm;
                }
            } catch (IllegalArgumentException | NoSuchMethodException var10) {
                var10.printStackTrace();
            } catch (ReflectiveOperationException var11) {
                var11.printStackTrace();
            }

        }
        return messageBuilding;
    }

    @Override
    public List<EntityID> getResult() {
        return this.result == null ? null : new ArrayList<>(this.result);
    }

    @Override
    public PathPlanning setFrom(EntityID id) {
        this.from = id;
        return this;
    }

    @Override
    public PathPlanning setDestination(Collection<EntityID> targets) {
        this.targets = new ArrayList<>();
        if (targets == null) {
            return this;
        }
        for (EntityID id : targets) {
            if (id == null) {
                continue;
            }
            StandardEntity entity = worldInfo.getEntity(id);
            EntityID areaID = null;
            if (entity instanceof Area) {
                areaID = entity.getID();
            } else if (entity instanceof Human human && human.isPositionDefined()
                    && worldInfo.getEntity(human.getPosition()) instanceof Area) {
                areaID = human.getPosition();
            }
            if (areaID != null && !this.targets.contains(areaID)) {
                this.targets.add(areaID);
            }
        }
        return this;
    }

    @Override
    public double getDistance() {
        double sum = 0.0D;
        List<EntityID> path = this.getResult();

        if (path != null && path.size() > 1) {

            EntityID preID = null, id = null;
            Edge edgeFrom = null, edgeTo = null;
            for (Iterator var5 = path.iterator(); var5.hasNext(); edgeFrom = edgeTo, preID = id) {
                if (preID == null) {
                    preID = (EntityID) var5.next();
                    id = preID;
                    continue;
                }

                id = (EntityID) var5.next();
                StandardEntity preEntity = this.worldInfo.getEntity(preID);
                Area now_Entity = (Area) preEntity;
                if (edgeFrom == null) {
                    edgeFrom = now_Entity.getEdgeTo(id);
                    edgeTo = edgeFrom;
                    Point2D mid_Point1 = this.getMidPoint(edgeFrom);
                    double x = Math.abs(mid_Point1.getX() - agentInfo.getX());
                    double y = Math.abs(mid_Point1.getY() - agentInfo.getY());
                    sum += Math.sqrt((double) (x * x + y * y));
                } else
                    edgeTo = now_Entity.getEdgeTo(id);
                if (edgeTo != null) {
                    Point2D mid_Point1 = this.getMidPoint(edgeFrom);
                    Point2D mid_Point2 = this.getMidPoint(edgeTo);
                    double x = Math.abs(mid_Point1.getX() - mid_Point2.getX());
                    double y = Math.abs(mid_Point1.getY() - mid_Point2.getY());
                    sum += Math.sqrt(x * x + y * y);
                }
            }
        }
        return sum;
    }

    private Point2D getMidPoint(Edge edge) {
        if (edge != null) {
            double midX = (double) (edge.getStartX() + edge.getEndX()) / 2;
            double midY = (double) (edge.getStartY() + edge.getEndY()) / 2;
            return new Point2D(midX, midY);
        }
        return null;
    }

    @Override
    public PathPlanning calc() {
        this.result = null;
        this.resultTarget = null;
        StandardEntity sourceEntity = this.from == null ? null : worldInfo.getEntity(this.from);
        if (!(sourceEntity instanceof Area sourceArea) || this.targets == null || this.targets.isEmpty()) {
            return this;
        }

        List<EntityID> planPath;

        if (previousTarget != null && targets.contains(previousTarget.getID())) {
            Area target = previousTarget;
            planPath = new ArrayList<>(getPath(sourceArea, target));
            result = planPath;
            if (!result.isEmpty()) {
                resultTarget = target.getID();
            }
        }

        if (result == null || result.isEmpty()) {
            targets.sort(new DistanceComparator(worldInfo, agentInfo));
            for (EntityID target1 : targets) {
                StandardEntity targetEntity = worldInfo.getEntity(target1);
                if (!(targetEntity instanceof Area target)) {
                    continue;
                }
                planPath = new ArrayList<>(getPath(sourceArea, target));
                if (!planPath.isEmpty()) {
                    result = planPath;
                    resultTarget = target1;
                    break;
                }
            }
        }

        if (result != null && (result.isEmpty() || !result.getLast().equals(resultTarget))) {
            result = null;
            resultTarget = null;
        }
        return this;
    }

    private List<EntityID> getPath(Area sourceArea, Area target) {
        List<EntityID> path = new ArrayList<>();
        if (sourceArea == null || target == null) {
            return path;
        }
        if (sourceArea.equals(target)) {
            path.add(sourceArea.getID());
            return path;
        }

        boolean repeatPlanning = repeatPlanning(target);
        boolean repeatAStar = !isPositionOnPreviousPath(sourceArea.getID());
        if (repeatAStar || repeatPlanning) {
            previousPath.clear();

            path.addAll(getGraphPath(sourceArea, target));
            if (!path.isEmpty()) {

                path = getAreaPath(sourceArea, target, path);
            }
            if (path.isEmpty() || !path.getFirst().equals(sourceArea.getID())
                    || !path.getLast().equals(target.getID())) {
                path.clear();
            }
            previousTarget = target;
            previousPath = new ArrayList<>(path);
        } else if (previousTarget.equals(target)) {
            int sourceIndex = previousPath.indexOf(sourceArea.getID());
            if (sourceIndex >= 0) {
                path = new ArrayList<>(previousPath.subList(sourceIndex, previousPath.size()));
            }
        }
        return path;
    }

    public List<EntityID> getGraphPath(Area source, Area destination) {
        if (destination == null) {
            return new ArrayList<>();
        }

        Node sourceNode = getNearestNode(source, world.getSelfLocation());
        Node destinationNode = getNearestNode(destination, world.getLocation(destination));
        int extraPathCost = 0;
        if (sourceNode == null || destinationNode == null) {
            return new ArrayList<>();
        }

        extraPathCost += (int) Ruler.getDistance(source.getLocation(worldInfo.getRawWorld()), sourceNode.getPosition());
        extraPathCost += (int) Ruler.getDistance(destination.getLocation(worldInfo.getRawWorld()),
                destinationNode.getPosition());

        boolean findPath = false;

        Set<Node> open = new HashSet<>();

        Set<EntityID> closed = new HashSet<>();
        Node current;

        sourceNode.setG(0);
        sourceNode.setCost(0);
        sourceNode.setDepth(0);
        sourceNode.setParent(null);
        destinationNode.setParent(null);
        open.add(sourceNode);

        if (sourceNode.equals(destinationNode)) {
            pathCost = sourceNode.getCost();
            pathCost += extraPathCost;
            return getPathByEndNode(destinationNode);
        }

        int maxDepth = 0;
        pathCost = -1;

        while ((maxDepth < graph.getNodeSize()) && (open.size() != 0)) {

            current = Collections.min(open);
            pathCost = current.getCost();
            pathCost += extraPathCost;

            if (current.equals(destinationNode)) {
                findPath = true;
                break;
            }

            open.remove(current);
            closed.add(current.getId());

            for (Pair<EntityID, MyEdge> neighbour : current.getNeighbourNodes()) {
                MyEdge neighbourMyEdge = neighbour.second();
                Node neighbourNode = neighbourMyEdge.getOtherNode(current);

                if (!closed.contains(neighbourNode.getId()) && ((neighbourMyEdge.isPassable()) || amIPoliceForce)) {

                    int neighbourG = neighbourMyEdge.getWeight() + current.getG();
                    if (!open.contains(neighbourNode)) {

                        neighbourNode.setParent(current.getId());

                        neighbourNode.setHeuristic(
                                (int) Ruler.getDistance(neighbourNode.getPosition(), destinationNode.getPosition()));
                        neighbourNode.setG(neighbourG);

                        neighbourNode.setCost(neighbourNode.getHeuristic() + neighbourG);
                        neighbourNode.setDepth(current.getDepth() + 1);

                        open.add(neighbourNode);

                        if (neighbourNode.getDepth() > maxDepth) {
                            maxDepth = neighbourNode.getDepth();
                        }

                    } else {

                        if (neighbourNode.getG() > neighbourG) {

                            neighbourNode.setParent(current.getId());
                            neighbourNode.setG(neighbourG);
                            neighbourNode.setCost(neighbourNode.getHeuristic() + neighbourG);
                            neighbourNode.setDepth(current.getDepth() + 1);

                            if (neighbourNode.getDepth() > maxDepth) {
                                maxDepth = neighbourNode.getDepth();
                            }
                        }
                    }
                }
            }
        }

        if (findPath) {
            return getPathByEndNode(destinationNode);
        } else {
            return new ArrayList<>();
        }
    }

    public List<EntityID> getAreaPath(Area sourceArea, Area destinationArea, List<EntityID> path) {
        Node node;
        List<EntityID> areaPath = new ArrayList<>();
        List<EntityID> tempAreaPathList = new ArrayList<>();

        areaPath.add(sourceArea.getID());

        for (int i = path.size() - 1; i >= 0; i--) {
            node = graph.getNode(path.get(i));
            for (EntityID areaId : node.getNeighbourAreaIds()) {
                if (tempAreaPathList.contains(areaId)) {
                    if (!areaPath.contains(areaId)) {
                        areaPath.add(areaId);
                    }
                } else {
                    tempAreaPathList.add(areaId);
                }
            }
        }

        if (!areaPath.contains(destinationArea.getID())) {
            areaPath.add(destinationArea.getID());
        }

        if (!((Area) worldInfo.getEntity(destinationArea.getID())).getNeighbours().contains(sourceArea.getID())
                && areaPath.size() < 3) {
            return new ArrayList<>();
        }

        if (agentInfo.getTime() >= scenarioInfo.getKernelAgentsIgnoreuntil()) {
            areaPath = validatePath(areaPath);
        }
        return areaPath;
    }

    private List<EntityID> validatePath(List<EntityID> path) {
        Edge edge;
        Area area;

        for (int i = 0; i < path.size() - 1; i++) {
            area = (Area) worldInfo.getEntity(path.get(i));
            edge = area.getEdgeTo(path.get(i + 1));
            if (edge == null) {
                // System.out.println(agentInfo.getID() + " time: " + agentInfo.getTime() + " " + path.get(i) + " 到 "
                //         + path.get(i + 1) + " 路径错误!!!");
                // System.out.println("原始路径: " + path);

                return new ArrayList<>(path.subList(0, i + 1));
            }
        }
        return path;
    }

    private Node getNearestNode(Area area, Pair<Integer, Integer> XYPair) {
        Node selected = null;
        int minDistance = Integer.MAX_VALUE;
        int distance;
        List<Node> areaNodes = new ArrayList<>(graph.getAreaNodes(area.getID()));

        for (Node node : areaNodes) {
            if (node.isPassable()) {
                distance = Ruler.getDistance(XYPair.first(), XYPair.second(), node.getPosition().first(),
                        node.getPosition().second());
                if (distance < minDistance) {
                    minDistance = distance;
                    selected = node;
                }
            }
        }

        if (selected == null) {
            for (Node node : areaNodes) {
                distance = Ruler.getDistance(XYPair.first(), XYPair.second(), node.getPosition().first(),
                        node.getPosition().second());
                if (distance < minDistance) {
                    minDistance = distance;
                    selected = node;
                }
            }
        }
        return selected;
    }

    private List<EntityID> getPathByEndNode(Node node) {
        List<EntityID> path = new ArrayList<>();
        Node current = node;
        path.add(current.getId());
        pathCost = 0;

        while (current.getParent() != null) {
            path.add(current.getParent());
            pathCost += world.getDistance(current.getId(), current.getParent());
            current = graph.getNode(current.getParent());
        }
        return path;
    }

    private boolean repeatPlanning(Area target) {
        if (previousTarget == null || !previousTarget.equals(target) || repeatMovingTime > 1) {
            repeatMovingTime = 0;
            return true;
        } else {
            repeatMovingTime++;
            return false;
        }
    }

    private boolean isPositionOnPreviousPath(EntityID position) {
        return previousPath.contains(position);
    }

    public static class DistanceComparator implements Comparator<EntityID> {
        private WorldInfo worldInfo;
        private AgentInfo agentInfo;

        public DistanceComparator(WorldInfo worldInfo, AgentInfo agentInfo) {
            this.worldInfo = worldInfo;
            this.agentInfo = agentInfo;
        }

        @Override
        public int compare(EntityID o1, EntityID o2) {
            int d1 = worldInfo.getDistance(worldInfo.getEntity(o1), agentInfo.me());
            int d2 = worldInfo.getDistance(worldInfo.getEntity(o2), agentInfo.me());
            return Integer.compare(d1, d2);
        }
    }

    public EntityID getResultTarget() {
        return resultTarget;
    }
}
