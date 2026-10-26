package AndroidRoboTeam.module.complex.common;

import java.util.*;

/** Fixed-order STN: release, duration, travel and deadline constraints.
 * Forward propagation is sufficient for this acyclic, lower-bound network. */
public final class TemporalSchedule {
    public static final int UNREACHABLE = 1_000_000;
    @FunctionalInterface public interface Travel { int time(int from, int to); }
    public record Task(int id, int pickup, int end, int release, int duration,
                       int deadline, int followUp) implements java.io.Serializable {
        public Task {
            if (duration < 0 || followUp < 0) throw new IllegalArgumentException("negative duration");
        }
    }
    public record Slot(Task task, int start, int finish) {}
    public record Plan(List<Slot> slots, int finish) {
        public Plan { slots = List.copyOf(slots); }
    }
    private TemporalSchedule() {}

    public static Plan evaluate(List<Task> tasks, int position, int now, Travel travel) {
        long clock = now;
        List<Slot> slots = new ArrayList<>();
        for (Task task : tasks) {
            int movement = travel.time(position, task.pickup());
            if (movement < 0 || movement >= UNREACHABLE) return null;
            long start = Math.max(clock + movement, task.release());
            long finish = start + task.duration();
            if (finish + task.followUp() > task.deadline() || finish > Integer.MAX_VALUE) return null;
            slots.add(new Slot(task, (int) start, (int) finish));
            clock = finish;
            position = task.end();
        }
        return new Plan(slots, (int) clock);
    }

    /** Try every insertion position; recheck ALL existing windows, not only the new task. */
    public static Plan insert(Plan existing, Task task, int position, int now,
                              Travel travel, boolean protectHead) {
        List<Task> tasks = existing.slots().stream().map(Slot::task).toList();
        if (tasks.stream().anyMatch(t -> t.id() == task.id())) return null;
        Plan best = null;
        int first = protectHead && !tasks.isEmpty() ? 1 : 0;
        for (int index = first; index <= tasks.size(); index++) {
            List<Task> trial = new ArrayList<>(tasks);
            trial.add(index, task);
            Plan candidate = evaluate(trial, position, now, travel);
            if (candidate != null && (best == null || candidate.finish() < best.finish())) best = candidate;
        }
        return best;
    }
}
