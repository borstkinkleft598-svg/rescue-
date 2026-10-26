package AndroidRoboTeam.module.complex.common;

import org.junit.jupiter.api.Test;
import rescuecore2.worldmodel.EntityID;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LDcopTaskSchedulerTest {

    @Test
    void rejectsTasksThatMissTheTimeWindow() {
        var impossible = candidate(1, LDcopTaskScheduler.Layer.TRANSPORT, 5, 2, 3, 9);

        assertNull(LDcopTaskScheduler.select(
                List.of(impossible), null, 0, 0, 5, 4));
    }

    @Test
    void prefersReadyTransportWhenDeadlinesAreEqual() {
        var rescue = candidate(1, LDcopTaskScheduler.Layer.RESCUE, 1, 2, 2, 20);
        var transport = candidate(2, LDcopTaskScheduler.Layer.TRANSPORT, 3, 1, 1, 20);

        var selection = LDcopTaskScheduler.select(
                List.of(rescue, transport), null, 0, 0, 5, 4);

        assertEquals(new EntityID(2), selection.target());
    }

    @Test
    void earlierDeadlineBeatsLayerTieBreaker() {
        var urgentRescue = candidate(1, LDcopTaskScheduler.Layer.RESCUE, 1, 2, 2, 10);
        var transport = candidate(2, LDcopTaskScheduler.Layer.TRANSPORT, 1, 1, 1, 20);

        var selection = LDcopTaskScheduler.select(
                List.of(urgentRescue, transport), null, 0, 0, 5, 4);

        assertEquals(new EntityID(1), selection.target());
    }

    @Test
    void keepsAFeasibleTargetDuringTheLockWindow() {
        var locked = candidate(1, LDcopTaskScheduler.Layer.TRANSPORT, 3, 1, 1, 20);
        var alternative = candidate(2, LDcopTaskScheduler.Layer.TRANSPORT, 2, 1, 1, 19);

        var selection = LDcopTaskScheduler.select(
                List.of(locked, alternative), new EntityID(1), 8, 5, 5, 4);

        assertEquals(new EntityID(1), selection.target());
        assertEquals(8, selection.lockUntil());
    }

    @Test
    void urgentTaskCanPreemptALockedTarget() {
        var locked = candidate(1, LDcopTaskScheduler.Layer.TRANSPORT, 2, 1, 1, 30);
        var urgent = candidate(2, LDcopTaskScheduler.Layer.TRANSPORT, 2, 1, 1, 12);

        var selection = LDcopTaskScheduler.select(
                List.of(locked, urgent), new EntityID(1), 8, 5, 5, 4);

        assertEquals(new EntityID(2), selection.target());
        assertEquals(10, selection.lockUntil());
    }

    @Test
    void expiredLockAllowsAReschedule() {
        var oldTarget = candidate(1, LDcopTaskScheduler.Layer.TRANSPORT, 4, 1, 1, 30);
        var best = candidate(2, LDcopTaskScheduler.Layer.TRANSPORT, 2, 1, 1, 20);

        var selection = LDcopTaskScheduler.select(
                List.of(oldTarget, best), new EntityID(1), 5, 5, 5, 4);

        assertEquals(new EntityID(2), selection.target());
    }

    private static LDcopTaskScheduler.Candidate candidate(
            int id,
            LDcopTaskScheduler.Layer layer,
            int travel,
            int service,
            int followUp,
            int latestFinish) {
        return new LDcopTaskScheduler.Candidate(
                new EntityID(id), layer, travel, service, followUp, latestFinish, true);
    }
}
