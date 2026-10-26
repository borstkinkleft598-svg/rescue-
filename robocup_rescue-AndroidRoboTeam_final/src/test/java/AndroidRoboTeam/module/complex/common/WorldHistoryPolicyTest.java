package AndroidRoboTeam.module.complex.common;

import adf.core.agent.info.WorldInfo;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldHistoryPolicyTest {
    @Test void disabledHistoryPreservesLiveStateAndAllowsCurrentUpdates() {
        StandardWorldModel model = new StandardWorldModel();
        Civilian civilian = new Civilian(new EntityID(1));
        civilian.setHP(5000); model.addEntity(civilian);
        WorldInfo world = new WorldInfo(model);
        WorldHistoryPolicy.configure(world,false);
        assertFalse(world.isRequestedRollback());
        civilian.setHP(4000);
        assertEquals(4000,((Human)world.getEntity(civilian.getID())).getHP());
        assertEquals(1,world.getEntitiesOfType(StandardEntityURN.CIVILIAN).size());
    }
    @Test void debugHistoryCanBeExplicitlyReenabled() {
        WorldInfo world = new WorldInfo(new StandardWorldModel());
        WorldHistoryPolicy.configure(world,true);
        assertTrue(world.isRequestedRollback());
    }
}
