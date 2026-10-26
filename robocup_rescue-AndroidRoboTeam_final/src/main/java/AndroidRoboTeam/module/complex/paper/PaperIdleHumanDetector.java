package AndroidRoboTeam.module.complex.paper;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.complex.HumanDetector;
import rescuecore2.worldmodel.EntityID;

/** Real ambulances do not load civilians in the virtual-transport experiment. */
public final class PaperIdleHumanDetector extends HumanDetector {
    public PaperIdleHumanDetector(AgentInfo a, WorldInfo w, ScenarioInfo s, ModuleManager m, DevelopData d) { super(a,w,s,m,d); }
    @Override public HumanDetector calc() { return this; }
    @Override public EntityID getTarget() { return null; }
}
