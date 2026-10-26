package AndroidRoboTeam.module.complex.common;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

class TemporalScheduleTest {
    private final Travel travel = (a, b) -> Math.abs(a - b);

    @Test void insertionReordersTasksAndPreservesBothDeadlines() {
        Task later = new Task(1, 10, 10, 0, 1, 30, 0);
        Task urgent = new Task(2, 2, 2, 0, 1, 4, 0);
        Plan initial = evaluate(List.of(later), 0, 0, travel);
        Plan inserted = insert(initial, urgent, 0, 0, travel, false);
        assertNotNull(inserted);
        assertEquals(List.of(2, 1), inserted.slots().stream().map(s -> s.task().id()).toList());
    }
    @Test void rejectsAnInsertionThatWouldBreakAnExistingDeadline() {
        Task original = new Task(1, 0, 0, 0, 3, 3, 0);
        Task newcomer = new Task(2, 0, 0, 0, 2, 2, 0);
        assertNull(insert(evaluate(List.of(original), 0, 0, travel), newcomer, 0, 0, travel, false));
    }
    @Test void transportCannotStartBeforeRescueCompletesButTravelCanOverlap() {
        Task transport = new Task(1, 2, 10, 10, 9, 20, 0);
        Plan plan = evaluate(List.of(transport), 0, 0, travel);
        assertEquals(10, plan.slots().get(0).start());
        assertEquals(19, plan.finish());
    }
    @Test void nextTripStartsFromRefugeNotPreviousVictim() {
        Task first = new Task(1, 2, 10, 0, 9, 50, 0);
        Task second = new Task(2, 3, 10, 0, 8, 50, 0);
        Plan plan = evaluate(List.of(first, second), 0, 0, travel);
        assertEquals(18, plan.slots().get(1).start());
        assertEquals(26, plan.finish());
    }
    @Test void includesFollowUpTransportInRescueFeasibility() {
        assertNull(evaluate(List.of(new Task(1, 0, 0, 0, 4, 6, 3)), 0, 0, travel));
    }
    @Test void unreachableIsNotZeroTravelTime() {
        assertNull(evaluate(List.of(new Task(1, 1, 1, 0, 1, 100, 0)), 0, 0, (a,b) -> UNREACHABLE));
    }
    @Test void duplicateInsertionIsRejected() {
        Task task = new Task(1, 1, 1, 0, 1, 100, 0);
        assertNull(insert(evaluate(List.of(task), 0, 0, travel), task, 0, 0, travel, false));
    }
    @Test void deathBoundaryIsStrictAndAnchoredToObservation() {
        assertEquals(14, LDcopWorldPlanner.latestFinish(10, 100, 20));
        assertEquals(10, LDcopWorldPlanner.latestFinish(10, 1, 20));
    }
    @Test void observedZeroDamageDoesNotInventADeathDeadline() {
        assertEquals(Integer.MAX_VALUE, LDcopWorldPlanner.latestFinish(10, 100, 0));
        assertEquals(10, LDcopWorldPlanner.latestFinish(10, 0, 0));
    }
}
