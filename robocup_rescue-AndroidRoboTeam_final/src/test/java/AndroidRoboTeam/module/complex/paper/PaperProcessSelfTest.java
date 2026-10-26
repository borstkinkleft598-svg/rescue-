package AndroidRoboTeam.module.complex.paper;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

/** Two separate JVMs evaluate different graph partitions. Relay holds no algorithm state. */
public final class PaperProcessSelfTest {
    public static void main(String[] args) throws Exception {
        String token = "process-test-only-20261022";
        if (args.length == 2) {
            int actor = Integer.parseInt(args[0]), port = Integer.parseInt(args[1]);
            List<Victim> tasks = List.of(new Victim(101,2,2,100,2,0), new Victim(102,18,2,100,2,20));
            try (PaperExchange exchange = new SocketPaperExchange(port,token,3000)) {
                var outcome = new PaperLdcopEngine(40,0,10000).plan(
                        new State(actor, actor == 1 ? 0 : 20,tasks,List.of()), List.of(1,2),0,
                        PaperLdcopEngine.Policy.LDCOP,(a,b) -> Math.abs(a-b),exchange);
                if (outcome.queue().size() != 1 || outcome.queue().get(0).id() != (actor == 1 ? 101 : 102))
                    throw new AssertionError("unexpected distributed allocation: " + outcome);
                System.out.println("actor=" + actor + " task=" + outcome.queue().get(0).id() + " PASS");
            }
            return;
        }
        try (PaperRelay relay = new PaperRelay(0,token,3000)) {
            List<Process> children = new ArrayList<>();
            try {
                for (int actor : List.of(1,2)) children.add(new ProcessBuilder(
                        Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx128m","-cp",
                        System.getProperty("java.class.path"),PaperProcessSelfTest.class.getName(),
                        Integer.toString(actor),Integer.toString(relay.port())).redirectErrorStream(true).start());
                for (Process child : children) {
                    if (!child.waitFor(15,TimeUnit.SECONDS)) throw new AssertionError("child process timed out");
                    String output = new String(child.getInputStream().readAllBytes());
                    System.out.print(output);
                    if (child.exitValue() != 0) throw new AssertionError("child failed");
                }
            } finally { children.forEach(p -> { if (p.isAlive()) p.destroyForcibly(); }); }
        }
        System.out.println("Cross-JVM BMS/STN/commit synchronization PASS");
    }
}
