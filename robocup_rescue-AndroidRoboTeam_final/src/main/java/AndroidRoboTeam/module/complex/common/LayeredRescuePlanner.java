package AndroidRoboTeam.module.complex.common;

import java.util.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

/** Snapshot-based layered assignment. Every process uses only its local knowledge.
 * No shared JVM memory or simulator-side pseudo-communication is assumed. */
public final class LayeredRescuePlanner {
    public enum Role { RESCUE, TRANSPORT }
    public record Worker(int id, Role role, int position, int available) {}
    public record Victim(int id, int position, int refuge, int buriedness,
                         int deadline, boolean needsTransport, int rescueDuration) {
        public Victim(int id, int position, int refuge, int buriedness, int deadline, boolean needsTransport) {
            this(id, position, refuge, buriedness, deadline, needsTransport, buriedness);
        }
        public Victim {
            if (rescueDuration < 0) throw new IllegalArgumentException("negative rescue duration");
        }
    }
    public record Result(Map<Integer, Plan> rescue, Map<Integer, Plan> transport) {}
    private record Offer(int worker, int task, Plan plan, double utility) {}
    private LayeredRescuePlanner() {}

    public static Result plan(Collection<Worker> workers, Collection<Victim> victims,
                              int now, Travel travel, int load, int unload,
                              int maxTasks) {
        return plan(workers, victims, now, travel, load, unload, maxTasks, Map.of(), Map.of());
    }

    public static Result plan(Collection<Worker> workers, Collection<Victim> victims,
                              int now, Travel travel, int load, int unload,
                              int maxTasks, Map<Integer, Integer> rescueOwners,
                              Map<Integer, Integer> transportOwners) {
        return plan(workers, victims, now, travel, load, unload, maxTasks, rescueOwners, transportOwners, Map.of());
    }

    public static Result plan(Collection<Worker> workers, Collection<Victim> victims,
                              int now, Travel travel, int load, int unload,
                              int maxTasks, Map<Integer, Integer> rescueOwners,
                              Map<Integer, Integer> transportOwners, Map<Integer, Integer> executingTargets) {
        List<Worker> rescuers = workers.stream().filter(w -> w.role() == Role.RESCUE)
                .sorted(Comparator.comparingInt(Worker::id)).toList();
        List<Worker> ambulances = workers.stream().filter(w -> w.role() == Role.TRANSPORT)
                .sorted(Comparator.comparingInt(Worker::id)).toList();
        Map<Integer, Victim> byId = new TreeMap<>();
        victims.forEach(v -> byId.put(v.id(), v));
        Map<Integer, Task> rescueTasks = new TreeMap<>();
        for (Victim v : byId.values()) {
            if (v.buriedness() <= 0) continue;
            int tail = 0;
            if (v.needsTransport()) {
                if (v.refuge() < 0 || ambulances.isEmpty()) continue;
                int move = travel.time(v.position(), v.refuge());
                if (move < 0 || move >= UNREACHABLE) continue;
                tail = move + load + unload;
            }
            // One planned rescuer per victim: do not invent extra simultaneous rescuers.
            rescueTasks.put(v.id(), new Task(v.id(), v.position(), v.position(), now,
                    v.rescueDuration(), v.deadline(), tail));
        }
        Map<Integer, Plan> rescue = assign(rescuers, rescueTasks, now, travel, maxTasks, rescueOwners, executingTargets);
        Map<Integer, Integer> releases = new HashMap<>();
        rescue.values().forEach(p -> p.slots().forEach(s -> releases.put(s.task().id(), s.finish())));
        Map<Integer, Task> transportTasks = new TreeMap<>();
        for (Victim v : byId.values()) {
            if (!v.needsTransport() || v.refuge() < 0) continue;
            Integer release = v.buriedness() > 0 ? releases.get(v.id()) : Integer.valueOf(now);
            int movement = travel.time(v.position(), v.refuge());
            if (release == null || movement < 0 || movement >= UNREACHABLE) continue;
            transportTasks.put(v.id(), new Task(v.id(), v.position(), v.refuge(), release,
                    load + movement + unload, v.deadline(), 0));
        }
        Map<Integer, Plan> transport = assign(ambulances, transportTasks, now, travel, maxTasks, transportOwners, executingTargets);
        // Pruning one layer must not leave dangling tasks in the other layer.
        // Repeat until no task is removed; handles even non-metric travel estimates.
        int before, after;
        do {
            before = size(rescue) + size(transport);
            Set<Integer> transported = ids(transport);
            for (Worker worker : rescuers) {
                List<Task> kept = rescue.get(worker.id()).slots().stream().map(Slot::task)
                        .filter(t -> !byId.get(t.id()).needsTransport() || transported.contains(t.id())).toList();
                rescue.put(worker.id(), retainFeasible(kept, worker, now, travel));
            }
            releases.clear();
            rescue.values().forEach(p -> p.slots().forEach(s -> releases.put(s.task().id(), s.finish())));
            for (Worker worker : ambulances) {
                List<Task> kept = new ArrayList<>();
                for (Slot slot : transport.get(worker.id()).slots()) {
                    Task task = slot.task();
                    Victim v = byId.get(task.id());
                    Integer release = v.buriedness() > 0 ? releases.get(v.id()) : Integer.valueOf(now);
                    if (release != null) kept.add(new Task(task.id(), task.pickup(), task.end(), release,
                            task.duration(), task.deadline(), 0));
                }
                transport.put(worker.id(), retainFeasible(kept, worker, now, travel));
            }
            after = size(rescue) + size(transport);
        } while (after < before);
        return new Result(Map.copyOf(rescue), Map.copyOf(transport));
    }

    private static int size(Map<Integer, Plan> plans) {
        return plans.values().stream().mapToInt(p -> p.slots().size()).sum();
    }
    private static Set<Integer> ids(Map<Integer, Plan> plans) {
        Set<Integer> ids = new HashSet<>();
        plans.values().forEach(p -> p.slots().forEach(s -> ids.add(s.task().id())));
        return ids;
    }
    private static Plan retainFeasible(List<Task> tasks, Worker worker, int now, Travel travel) {
        List<Task> kept = new ArrayList<>();
        Plan result = new Plan(List.of(), Math.max(now, worker.available()));
        for (Task task : tasks) {
            kept.add(task);
            Plan candidate = evaluate(kept, worker.position(), Math.max(now, worker.available()), travel);
            if (candidate == null) kept.remove(kept.size() - 1);
            else result = candidate;
        }
        return result;
    }

    private static Map<Integer, Plan> assign(List<Worker> workers, Map<Integer, Task> tasks,
                                              int now, Travel travel, int maxTasks,
                                              Map<Integer, Integer> owners, Map<Integer, Integer> executingTargets) {
        Map<Integer, Plan> plans = new TreeMap<>();
        workers.forEach(w -> plans.put(w.id(), new Plan(List.of(), Math.max(now, w.available()))));
        Set<Integer> remaining = new TreeSet<>(tasks.keySet());
        // Insert live reservations before new offers can consume that owner's capacity.
        // Otherwise even a valid claim could be displaced on every rolling replan.
        for (Worker worker : workers) {
            List<Task> reserved = tasks.values().stream()
                    .filter(t -> Objects.equals(owners.get(t.id()), worker.id()))
                    .filter(t -> !executingTargets.containsKey(worker.id()) || executingTargets.get(worker.id()) == t.id())
                    .sorted(Comparator.comparingInt(Task::deadline).thenComparingInt(Task::id)).toList();
            for (Task task : reserved) {
                Plan previous = plans.get(worker.id());
                if (previous.slots().size() >= maxTasks) break;
                Plan candidate = insert(previous, task, worker.position(),
                        Math.max(now, worker.available()), travel, true);
                if (candidate != null) {
                    plans.put(worker.id(), candidate);
                    remaining.remove(task.id());
                }
            }
        }
        // L-DCOP insertion rounds: select one feasible agent-task insertion at a time.
        // The assignment score is marginal schedule duration; no extra DCOP solver is introduced.
        while (!remaining.isEmpty()) {
            Offer best = null;
            for (Worker worker : workers) {
                Plan previous = plans.get(worker.id());
                if (previous.slots().size() >= maxTasks) continue;
                for (int id : remaining) {
                    if (executingTargets.containsKey(worker.id()) && executingTargets.get(worker.id()) != id) continue;
                    if (owners.containsKey(id) && owners.get(id) != worker.id()) continue;
                    Plan candidate = insert(previous, tasks.get(id), worker.position(),
                            Math.max(now, worker.available()), travel,
                            !previous.slots().isEmpty()
                                    && Objects.equals(owners.get(previous.slots().get(0).task().id()), worker.id()));
                    if (candidate == null) continue;
                    double utility = -(candidate.finish() - previous.finish());
                    Offer offer = new Offer(worker.id(), id, candidate, utility);
                    if (best == null || tasks.get(id).deadline() < tasks.get(best.task()).deadline()
                            || (tasks.get(id).deadline() == tasks.get(best.task()).deadline()
                            && (utility > best.utility() || (utility == best.utility()
                            && (worker.id() < best.worker() || (worker.id() == best.worker() && id < best.task()))))))
                        best = offer;
                }
            }
            if (best == null) break;
            plans.put(best.worker(), best.plan());
            remaining.remove(best.task());
        }
        return plans;
    }
}
