package AndroidRoboTeam.module.complex.common;

import rescuecore2.worldmodel.EntityID;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * Bounded rolling-horizon implementation of the scheduling part of L-DCOP.
 *
 * <p>Instead of enumerating every task permutation, each candidate carries
 * the cost of its current layer and the estimated cost of the dependent layer.
 * A candidate is feasible only when both layers can finish before its time
 * window closes.</p>
 */
public final class LDcopTaskScheduler {

    public enum Layer {
        RESCUE,
        TRANSPORT
    }

    public record Candidate(
            EntityID target,
            Layer layer,
            int travelTime,
            int serviceTime,
            int followUpTime,
            int latestFinish,
            boolean reachable) {

        public Candidate {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(layer, "layer");
            travelTime = Math.max(0, travelTime);
            serviceTime = Math.max(0, serviceTime);
            followUpTime = Math.max(0, followUpTime);
        }

        public int completionTime(int now) {
            long completion = (long) now + travelTime + serviceTime + followUpTime;
            return completion > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) completion;
        }

        public int slack(int now) {
            return latestFinish - completionTime(now);
        }

        public boolean isFeasible(int now) {
            return reachable && completionTime(now) <= latestFinish;
        }
    }

    public record Selection(Candidate candidate, int lockUntil) {
        public EntityID target() {
            return candidate.target();
        }
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparingInt(Candidate::latestFinish)
                    .thenComparingInt(candidate -> candidate.layer() == Layer.TRANSPORT ? 0 : 1)
                    .thenComparingInt(candidate -> candidate.travelTime()
                            + candidate.serviceTime() + candidate.followUpTime())
                    .thenComparingInt(candidate -> candidate.target().getValue());

    private LDcopTaskScheduler() {
    }

    /**
     * Selects the earliest feasible schedule while keeping a valid target for
     * a short period. A locked target may still be pre-empted by a candidate
     * whose slack is smaller by at least {@code preemptionMargin} timesteps.
     */
    public static Selection select(
            Collection<Candidate> candidates,
            EntityID lockedTarget,
            int currentLockUntil,
            int now,
            int targetLockTime,
            int preemptionMargin) {

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        Candidate best = candidates.stream()
                .filter(candidate -> candidate.isFeasible(now))
                .min(CANDIDATE_ORDER)
                .orElse(null);
        if (best == null) {
            return null;
        }

        Candidate locked = null;
        if (lockedTarget != null) {
            locked = candidates.stream()
                    .filter(candidate -> candidate.target().equals(lockedTarget))
                    .filter(candidate -> candidate.isFeasible(now))
                    .findFirst()
                    .orElse(null);
        }

        if (locked != null && now < currentLockUntil && !locked.target().equals(best.target())) {
            boolean urgentPreemption = best.slack(now) + Math.max(0, preemptionMargin) < locked.slack(now);
            if (!urgentPreemption) {
                return new Selection(locked, currentLockUntil);
            }
        }

        if (locked != null && locked.target().equals(best.target()) && now < currentLockUntil) {
            return new Selection(locked, currentLockUntil);
        }

        return new Selection(best, now + Math.max(1, targetLockTime));
    }
}
