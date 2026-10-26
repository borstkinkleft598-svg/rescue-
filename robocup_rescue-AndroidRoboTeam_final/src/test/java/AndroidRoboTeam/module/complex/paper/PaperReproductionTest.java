package AndroidRoboTeam.module.complex.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;
import static AndroidRoboTeam.module.complex.paper.PaperLdcopEngine.*;
import static AndroidRoboTeam.module.complex.common.TemporalSchedule.*;

@Timeout(20)
class PaperReproductionTest {
    private static final String TOKEN = "unit-test-only-token-20261022";
    private final Travel travel = (a,b) -> Math.abs(a-b);

    private List<Outcome> run(List<State> states, int epoch, Policy policy) throws Exception {
        try (PaperRelay relay = new PaperRelay(0, TOKEN, 3000);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Integer> members = states.stream().map(State::actor).sorted().toList();
            List<Future<Outcome>> results = new ArrayList<>();
            for (State state : states) results.add(executor.submit(() -> {
                try (PaperExchange exchange = new SocketPaperExchange(relay.port(), TOKEN, 3000)) {
                    return new PaperLdcopEngine(40, 0, 10000).plan(state, members, epoch, policy, travel, exchange);
                }
            }));
            List<Outcome> values = new ArrayList<>();
            for (Future<Outcome> result : results) values.add(result.get(15, TimeUnit.SECONDS));
            return values;
        }
    }

    @Test void actualSocketBmsAssignsDifferentTasksToTwoActors() throws Exception {
        List<Victim> victims = List.of(new Victim(101, 2, 2, 100, 2, 0), new Victim(102, 18, 2, 100, 2, 20));
        List<Outcome> results = run(List.of(new State(1, 0, victims, List.of()), new State(2, 20, victims, List.of())), 0, Policy.LDCOP);
        Set<Integer> assigned = new HashSet<>();
        for (Outcome o : results) for (Task t : o.queue()) assertTrue(assigned.add(t.id()));
        assertEquals(Set.of(101,102), assigned);
        assertEquals(101, results.get(0).queue().get(0).id());
        assertEquals(102, results.get(1).queue().get(0).id());
    }
    @Test void bmsAndInsertionRepeatUntilMultipleTasksAreScheduled() throws Exception {
        List<Victim> victims = List.of(new Victim(101, 1, 2, 100, 1, 0), new Victim(102, 2, 2, 100, 1, 0),
                new Victim(103, 3, 2, 100, 1, 0));
        Outcome result = run(List.of(new State(1, 0, victims, List.of())), 0, Policy.LDCOP).get(0);
        assertEquals(3, result.queue().size());
        assertEquals(3, result.assignmentRounds());
        assertNotNull(evaluate(result.queue(), 0, 0, travel));
    }
    @Test void layeredPolicyRejectsImpossibleWindowsButDcopBaselineDoesNot() throws Exception {
        State state = new State(1, 0, List.of(new Victim(101, 10, 10, 15, 10, 20)), List.of());
        assertTrue(run(List.of(state), 0, Policy.LDCOP).get(0).queue().isEmpty());
        assertEquals(1, run(List.of(state), 0, Policy.DCOP).get(0).queue().size());
    }
    @Test void allAcceptedTasksAreSynchronizedNotOnlyTheHead() throws Exception {
        List<Victim> victims = List.of(new Victim(101, 1, 2, 100, 1, 0), new Victim(102, 2, 2, 100, 1, 0));
        List<Task> committed = List.of(new Task(101,1,1,0,2,100,1), new Task(102,2,2,0,2,100,1));
        List<Outcome> results = run(List.of(new State(1,0,victims,committed), new State(2,0,victims,List.of())), 1, Policy.LDCOP);
        assertEquals(committed, results.get(0).queue());
        assertTrue(results.get(1).queue().isEmpty());
    }
    @Test void refreshRemovesCompletedAndDeadTasksWithoutDiscardingRemainingOrder() {
        List<Task> previous = List.of(new Task(101,1,1,0,5,100,1), new Task(102,2,2,0,5,100,1));
        List<Task> refreshed = PaperLdcopEngine.refresh(previous,
                List.of(new Victim(102,2,3,100,1,0)), 0, 5, travel);
        assertEquals(1, refreshed.size());
        assertEquals(102, refreshed.get(0).id());
        assertEquals(3, refreshed.get(0).duration());
    }
    @Test void symmetricBmsDoesNotCommitDuplicateOwnership() throws Exception {
        List<Victim> victims = List.of(new Victim(101,2,2,100,1,0), new Victim(102,2,2,100,1,0));
        List<Outcome> results = run(List.of(new State(1,0,victims,List.of()), new State(2,0,victims,List.of())), 0, Policy.LDCOP);
        Set<Integer> unique = new HashSet<>();
        for (Outcome o : results) for (Task task : o.queue()) assertTrue(unique.add(task.id()));
        assertFalse(unique.isEmpty());
    }
    @Test void missingActorFailsInsteadOfCommittingAPartialConsensus() throws Exception {
        try (PaperRelay relay = new PaperRelay(0,TOKEN,100);
             PaperExchange exchange = new SocketPaperExchange(relay.port(),TOKEN,1000)) {
            IOException error = assertThrows(IOException.class,
                    () -> exchange.gather(1,"state",1,List.of(1,2),new Choice(null)));
            assertTrue(error.getMessage().contains("timeout"));
        }
    }
    @Test void wrongTokenCannotJoinTheExperiment() throws Exception {
        try (PaperRelay relay = new PaperRelay(0,TOKEN,1000)) {
            assertThrows(IOException.class, () -> new SocketPaperExchange(relay.port(), "wrong-token",1000));
        }
    }
    @Test void relayExchangesSerializableWholeSchedules() throws Exception {
        List<Outcome> results = run(List.of(new State(1,0,List.of(new Victim(100,1,1,30,2,5)),List.of())),0,Policy.LDCOP);
        assertEquals(100, results.get(0).queue().get(0).id());
    }
}
