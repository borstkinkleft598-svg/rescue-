package AndroidRoboTeam.module.complex.common;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.common.ExecutionDispatchPolicy.*;
import static AndroidRoboTeam.module.complex.common.LayeredRescuePlanner.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

class ExecutionDispatchPolicyTest {
    private Victim v(int id, int buried, int deadline) { return new Victim(id, 2, 10, buried, deadline, true); }

    @Test void emptyScheduleAndNoExecutionHasNoProtectedHead() {
        assertNull(protectedHead(new Plan(List.of(),0),null));
        assertEquals(9,protectedHead(new Plan(List.of(),0),9));
        Task task = new Task(7,0,0,0,1,10,0);
        assertEquals(7,protectedHead(new Plan(List.of(new Slot(task,0,1)),1),9));
    }

    @Test void noPhantomSameRoleWorkerCanTakeOurTasks() {
        var workers = List.of(new Worker(1, Role.RESCUE, 0, 0), new Worker(2, Role.RESCUE, 0, 0),
                new Worker(3, Role.TRANSPORT, 0, 0));
        assertEquals(List.of(workers.get(0), workers.get(2)), dispatchWorkers(workers, 1, Role.RESCUE, Set.of(2, 3), Set.of()));
    }
    @Test void simultaneousClaimsConvergeInsteadOfEveryoneKeepingTheirOwnReservation() {
        List<Arrival> arrivals = List.of(new Arrival(1,5),new Arrival(2,1),new Arrival(3,1),new Arrival(4,2));
        assertTrue(retainedClaimant(2,arrivals,2));
        assertTrue(retainedClaimant(3,arrivals,2));
        assertFalse(retainedClaimant(1,arrivals,2));
        assertFalse(retainedClaimant(4,arrivals,2));
        assertFalse(retainedClaimant(2,arrivals,0));
    }
    @Test void transportNeedsFreshRealRescueIntentRatherThanLocallyInventedAssignment() {
        var workers = List.of(new Worker(1, Role.TRANSPORT, 0, 0), new Worker(2, Role.RESCUE, 0, 0));
        assertEquals(List.of(workers.get(0)), dispatchWorkers(workers, 1, Role.TRANSPORT, Set.of(2), Set.of()));
        assertEquals(workers, dispatchWorkers(workers, 1, Role.TRANSPORT, Set.of(2), Set.of(2)));
        assertEquals(List.of(workers.get(0)), dispatchWorkers(workers, 1, Role.TRANSPORT, Set.of(), Set.of(2)));
    }
    @Test void zeroHelpersNeverInventsParallelRescueSpeed() {
        assertEquals(59, rescueDuration(59, 0));
        assertEquals(30, rescueDuration(59, 2));
        assertEquals(0, rescueDuration(0, 3));
    }
    @Test void ambulanceDoesNotStageForUnconfirmedBuriedVictim() {
        var choices = List.of(new Candidate(v(1, 60, 200), 1, 5, 0), new Candidate(v(2, 0, 200), 2, 5, 0));
        assertEquals(List.of(v(2, 0, 200)), candidates(choices, 0, 12, null, Role.TRANSPORT, Set.of()));
        assertEquals(2, candidates(choices, 0, 12, null, Role.TRANSPORT, Set.of(1)).size());
    }
    @Test void rescuePoolDoesNotFillWithUnburiedTransportTasks() {
        assertTrue(candidates(List.of(new Candidate(v(1, 0, 100), 1, 1, 1)), 0, 12,
                null, Role.RESCUE, Set.of()).isEmpty());
    }
    @Test void impossibleUrgentTasksCannotExhaustCandidateCap() {
        List<Candidate> choices = new ArrayList<>();
        for (int i=0;i<12;i++) choices.add(new Candidate(v(i, 30, 10), 20, 30, 5));
        choices.add(new Candidate(v(100, 1, 100), 1, 1, 1));
        assertEquals(List.of(v(100, 1, 100)), candidates(choices, 0, 12, null, Role.RESCUE, Set.of()));
    }
    @Test void currentFeasibleHeadSurvivesCandidateTruncation() {
        List<Candidate> choices = new ArrayList<>();
        for (int i=0;i<20;i++) choices.add(new Candidate(v(i, 1, 100+i), i+1, 1, 1));
        var result = candidates(choices, 0, 3, 19, Role.RESCUE, Set.of());
        assertEquals(3, result.size());
        assertTrue(result.stream().anyMatch(v -> v.id()==19));
    }
    @Test void protectedHeadDoesNotResurrectInfeasibleTask() {
        assertTrue(candidates(List.of(new Candidate(v(1, 30, 5), 3, 30, 0)), 0, 3,
                1, Role.RESCUE, Set.of()).isEmpty());
    }
    @Test void candidateSelectionIsDeterministicAndIncludesShorterTrips() {
        List<Candidate> choices = new ArrayList<>();
        for(int i=0;i<15;i++) choices.add(new Candidate(v(i, 1, 200+i), 50-i, 1, 1));
        var a=candidates(choices,0,6,null,Role.RESCUE,Set.of());
        Collections.reverse(choices);
        assertEquals(a,candidates(choices,0,6,null,Role.RESCUE,Set.of()));
        assertTrue(a.stream().anyMatch(v -> v.id()==0));
        assertTrue(a.stream().anyMatch(v -> v.id()==14));
    }
    @Test void smallTasksDoNotCallForLargeRemoteCrews() {
        assertEquals(1,crewLimit(28,40,3));
        assertEquals(3,crewLimit(59,40,3));
        assertEquals(3,crewLimit(500,40,3));
    }
    @Test void nearbyUsefulHelperCanJoinHeavyVictim() {
        assertTrue(mayAssist(2,59,40,3,8,4,Set.of(1),1,List.of(new Arrival(2,0))));
    }
    @Test void urgentSmallTaskCanReceiveHelpInsteadOfBeingBlockedByHeavyThreshold() {
        assertEquals(2,crewLimit(20,40,3,10));
        assertEquals(1,crewLimit(20,40,3,100));
        assertTrue(mayAssist(2,20,2,8,1,Set.of(1),1,List.of(new Arrival(2,0))));
    }
    @Test void remoteHelpersCannotExceedCrewCapacity() {
        assertFalse(mayAssist(4,59,40,3,8,4,Set.of(1),1,
                List.of(new Arrival(2,0),new Arrival(3,0),new Arrival(4,0))));
        assertFalse(mayAssist(4,59,40,3,8,4,Set.of(1,2,3),1,List.of(new Arrival(4,0))));
    }
    @Test void noUnconfirmedOrLateHelpIsCountedAsUseful() {
        assertFalse(mayAssist(2,59,40,3,8,4,Set.of(1),0,List.of(new Arrival(2,0))));
        assertFalse(mayAssist(2,59,40,3,8,4,Set.of(1),1,List.of(new Arrival(2,20))));
        assertFalse(mayAssist(2,40,40,6,100,1,Set.of(1),2,List.of(new Arrival(2,20))));
    }
    @Test void tooLittlePredictedSavingDoesNotDivertAnAgent() {
        assertFalse(mayAssist(2,59,40,3,8,30,Set.of(1),1,List.of(new Arrival(2,0))));
    }
    @Test void counterpartCommitmentCannotBeSilentlyReassigned() {
        var result=plan(List.of(new Worker(1,Role.TRANSPORT,0,0), new Worker(2,Role.TRANSPORT,0,0)),
                List.of(v(9,0,100),v(10,0,100)),0,(a,b)->Math.abs(a-b),1,1,3,
                Map.of(),Map.of(9,2),Map.of(2,9));
        assertEquals(List.of(9), result.transport().get(2).slots().stream().map(s->s.task().id()).toList());
        assertEquals(List.of(10), result.transport().get(1).slots().stream().map(s->s.task().id()).toList());
    }
    @Test void confirmedCooperationShortensActualLayerReleaseButDoesNotRemoveDependency() {
        Victim victim = new Victim(9,2,10,59,100,true,rescueDuration(59,2));
        var result=plan(List.of(new Worker(1,Role.RESCUE,0,0),new Worker(2,Role.TRANSPORT,0,0)),
                List.of(victim),0,(a,b)->Math.abs(a-b),1,1,3);
        assertEquals(32,result.rescue().get(1).slots().get(0).finish());
        assertEquals(32,result.transport().get(2).slots().get(0).task().release());
    }
}
