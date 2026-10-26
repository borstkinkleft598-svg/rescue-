package AndroidRoboTeam.module.complex.paper;

import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.agent.module.ModuleManager;
import adf.core.component.module.complex.Search;
import rescuecore2.worldmodel.EntityID;

public final class PaperIdleSearch extends Search {
    public PaperIdleSearch(AgentInfo a, WorldInfo w, ScenarioInfo s, ModuleManager m, DevelopData d) { super(a,w,s,m,d); }
    @Override public Search calc() { return this; }
    @Override public EntityID getTarget() { return null; }
}
