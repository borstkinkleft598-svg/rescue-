package AndroidRoboTeam.centralized.SEUAT;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.AgentInfo;
import adf.core.agent.info.ScenarioInfo;
import adf.core.agent.info.WorldInfo;
import adf.core.agent.module.ModuleManager;
import adf.core.component.centralized.CommandPicker;
import adf.core.component.communication.CommunicationMessage;
import rescuecore2.worldmodel.EntityID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class DefaultCommandPickerAmbulance extends CommandPicker {

    private int scoutDistance;

    private Collection<CommunicationMessage> messages;
    private Map<EntityID, EntityID> allocationData;

    public DefaultCommandPickerAmbulance(AgentInfo ai, WorldInfo wi, ScenarioInfo si, ModuleManager moduleManager,
            DevelopData developData) {
        super(ai, wi, si, moduleManager, developData);
        this.messages = new ArrayList<>();
        this.allocationData = null;
        this.scoutDistance = developData.getInteger("CommandPickerAmbulance.scoutDistance", 40000);
    }

    @Override
    public CommandPicker setAllocatorResult(Map<EntityID, EntityID> allocationData) {
        this.allocationData = allocationData;
        return this;
    }

    @Override
    public CommandPicker calc() {
        return this;
    }

    @Override
    public Collection<CommunicationMessage> getResult() {
        return this.messages;
    }

}
