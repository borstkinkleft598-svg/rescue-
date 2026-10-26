package AndroidRoboTeam.module.complex.common;

import java.util.*;

/** Priority within the transport layer: collect a feasible ready passenger
 * before waiting for speculative future releases. Existing STN checks remain. */
public final class ReadyTransportPolicy {
    public record Pickup(int target, int travel, int finish, int deadline) {}
    private ReadyTransportPolicy() {}

    public static Integer choose(Collection<Pickup> pickups, Integer executing, int switchMargin) {
        List<Pickup> feasible = pickups.stream().filter(p -> p.travel >= 0
                && p.travel < TemporalSchedule.UNREACHABLE && p.finish <= p.deadline).toList();
        Comparator<Pickup> order = Comparator.comparingInt((Pickup p) -> p.deadline - p.finish <= 8 ? 0 : 1)
                .thenComparingInt(Pickup::finish).thenComparingInt(Pickup::deadline).thenComparingInt(Pickup::target);
        Pickup best = feasible.stream().min(order).orElse(null);
        if (best == null) return null;
        Pickup current = feasible.stream().filter(p -> Objects.equals(p.target, executing)).findFirst().orElse(null);
        if (current != null && ((current.deadline - current.finish > 8) == (best.deadline - best.finish > 8))
                && current.finish <= (long) best.finish + Math.max(0, switchMargin)) return current.target;
        return best.target;
    }
}
