package AndroidRoboTeam.module.complex.paper;

import AndroidRoboTeam.module.complex.common.TemporalSchedule.Task;
import java.io.Serializable;
import java.util.*;

/** Experiment wire format. The relay never evaluates these algorithm payloads. */
public final class PaperProtocol {
    private PaperProtocol() {}
    public record Node(String kind, int id) implements Serializable {}
    public record Signal(Node from, Node to, double value) implements Serializable {}
    public record Victim(int id, int position, int work, int deadline, int transport, int refuge) implements Serializable {}
    public record State(int actor, int position, List<Victim> victims, List<Task> queue) implements Serializable {
        public State { victims = List.copyOf(victims); queue = List.copyOf(queue); }
    }
    public record Bid(int task, double cost, List<Task> queue) implements Serializable {
        public Bid { queue = List.copyOf(queue); }
    }
    public record Bids(List<Bid> bids) implements Serializable {
        public Bids { bids = List.copyOf(bids); }
    }
    public record Signals(List<Signal> signals) implements Serializable {
        public Signals { signals = List.copyOf(signals); }
    }
    public record Choice(Integer task) implements Serializable {}
    public record Queue(List<Task> tasks) implements Serializable {
        public Queue { tasks = List.copyOf(tasks); }
    }
    public record Request(int epoch, String phase, int actor, List<Integer> members, Serializable payload) implements Serializable {}
    public record Reply(Map<Integer, Serializable> values, String error) implements Serializable {}
}
