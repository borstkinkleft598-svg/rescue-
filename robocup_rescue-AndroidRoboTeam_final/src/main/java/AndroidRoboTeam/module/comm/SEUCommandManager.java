package AndroidRoboTeam.module.comm;

import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.StandardMessage;
import adf.core.agent.communication.standard.bundle.StandardMessagePriority;
import adf.core.agent.info.AgentInfo;
import adf.core.component.communication.CommunicationMessage;

import java.util.*;

public class SEUCommandManager {

    private Map<String, CommunicationMessage> commandQueue = new HashMap<>();

    private Map<String, Integer> lastSentTimeMap = new HashMap<>();

    private static final int COMMAND_COOL_DOWN = 5;

    public SEUCommandManager() {
    }

    public void addCommand(CommunicationMessage message, StandardMessagePriority p) {
        if (message == null)
            return;

        String key = generateCommandKey(message);

        if (commandQueue.containsKey(key)) {
            CommunicationMessage existing = commandQueue.get(key);
            if (getPriorityValue(message, p) > getPriorityValue(existing, p)) {
                commandQueue.put(key, message);
            }
        } else {
            commandQueue.put(key, message);
        }
    }

    public void flush(AgentInfo agentInfo, MessageManager messageManager) {
        int currentTime = agentInfo.getTime();

        for (Map.Entry<String, CommunicationMessage> entry : commandQueue.entrySet()) {
            String key = entry.getKey();
            CommunicationMessage msg = entry.getValue();

            if (currentTime - lastSentTimeMap.getOrDefault(key, -COMMAND_COOL_DOWN) >= COMMAND_COOL_DOWN) {
                messageManager.addMessage(msg);
                lastSentTimeMap.put(key, currentTime);
            }
        }

        commandQueue.clear();
    }

    private String generateCommandKey(CommunicationMessage msg) {

        return msg.getClass().getSimpleName() + "_" + msg.getCheckKey();
    }

    private int getPriorityValue(CommunicationMessage msg, StandardMessagePriority p) {
        if (msg instanceof StandardMessage) {

            if (p == StandardMessagePriority.HIGH)
                return 3;
            if (p == StandardMessagePriority.NORMAL)
                return 2;
            if (p == StandardMessagePriority.LOW)
                return 1;
        }
        return 0;
    }
}