package AndroidRoboTeam.module.complex.common;

import java.util.*;
import static AndroidRoboTeam.module.complex.common.LayeredRescuePlanner.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

/** Execution constraints for the existing insertion planner; not another solver. */
public final class ExecutionDispatchPolicy {
    public record Candidate(Victim victim, int travel, int service, int followUp) {
        long finish(int now) { return (long) now + travel + service + followUp; }
    }
    public record Arrival(int actor, int travel) {}
    private ExecutionDispatchPolicy() {}

    public static Integer protectedHead(Plan current, Integer executingTarget) {
        if (current.slots().isEmpty()) return executingTarget;
        return current.slots().get(0).task().id();
    }

    public static int rescueDuration(int buriedness, int confirmedWorkers) {
        int workers = Math.max(1, confirmedWorkers);
        return (int) Math.max(0, ((long) buriedness + workers - 1) / workers);
    }

    public static boolean retainedClaimant(int self, Collection<Arrival> claimants, int capacity) {
        return claimants.stream().filter(a -> a.travel() >= 0 && a.travel() < UNREACHABLE)
                .sorted(Comparator.comparingInt(Arrival::travel).thenComparingInt(Arrival::actor))
                .limit(Math.max(0, capacity)).anyMatch(a -> a.actor() == self);
    }

    /** Never assign this process's own role to an uncommitted shadow worker.
     * Ambulance staging also requires a real fire-brigade intention. */
    public static List<Worker> dispatchWorkers(Collection<Worker> known, int self, Role role,
                                               Set<Integer> fresh, Set<Integer> rescueIntentOwners) {
        return known.stream().filter(w -> w.id() == self ||
                (w.role() != role && fresh.contains(w.id()) &&
                (role != Role.TRANSPORT || rescueIntentOwners.contains(w.id()))))
                .sorted(Comparator.comparingInt(Worker::id)).toList();
    }

    /** Mix urgent windows and short feasible trips, rather than letting twelve
     * impossible/remote urgent tasks monopolize the entire candidate pool. */
    public static List<Victim> candidates(Collection<Candidate> known, int now, int limit,
                                          Integer protectedHead, Role role, Set<Integer> confirmedRescues) {
        if (limit < 1) throw new IllegalArgumentException("candidate limit must be positive");
        List<Candidate> feasible = known.stream().filter(c -> c.travel() >= 0 && c.travel() < UNREACHABLE
                && c.finish(now) <= c.victim().deadline()
                && (role == Role.RESCUE ? c.victim().buriedness() > 0 :
                c.victim().needsTransport() && (c.victim().buriedness() == 0 || confirmedRescues.contains(c.victim().id()))))
                .toList();
        Map<Integer, Victim> selected = new LinkedHashMap<>();
        feasible.stream().filter(c -> Objects.equals(c.victim().id(), protectedHead)).findFirst()
                .ifPresent(c -> selected.put(c.victim().id(), c.victim()));
        Comparator<Candidate> urgency = Comparator.comparingInt((Candidate c) -> c.victim().deadline())
                .thenComparingLong(c -> c.finish(now)).thenComparingInt(c -> c.victim().id());
        int urgentQuota = Math.max(1, limit / 3);
        for (Candidate c : feasible.stream().sorted(urgency).toList()) {
            if (selected.size() >= urgentQuota) break;
            selected.put(c.victim().id(), c.victim());
        }
        Comparator<Candidate> efficiency = Comparator.comparingLong((Candidate c) -> c.finish(now))
                .thenComparing(urgency);
        for (Candidate c : feasible.stream().sorted(efficiency).toList()) {
            if (selected.size() >= limit) break;
            selected.put(c.victim().id(), c.victim());
        }
        return List.copyOf(selected.values());
    }

    public static int crewLimit(int buriedness, int heavyThreshold, int maximum) {
        if (buriedness < heavyThreshold) return 1;
        return Math.min(Math.max(1, maximum), Math.max(2, rescueDuration(buriedness, 20)));
    }
    public static int crewLimit(int buriedness, int heavyThreshold, int maximum, int rescueWindow) {
        return Math.min(Math.max(1, maximum), Math.max(crewLimit(buriedness, heavyThreshold, maximum),
                rescueDuration(buriedness, Math.max(1, rescueWindow))));
    }

    /** Only useful, nearby, deterministically bounded additions to an actual crew.
     * Account for work completed by the existing crew during our travel. */
    public static boolean mayAssist(int self, int buriedness, int heavyThreshold, int maximum,
                                     int maxTravel, int minimumSaving, Set<Integer> committed,
                                     int workingNow, Collection<Arrival> available) {
        if (buriedness < heavyThreshold || workingNow < 1 || committed.isEmpty()) return false;
        return mayAssist(self, buriedness, crewLimit(buriedness, heavyThreshold, maximum),
                maxTravel, minimumSaving, committed, workingNow, available);
    }
    public static boolean mayAssist(int self, int buriedness, int desiredCrew, int maxTravel,
                                     int minimumSaving, Set<Integer> committed, int workingNow,
                                     Collection<Arrival> available) {
        if (desiredCrew <= 1 || workingNow < 1 || committed.isEmpty()) return false;
        if (committed.contains(self)) return true;
        int slots = desiredCrew - committed.size();
        if (slots <= 0) return false;
        List<Arrival> additions = available.stream().filter(a -> !committed.contains(a.actor())
                && a.travel() >= 0 && a.travel() <= maxTravel)
                .sorted(Comparator.comparingInt(Arrival::travel).thenComparingInt(Arrival::actor)).toList();
        for (Arrival a : additions.stream().limit(slots).toList()) {
            if (a.actor() != self) continue;
            int remaining = Math.max(0, buriedness - a.travel() * workingNow);
            int withUs = a.travel() + rescueDuration(remaining, workingNow + 1);
            return remaining > 0 && rescueDuration(buriedness, workingNow) - withUs >= minimumSaving;
        }
        return false;
    }
}
