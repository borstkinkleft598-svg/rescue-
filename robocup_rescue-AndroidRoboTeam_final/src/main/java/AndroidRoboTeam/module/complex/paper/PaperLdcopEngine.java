package AndroidRoboTeam.module.complex.paper;

import AndroidRoboTeam.module.complex.common.TemporalSchedule;
import java.io.*;
import java.util.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

/** Paper III.E: BMS -> feasible STN insertion -> synchronize all accepted tasks
 * -> erase assigned tasks from layer 1 -> repeat until no task can be added. */
public final class PaperLdcopEngine {
    public enum Policy { LDCOP, DCOP }
    public record Outcome(List<Task> queue, int assignmentRounds, int assignedTasks, int conflicts) {}
    private final int iterations, maxTasks;
    private final double unservedPenalty;
    public PaperLdcopEngine(int iterations, int maxTasks, double unservedPenalty) {
        if (iterations < 1 || unservedPenalty <= 0) throw new IllegalArgumentException("invalid BMS parameters");
        this.iterations = iterations;
        this.maxTasks = maxTasks <= 0 ? Integer.MAX_VALUE : maxTasks;
        this.unservedPenalty = unservedPenalty;
    }

    public Outcome plan(State state, List<Integer> members, int now, Policy policy,
                        Travel travel, PaperExchange exchange) throws IOException {
        int me = state.actor();
        if (!members.equals(members.stream().distinct().sorted().toList()) || !members.contains(me))
            throw new IllegalArgumentException("membership must be sorted/unique and include self");
        Map<Integer, Serializable> shared = exchange.gather(now, "state", me, members, state);
        SortedMap<Integer, Victim> victims = new TreeMap<>();
        Set<Integer> assigned = new HashSet<>();
        for (Serializable object : shared.values()) {
            State peer = (State) object;
            for (Victim victim : peer.victims())
                victims.merge(victim.id(), victim, (a, b) ->
                        a.deadline() < b.deadline() || (a.deadline() == b.deadline() && a.work() <= b.work()) ? a : b);
            if (policy == Policy.LDCOP) for (Task task : peer.queue())
                if (!assigned.add(task.id())) throw new IOException("duplicate committed task " + task.id());
        }
        List<Task> own = new ArrayList<>(policy == Policy.LDCOP ? state.queue() : List.of());
        Set<Integer> remaining = new TreeSet<>(victims.keySet());
        remaining.removeAll(assigned);
        int accepted = 0, conflicts = 0, round = 0;
        int limit = policy == Policy.DCOP ? 1 : remaining.size();
        while (round < limit && !remaining.isEmpty()) {
            List<Bid> bids = new ArrayList<>();
            Plan initial = evaluate(own, state.position(), now, travel);
            if (initial == null) throw new IOException("local STN became inconsistent before assignment");
            if (own.size() < maxTasks) for (int id : remaining) {
                Victim v = victims.get(id);
                if (v.work() <= 0) continue;
                Task task = new Task(id, v.position(), v.position(), now, v.work(),
                        policy == Policy.LDCOP ? v.deadline() : Integer.MAX_VALUE,
                        policy == Policy.LDCOP ? v.transport() : 0);
                Plan candidate = insert(initial, task, state.position(), now, travel, false);
                if (candidate == null) continue;
                double cost = policy == Policy.LDCOP ? candidate.finish() - initial.finish()
                        : travel.time(state.position(), v.position());
                bids.add(new Bid(id, cost, candidate.slots().stream().map(Slot::task).toList()));
            }
            Map<Integer, Bids> allBids = new TreeMap<>();
            exchange.gather(now, round + "/bids", me, members, new Bids(bids))
                    .forEach((id, frame) -> allBids.put(id, (Bids) frame));
            if (allBids.values().stream().allMatch(b -> b.bids().isEmpty())) break;
            Integer selected = DistributedBms.select(me, members, allBids, now, round,
                    iterations, unservedPenalty, exchange);
            Map<Integer, Serializable> decisions = exchange.gather(now, round + "/decisions", me,
                    members, new Choice(selected));
            Map<Integer, Integer> winners = new TreeMap<>();
            for (int actor : members) {
                Integer task = ((Choice) decisions.get(actor)).task();
                if (task == null) continue;
                Bid bid = bid(allBids, actor, task);
                if (bid == null) throw new IOException("BMS selected an edge outside its domain");
                Integer old = winners.get(task);
                if (old != null) conflicts++;
                if (old == null || bid.cost() < bid(allBids, old, task).cost()
                        || (bid.cost() == bid(allBids, old, task).cost() && actor < old)) winners.put(task, actor);
            }
            if (winners.isEmpty()) break;
            for (var winner : winners.entrySet()) {
                remaining.remove(winner.getKey());
                if (winner.getValue() == me) own = new ArrayList<>(bid(allBids, me, winner.getKey()).queue());
            }
            // Exchange the whole accepted STN queue, not only a head-target lease.
            Map<Integer, Serializable> commits = exchange.gather(now, round + "/commit", me, members, new PaperProtocol.Queue(own));
            Set<Integer> committed = new HashSet<>();
            for (Serializable frame : commits.values()) for (Task task : ((PaperProtocol.Queue) frame).tasks())
                if (!committed.add(task.id())) throw new IOException("commit conflict for " + task.id());
            assigned.addAll(committed);
            accepted += winners.size();
            round++;
        }
        return new Outcome(List.copyOf(own), round, accepted, conflicts);
    }
    private static Bid bid(Map<Integer, Bids> bids, int actor, int task) {
        return bids.get(actor).bids().stream().filter(b -> b.task() == task).findFirst().orElse(null);
    }

    /** Update remaining service time without discarding the accepted task order. */
    public static List<Task> refresh(List<Task> old, List<Victim> victims, int position, int now, Travel travel) {
        Map<Integer, Victim> byId = new HashMap<>();
        victims.forEach(v -> byId.put(v.id(), v));
        List<Task> kept = new ArrayList<>();
        for (Task previous : old) {
            Victim v = byId.get(previous.id());
            if (v == null || v.work() <= 0) continue;
            Task updated = new Task(v.id(), v.position(), v.position(), now, v.work(), v.deadline(), v.transport());
            kept.add(updated);
            if (evaluate(kept, position, now, travel) == null) kept.remove(kept.size() - 1);
        }
        return List.copyOf(kept);
    }
}
