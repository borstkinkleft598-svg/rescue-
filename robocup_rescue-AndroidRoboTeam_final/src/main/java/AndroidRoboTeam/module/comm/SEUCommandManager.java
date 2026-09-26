package AndroidRoboTeam.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.info.AgentInfo;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.worldmodel.EntityID;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SEUCommandManager {

    private static final Map<Integer, SEUCommandManager> INSTANCES = new ConcurrentHashMap<>();

    private final Map<String, QueuedCommand> commandQueue = new HashMap<>();

    private final Map<String, Integer> lastSentTimeMap = new HashMap<>();

    private static final int COMMAND_COOL_DOWN = 5;

    private SEUCommandManager() {
    }

    public static SEUCommandManager getInstance(EntityID agentID) {
        if (agentID == null) {
            throw new IllegalArgumentException("agentID must not be null");
        }
        return INSTANCES.computeIfAbsent(agentID.getValue(), id -> new SEUCommandManager());
    }

    public synchronized void addCommand(CommunicationMessage message, StandardMessagePriority priority) {
        if (message == null)
            return;

        String key = generateCommandKey(message);
        QueuedCommand existing = commandQueue.get(key);
        if (existing == null || getPriorityValue(priority) > getPriorityValue(existing.priority())) {
            commandQueue.put(key, new QueuedCommand(message, priority));
        }
    }

    public synchronized void flush(AgentInfo agentInfo, MessageManager messageManager) {
        int currentTime = agentInfo.getTime();

        for (Map.Entry<String, QueuedCommand> entry : commandQueue.entrySet()) {
            String key = entry.getKey();
            CommunicationMessage msg = entry.getValue().message();
            Integer lastSentTime = lastSentTimeMap.get(key);

            if (lastSentTime == null || currentTime < lastSentTime
                    || currentTime - lastSentTime >= COMMAND_COOL_DOWN) {
                messageManager.addMessage(msg);
                lastSentTimeMap.put(key, currentTime);
            }
        }

        commandQueue.clear();
    }

    private String generateCommandKey(CommunicationMessage msg) {

        return msg.getClass().getSimpleName() + "_" + msg.getCheckKey();
    }

    private int getPriorityValue(StandardMessagePriority priority) {
        if (priority == null) {
            return 0;
        }
        return switch (priority) {
            case HIGH -> 3;
            case NORMAL -> 2;
            case LOW -> 1;
        };
    }

    private record QueuedCommand(CommunicationMessage message, StandardMessagePriority priority) {
    }
}
