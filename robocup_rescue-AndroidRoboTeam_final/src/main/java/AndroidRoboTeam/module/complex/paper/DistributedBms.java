package AndroidRoboTeam.module.complex.paper;

import AndroidRoboTeam.module.complex.center.BMSCardinalityFactor;
import AndroidRoboTeam.module.complex.center.BMSSelectorFactor;
import es.csic.iiia.bms.*;
import es.csic.iiia.bms.factors.*;
import java.io.*;
import java.util.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

/** A real graph partition: only this actor's selector and owned task factors
 * are evaluated here. All remote messages cross PaperExchange barriers. */
public final class DistributedBms {
    private record Link(Node from, Node to) {}
    private DistributedBms() {}

    public static Integer select(int me, List<Integer> members, Map<Integer, Bids> allBids,
            int epoch, int assignmentRound, int iterations, double unservedPenalty,
            PaperExchange exchange) throws IOException {
        Map<Node, Factor<Node>> local = new LinkedHashMap<>();
        List<Signal> outgoing = new ArrayList<>();
        Map<Link, Double> last = new HashMap<>();
        CommunicationAdapter<Node> adapter = (value, from, to) -> {
            if (Double.isNaN(value)) throw new IllegalStateException("NaN BMS message");
            Link link = new Link(from, to);
            double previous = last.getOrDefault(link, 0.0);
            double damped = Double.isFinite(value) && Double.isFinite(previous) ? 0.5 * value + 0.5 * previous : value;
            last.put(link, damped);
            outgoing.add(new Signal(from, to, damped));
        };
        BMSSelectorFactor<Node> selector = new BMSSelectorFactor<>();
        WeightingFactor<Node> variable = new WeightingFactor<>(selector);
        Node variableId = new Node("variable", me), idle = new Node("idle", me);
        configure(variable, variableId, adapter);
        local.put(variableId, variable);
        SortedSet<Integer> tasks = new TreeSet<>();
        allBids.values().forEach(b -> b.bids().forEach(e -> tasks.add(e.task())));
        for (int task : tasks) if (owner(task, members) == me) {
            BMSCardinalityFactor<Node> factor = new BMSCardinalityFactor<>();
            // Explicit modeling assumption: at most one scheduled owner per rescue task.
            factor.setFunction(n -> n == 0 ? unservedPenalty : n == 1 ? 0 : 1e12);
            Node id = new Node("task", task);
            configure(factor, id, adapter);
            for (int worker : members)
                if (allBids.get(worker).bids().stream().anyMatch(e -> e.task() == task))
                    factor.addNeighbor(new Node("variable", worker));
            local.put(id, factor);
        }
        BMSCardinalityFactor<Node> idleFactor = new BMSCardinalityFactor<>();
        idleFactor.setFunction(n -> 0);
        configure(idleFactor, idle, adapter);
        idleFactor.addNeighbor(variableId);
        local.put(idle, idleFactor);
        for (Bid bid : allBids.get(me).bids()) {
            Node task = new Node("task", bid.task());
            variable.addNeighbor(task);
            // Small reproducible perturbation avoids symmetric ties; it is not an ADVP solver.
            double tie = members.indexOf(me) * 1e-7 * (1 + tasks.headSet(bid.task()).size());
            variable.setPotential(task, bid.cost() + tie);
        }
        variable.addNeighbor(idle);
        variable.setPotential(idle, 0);
        for (int i = 0; i < iterations; i++) {
            local.values().forEach(Factor::run);
            Map<Integer, Serializable> frames = exchange.gather(epoch, assignmentRound + "/bms/" + i,
                    me, members, new Signals(outgoing));
            outgoing.clear();
            for (Serializable frame : frames.values()) {
                for (Signal message : ((Signals) frame).signals()) {
                    Factor<Node> recipient = local.get(message.to());
                    if (recipient != null && recipient.getNeighbors().contains(message.from()))
                        recipient.receive(message.value(), message.from());
                }
            }
        }
        Node choice = selector.select();
        return choice == null || !choice.kind().equals("task") ? null : choice.id();
    }
    private static int owner(int task, List<Integer> members) {
        return members.get(Math.floorMod(task, members.size()));
    }
    private static void configure(Factor<Node> factor, Node id, CommunicationAdapter<Node> adapter) {
        factor.setIdentity(id);
        factor.setMaxOperator(new Minimize());
        factor.setCommunicationAdapter(adapter);
    }
}
