package AndroidRoboTeam.module.complex.center;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.MessageUtil;
import adf.core.agent.communication.standard.bundle.centralized.CommandPolice;
import adf.core.agent.communication.standard.bundle.centralized.MessageReport;
import adf.core.agent.communication.standard.bundle.information.MessagePoliceForce;
import adf.core.agent.communication.standard.bundle.information.MessageRoad;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.agent.precompute.PrecomputeData;
import adf.core.component.communication.CommunicationMessage;
import adf.core.component.module.complex.PoliceTargetAllocator;
import es.csic.iiia.bms.Factor;
import es.csic.iiia.bms.Minimize;
import es.csic.iiia.bms.factors.CardinalityFactor;
import es.csic.iiia.bms.factors.CardinalityFactor.CardinalityFunction;
import es.csic.iiia.bms.factors.ProxyFactor;
import es.csic.iiia.bms.factors.SelectorFactor;
import es.csic.iiia.bms.factors.WeightingFactor;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.util.*;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static rescuecore2.standard.entities.StandardEntityURN.*;

public class SEUMaxSumPoliceTargetAllocator extends PoliceTargetAllocator {

    private final static StandardEntityURN URL = POLICE_OFFICE;

    private final static StandardEntityURN AGENT_URL = POLICE_FORCE;

    private final static int ITERATIONS = 100;

    private final static double PENALTY = 300.0;

    private final static EntityID SEARCHING_TASK = new EntityID(-1);

    private final Map<EntityID, EntityID> result = new HashMap<>();

    private Set<EntityID> agents = new HashSet<>();

    private final Set<EntityID> tasks = new HashSet<>();

    private final Set<EntityID> ignored = new HashSet<>();

    private final Map<EntityID, Factor<EntityID>> nodes = new HashMap<>();

    private final BufferedCommunicationAdapter adapter;

    private final Map<EntityID, Double> rates = new HashMap<>();

    private final Set<EntityID> requested = new HashSet<>();

    private final Set<EntityID> received = new HashSet<>();

    public SEUMaxSumPoliceTargetAllocator(
            AgentInfo ai, WorldInfo wi, ScenarioInfo si,
            ModuleManager mm, DevelopData dd) {
        super(ai, wi, si, mm, dd);
        System.out
                .println("[DCOP DEBUG] SEUMaxSumPoliceTargetAllocator 构造函数被调用，代理ID: " + ai.getID() + "，类型: " + ai.me());

        this.adapter = new BufferedCommunicationAdapter();

    }

    @Override
    public Map<EntityID, EntityID> getResult() {
        return this.result;
    }

    @Override
    public PoliceTargetAllocator calc() {

        this.result.clear();
        if (this.agents.isEmpty()) {

            this.initializeAgents();

        }

        if (!this.have2allocate()) {

            return this;
        }

        this.initializeTasks();

        if (this.tasks.isEmpty()) {
            for (EntityID agent : this.agents) {
                this.result.put(agent, null);
            }

            return this;
        }

        this.initializeFactorGraph();

        for (int i = 0; i < ITERATIONS; ++i) {
            this.nodes.values().stream().forEach(Factor::run);
            this.adapter.execute(this.nodes);
        }

        for (EntityID agent : this.agents) {
            final Factor<EntityID> node = this.nodes.get(agent);
            EntityID task = selectTask((ProxyFactor<EntityID>) node);
            if (task == null || task.equals(SEARCHING_TASK)) {
                task = null;
            }
            this.result.put(agent, task);
        }

        int n = 0;
        for (EntityID id : this.agents) {
            if (this.result.get(id) != null) {
                ++n;
            }
        }

        return this;
    }

    @Override
    public PoliceTargetAllocator updateInfo(MessageManager mm) {
        super.updateInfo(mm);

        if (this.getCountUpdateInfo() >= 2) {
            return this;
        }

        this.received.clear();

        final Collection<CommunicationMessage> rmessages = mm.getReceivedMessageList(MessageRoad.class);
        for (CommunicationMessage tmp : rmessages) {
            MessageRoad message = (MessageRoad) tmp;
            MessageUtil.reflectMessage(this.worldInfo, message);
        }

        final Collection<CommunicationMessage> pfmessages = mm.getReceivedMessageList(MessagePoliceForce.class);
        for (CommunicationMessage tmp : pfmessages) {
            MessagePoliceForce message = (MessagePoliceForce) tmp;
            MessageUtil.reflectMessage(this.worldInfo, message);

            final EntityID id = message.getAgentID();
            this.received.add(id);
            Human pf = (Human) this.worldInfo.getEntity(id);
            pf.undefineX();
            pf.undefineY();
        }

        final Collection<CommunicationMessage> pfcommands = mm.getReceivedMessageList(CommandPolice.class);
        for (CommunicationMessage tmp : pfcommands) {
            CommandPolice command = (CommandPolice) tmp;
            if (!command.isBroadcast()) {
                continue;
            }
            if (!command.isTargetIDDefined()) {
                continue;
            }
            if (command.getAction() != CommandPolice.ACTION_CLEAR) {
                continue;
            }

            final Set<EntityID> requestedTasks = this.normalizeTaskTargets(command.getTargetID());
            this.requested.addAll(requestedTasks);
            this.ignored.removeAll(requestedTasks);
        }

        final Collection<CommunicationMessage> repmessages = mm.getReceivedMessageList(MessageReport.class);
        for (CommunicationMessage tmp : repmessages) {
            MessageReport message = (MessageReport) tmp;
            if (message.isDone() && message.isFromIDDefined()) {
                final Set<EntityID> completedTasks = this.normalizeTaskTargets(message.getFromID());
                this.ignored.addAll(completedTasks);
                this.requested.removeAll(completedTasks);
            }
        }

        return this;
    }

    @Override
    public PoliceTargetAllocator resume(PrecomputeData pd) {
        super.resume(pd);

        if (this.getCountResume() >= 2) {
            return this;
        }

        final Map<EntityID, Double> areas = new HashMap<>();

        this.worldInfo.getEntitiesOfType(ROAD, HYDRANT)
                .stream()
                .map(Area.class::cast)
                .forEach(a -> areas.put(a.getID(), computeArea(a)));

        final double max = areas.values()
                .stream()
                .max(Double::compare)
                .orElse(1.0);

        for (EntityID id : areas.keySet()) {
            final double area = areas.get(id);
            this.rates.put(id, area / max);
        }

        return this;
    }

    @Override
    public PoliceTargetAllocator preparate() {
        super.preparate();

        if (this.getCountPreparate() >= 2) {
            return this;
        }

        final Map<EntityID, Double> areas = new HashMap<>();

        this.worldInfo.getEntitiesOfType(ROAD, HYDRANT)
                .stream()
                .map(Area.class::cast)
                .forEach(a -> areas.put(a.getID(), computeArea(a)));

        final double max = areas.values()
                .stream()
                .max(Double::compare)
                .orElse(1.0);

        for (EntityID id : areas.keySet()) {
            final double area = areas.get(id);
            this.rates.put(id, area / max);
        }

        return this;
    }

    private static double computeArea(Area area) {
        return GeometryTools2D.computeArea(
                GeometryTools2D.vertexArrayToPoints(area.getApexList()));
    }

    private boolean have2allocate() {
        System.out.println("[DCOP DEBUG] 检查分配条件:");

        boolean centersExist = this
                .allCentersExists();
        System.out.println("[DCOP DEBUG] - allCentersExists: " + centersExist);
        if (!centersExist) {
            System.out.println("[DCOP DEBUG] 返回false: 中心不存在");
            return false;
        }

        final int nAgents = this.agents.size();
        final int nReceived = this.received
                .size();
        System.out.println("[DCOP DEBUG] - agents.size: " + nAgents);
        System.out.println("[DCOP DEBUG] - received.size: " + nReceived);
        if (nReceived != nAgents) {
            System.out.println("[DCOP DEBUG] 收到消息数量(" + nReceived + ") != 代理数量(" + nAgents + ")，继续使用当前 worldInfo 进行分配");
        }

        final int lowest = this.worldInfo.getEntityIDsOfType(URL)
                .stream()
                .mapToInt(EntityID::getValue)
                .min().orElse(-1);

        final int me = this.agentInfo.getID().getValue();
        final int time = this.agentInfo.getTime();
        final int ignored = this.scenarioInfo.getKernelAgentsIgnoreuntil();

        System.out.println("[DCOP DEBUG] - 最低ID警察办公室: " + lowest);
        System.out.println("[DCOP DEBUG] - 当前代理ID: " + me);
        System.out.println("[DCOP DEBUG] - 当前时间: " + time);
        System.out.println("[DCOP DEBUG] - 忽略截止时间: " + ignored);

        boolean result = time >= ignored
                && me == lowest;
        System.out.println("[DCOP DEBUG] 最终条件: time >= ignored && me == lowest = " + result);
        System.out.println("[DCOP DEBUG]   time >= ignored: " + (time >= ignored));
        System.out.println("[DCOP DEBUG]   me == lowest: " + (me == lowest));

        return result;
    }

    private boolean allCentersExists() {
        final int fss = this.scenarioInfo.getScenarioAgentsFs();
        final int pos = this.scenarioInfo.getScenarioAgentsPo();
        final int acs = this.scenarioInfo.getScenarioAgentsAc();

        System.out.println("[DCOP DEBUG] 检查中心存在性:");
        System.out.println("[DCOP DEBUG] - 消防站数量(fss): " + fss);
        System.out.println("[DCOP DEBUG] - 警察办公室数量(pos): " + pos);
        System.out.println("[DCOP DEBUG] - 救护车中心数量(acs): " + acs);

        boolean result = fss > 0 && pos > 0 && acs > 0;
        System.out.println("[DCOP DEBUG] 中心存在性结果: " + result + " (fss>0 && pos>0 && acs>0)");

        return result;
    }

    private void initializeAgents() {
        final Collection<EntityID> tmp = this.worldInfo.getEntityIDsOfType(AGENT_URL);
        this.agents = new HashSet<>(tmp);
    }

    private void initializeTasks() {
        this.tasks.clear();

        this.worldInfo.getEntitiesOfType(POLICE_FORCE)
                .stream()
                .map(Human.class::cast)
                .filter(Human::isPositionDefined)
                .map(Human::getPosition)
                .flatMap(this::extractTasks)
                .forEach(this.tasks::add);

        this.tasks.addAll(this.requested);
        this.tasks.removeAll(this.ignored);
    }

    private Stream<EntityID> extractTasks(EntityID position) {
        StandardEntity tmp = this.worldInfo.getEntity(position);
        if (!Area.class.isInstance(tmp)) {
            return Stream.empty();
        }
        if (!Road.class.isInstance(tmp) || this.ignored.contains(position)) {
            final Area area = (Area) tmp;
            return area.getNeighbours()
                    .stream()
                    .map(this.worldInfo::getEntity)
                    .filter(Road.class::isInstance)
                    .map(StandardEntity::getID)
                    .filter(id -> !this.ignored.contains(id));
        }

        return Stream.of(position);
    }

    private void initializeFactorGraph() {
        this.nodes.clear();
        this.initializeVariableNodes(this.agents);
        this.initializeFactorNodes(this.tasks);
        this.connectNodes(this.agents, this.tasks);
    }

    private Set<EntityID> normalizeTaskTargets(EntityID target) {
        final LinkedHashSet<EntityID> normalized = new LinkedHashSet<>();
        this.collectTaskTargets(target, normalized);
        return normalized;
    }

    private void collectTaskTargets(EntityID target, Set<EntityID> normalized) {
        if (target == null) {
            return;
        }

        final StandardEntity entity = this.worldInfo.getEntity(target);
        if (entity == null) {
            return;
        }

        if (entity instanceof Blockade) {
            final Blockade blockade = (Blockade) entity;
            if (blockade.isPositionDefined()) {
                this.collectTaskTargets(blockade.getPosition(), normalized);
            }
            return;
        }

        if (entity instanceof Human) {
            final Human human = (Human) entity;
            if (human.isPositionDefined()) {
                this.collectTaskTargets(human.getPosition(), normalized);
            }
            return;
        }

        if (entity instanceof Road) {
            normalized.add(entity.getID());
            return;
        }

        if (entity instanceof Area) {
            final Area area = (Area) entity;
            area.getNeighbours()
                    .stream()
                    .map(this.worldInfo::getEntity)
                    .filter(Road.class::isInstance)
                    .map(StandardEntity::getID)
                    .forEach(normalized::add);
        }
    }

    private void initializeVariableNodes(Collection<EntityID> ids) {
        for (EntityID id : ids) {
            final Factor<EntityID> tmp = new BMSSelectorFactor<>();
            final WeightingFactor<EntityID> vnode = new WeightingFactor<>(tmp);
            vnode.setMaxOperator(new Minimize());
            vnode.setIdentity(id);
            vnode.setCommunicationAdapter(this.adapter);
            this.nodes.put(id, vnode);
        }
    }

    private void initializeFactorNodes(Collection<EntityID> ids) {
        for (EntityID id : ids) {
            final CardinalityFactor<EntityID> fnode = new BMSCardinalityFactor<>();
            final CardinalityFunction func = new CardinalityFunction() {
                @Override
                public double getCost(int nActiveVariables) {
                    return SEUMaxSumPoliceTargetAllocator.this.computePenalty(id, nActiveVariables);
                }
            };
            fnode.setFunction(func);

            fnode.setMaxOperator(new Minimize());
            fnode.setIdentity(id);
            fnode.setCommunicationAdapter(this.adapter);
            this.nodes.put(id, fnode);
        }
    }

    private void connectNodes(
            Collection<EntityID> vnodeids, Collection<EntityID> fnodeids) {
        for (EntityID vnodeid : vnodeids) {

            final List<EntityID> closer = fnodeids
                    .stream()
                    .sorted((i1, i2) -> {
                        final double d1 = this.worldInfo.getDistance(i1, vnodeid);
                        final double d2 = this.worldInfo.getDistance(i2, vnodeid);
                        return Double.compare(d1, d2);
                    })
                    .collect(toList());

            for (int i = 0; i < Math.min(3, closer.size()); ++i) {
                final EntityID fnodeid = closer.get(i);
                WeightingFactor<EntityID> vnode = (WeightingFactor<EntityID>) this.nodes.get(vnodeid);
                vnode.addNeighbor(fnodeid);

                Factor<EntityID> fnode = this.nodes.get(fnodeid);
                fnode.addNeighbor(vnodeid);

                final double penalty = this.computePenalty(vnodeid, fnodeid);
                vnode.setPotential(fnodeid, penalty);
            }
        }
    }

    private static EntityID selectTask(ProxyFactor<EntityID> proxy) {
        final SelectorFactor<EntityID> selector = (SelectorFactor<EntityID>) proxy.getInnerFactor();
        return selector.select();
    }

    private double computePenalty(EntityID agent, EntityID task) {
        if (task.equals(SEARCHING_TASK)) {
            return 0.0;
        }

        final double d = this.worldInfo.getDistance(agent, task);
        return d / (42000.0 / 1.5);
    }

    private double computePenalty(EntityID task, int nAgents) {
        if (task.equals(SEARCHING_TASK)) {
            return 0.0;
        }

        final StandardEntity standardEntity = this.worldInfo.getEntity(task);
        if (nAgents == 0) {
            return PENALTY;
        }

        final double nLeasts = 1.0;
        final double ratio = Math.min((double) nAgents, nLeasts) / nLeasts;

        double rate = this.rates.getOrDefault(task, 1.0);

        boolean isEntrance = false;
        if (standardEntity instanceof Road) {
            final Road entity = (Road) standardEntity;
            isEntrance = entity.getNeighbours()
                    .stream()
                    .map(this.worldInfo::getEntity)
                    .anyMatch(Building.class::isInstance);
        } else if (standardEntity instanceof Area) {
            isEntrance = ((Area) standardEntity).getNeighbours()
                    .stream()
                    .map(this.worldInfo::getEntity)
                    .anyMatch(Building.class::isInstance);
        }

        if (isEntrance) {
            rate = 1.5;
        }

        if (this.requested.contains(task)) {
            rate = 2.0;
        }

        return PENALTY * rate * (1.0 - Math.pow(ratio, 2.0));
    }
}
