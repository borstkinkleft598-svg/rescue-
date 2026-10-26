package AndroidRoboTeam.module.complex.paper;

import kernel.AgentProxy;
import kernel.CommandCollector;
import rescuecore2.config.Config;
import rescuecore2.log.Logger;
import rescuecore2.messages.Command;
import rescuecore2.standard.entities.*;
import rescuecore2.standard.messages.*;
import java.util.*;

/** Experiment-only synchronization: wait for actual agent actions, not a fixed
 * 60-second sleep per timestep. Dead agents, civilians and centres do not block. */
public final class PaperCommandCollector implements CommandCollector {
    private int ignored, maxMillis;
    @Override public void initialise(Config config) {
        ignored = config.getIntValue("kernel.agents.ignoreuntil", 3);
        maxMillis = config.getIntValue("kernel.agents.think-time", 60000);
    }
    @Override public Collection<Command> getAgentCommands(Collection<AgentProxy> agents, int timestep) throws InterruptedException {
        if (timestep >= ignored) {
            Set<AgentProxy> waiting = new HashSet<>();
            for (AgentProxy agent : agents) if (requiresAction((StandardEntity) agent.getControlledEntity())) waiting.add(agent);
            long deadline = System.nanoTime() + maxMillis * 1_000_000L;
            while (!waiting.isEmpty()) {
                waiting.removeIf(a -> a.getAgentCommands(timestep).stream().anyMatch(PaperCommandCollector::isAction));
                if (waiting.isEmpty()) break;
                if (System.nanoTime() >= deadline) {
                    Logger.error("[PAPER-COLLECTOR-TIMEOUT] missing " + waiting.size() + " agent actions at " + timestep);
                    break;
                }
                Thread.sleep(20);
            }
        }
        List<Command> result = new ArrayList<>();
        for (AgentProxy agent : agents) result.addAll(agent.getAgentCommands(timestep));
        return result;
    }
    static boolean requiresAction(StandardEntity entity) {
        return entity instanceof Human h && !(h instanceof Civilian)
                && (!h.isHPDefined() || h.getHP() > 0);
    }
    private static boolean isAction(Command c) {
        return c instanceof AKMove || c instanceof AKRest || c instanceof AKRescue || c instanceof AKLoad
                || c instanceof AKUnload || c instanceof AKClear || c instanceof AKClearArea || c instanceof AKExtinguish;
    }
}
