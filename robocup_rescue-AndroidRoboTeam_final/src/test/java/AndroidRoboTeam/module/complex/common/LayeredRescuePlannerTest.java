package AndroidRoboTeam.module.complex.common;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.common.LayeredRescuePlanner.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

class LayeredRescuePlannerTest {
    private final Travel travel = (a, b) -> Math.abs(a - b);
    private Worker fb(int id, int pos) { return new Worker(id, Role.RESCUE, pos, 0); }
    private Worker at(int id, int pos) { return new Worker(id, Role.TRANSPORT, pos, 0); }
    private Victim victim(int id, int pos, int buried, int deadline) {
        return new Victim(id, pos, 10, buried, deadline, true);
    }

    @Test void propagatesActualRescueFinishToTransportRelease() {
        Result result = plan(List.of(fb(1, 0), at(2, 0)), List.of(victim(9, 2, 3, 30)), 0, travel, 1, 1, 3);
        Slot rescue = result.rescue().get(1).slots().get(0);
        Slot transport = result.transport().get(2).slots().get(0);
        assertEquals(rescue.finish(), transport.task().release());
        assertEquals(5, transport.start());
        assertEquals(15, transport.finish());
    }
    @Test void noVirtualAmbulanceWhenThereIsNoRealAmbulance() {
        Result result = plan(List.of(fb(1, 0)), List.of(victim(9, 2, 3, 30)), 0, travel, 1, 1, 3);
        assertTrue(result.rescue().get(1).slots().isEmpty());
    }
    @Test void removesRescueWhenActualAmbulanceCannotArriveBeforeDeath() {
        Result result = plan(List.of(fb(1, 0), at(2, 100)), List.of(victim(9, 2, 3, 20)), 0, travel, 1, 1, 3);
        assertTrue(result.rescue().get(1).slots().isEmpty());
        assertTrue(result.transport().get(2).slots().isEmpty());
    }
    @Test void eachCivilianHasAtMostOneTransportOwner() {
        Result result = plan(List.of(at(1, 0), at(2, 0)), List.of(victim(9, 2, 0, 30)), 0, travel, 1, 1, 3);
        assertEquals(1, result.transport().values().stream().mapToInt(p -> p.slots().size()).sum());
    }
    @Test void plansMultipleTasksAndHonorsCapacityBound() {
        Result result = plan(List.of(at(1, 0)), List.of(victim(9, 2, 0, 100), victim(10, 3, 0, 100),
                victim(11, 4, 0, 100)), 0, travel, 1, 1, 2);
        assertEquals(2, result.transport().get(1).slots().size());
    }
    @Test void honorsReceivedTransportReservation() {
        Result result = plan(List.of(at(1, 0), at(2, 5)), List.of(victim(9, 2, 0, 30)),
                0, travel, 1, 1, 3, Map.of(), Map.of(9, 2));
        assertTrue(result.transport().get(1).slots().isEmpty());
        assertEquals(9, result.transport().get(2).slots().get(0).task().id());
    }
    @Test void buriedTeamMemberNeedsRescueButNoTransport() {
        Result result = plan(List.of(fb(1, 0)), List.of(new Victim(9, 2, -1, 3, 20, false)),
                0, travel, 1, 1, 3);
        assertEquals(1, result.rescue().get(1).slots().size());
    }
    @Test void claimedTaskKeepsCapacityAheadOfNewEarlierDeadlineOffers() {
        Result result = plan(List.of(at(1, 0)),
                List.of(victim(9, 8, 0, 100), victim(10, 1, 0, 50)),
                0, travel, 1, 1, 1, Map.of(), Map.of(9, 1));
        assertEquals(9, result.transport().get(1).slots().get(0).task().id());
    }
    @Test void insertionDoesNotPreemptReservedHeadEvenIfShorter() {
        Result result = plan(List.of(at(1, 0)),
                List.of(victim(9, 8, 0, 100), victim(10, 1, 0, 100)),
                0, travel, 1, 1, 2, Map.of(), Map.of(9, 1));
        List<Slot> slots = result.transport().get(1).slots();
        assertEquals(2, slots.size());
        assertEquals(9, slots.get(0).task().id());
        assertEquals(10, slots.get(1).task().id());
    }
    @Test void infeasibleReservationDoesNotBlockAnotherFeasibleTask() {
        Result result = plan(List.of(at(1, 0)),
                List.of(victim(9, 8, 0, 1), victim(10, 1, 0, 100)),
                0, travel, 1, 1, 1, Map.of(), Map.of(9, 1));
        assertEquals(10, result.transport().get(1).slots().get(0).task().id());
    }
    @Test void sameSnapshotProducesSameAssignmentsRegardlessOfCollectionOrder() {
        List<Worker> workers = List.of(at(1, 0), at(2, 5));
        List<Victim> victims = List.of(victim(9, 2, 0, 60), victim(10, 3, 0, 60));
        Result a = plan(workers, victims, 0, travel, 1, 1, 3);
        Result b = plan(List.of(workers.get(1), workers.get(0)), List.of(victims.get(1), victims.get(0)),
                0, travel, 1, 1, 3);
        assertEquals(a, b);
    }

    @Test void variedScenariosAlwaysPreserveWindowsOwnershipAndLayerDependencies() {
        Random random = new Random(20261021);
        for (int scenario = 0; scenario < 100; scenario++) {
            List<Worker> workers = List.of(fb(1, random.nextInt(20)), fb(2, random.nextInt(20)),
                    at(3, random.nextInt(20)), at(4, random.nextInt(20)));
            List<Victim> victims = new ArrayList<>();
            for (int i = 0; i < 10; i++)
                victims.add(victim(100 + i, random.nextInt(20), random.nextInt(6), 10 + random.nextInt(70)));
            Result result = plan(workers, victims, 0, travel, 1, 1, 3);
            Map<Integer, Integer> rescued = new HashMap<>();
            Set<Integer> transported = new HashSet<>();
            result.rescue().values().forEach(p -> p.slots().forEach(s -> {
                assertNull(rescued.put(s.task().id(), s.finish()), "duplicate rescue owner");
                assertTrue(s.finish() + s.task().followUp() <= s.task().deadline());
            }));
            result.transport().values().forEach(p -> p.slots().forEach(s -> {
                assertTrue(transported.add(s.task().id()), "duplicate transport owner");
                assertTrue(s.start() >= s.task().release());
                assertTrue(s.finish() <= s.task().deadline());
                Victim v = victims.stream().filter(t -> t.id() == s.task().id()).findFirst().orElseThrow();
                if (v.buriedness() > 0) {
                    assertNotNull(rescued.get(v.id()));
                    assertTrue(s.start() >= rescued.get(v.id()));
                }
            }));
            assertTrue(transported.containsAll(rescued.keySet()));
            for (Worker w : workers) {
                Plan p = (w.role() == Role.RESCUE ? result.rescue() : result.transport()).get(w.id());
                assertTrue(p.slots().size() <= 3);
                assertEquals(p, evaluate(p.slots().stream().map(Slot::task).toList(), w.position(), 0, travel));
            }
        }
    }
}
