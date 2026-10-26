package AndroidRoboTeam.module.complex.paper;

import org.junit.jupiter.api.Test;
import rescuecore2.config.Config;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.properties.IntProperty;
import static org.junit.jupiter.api.Assertions.*;

class PaperServerHelpersTest {
    @Test void fullCityPerceptionIncludesHumansWithoutUsingTheSpatialIndex() {
        StandardWorldModel world = new StandardWorldModel();
        Civilian civilian = new Civilian(new EntityID(1));
        civilian.setHP(1234); civilian.setDamage(17); civilian.setBuriedness(8);
        world.addEntity(civilian);
        FireBrigade brigade = new FireBrigade(new EntityID(2));
        brigade.setHP(9999); world.addEntity(brigade);
        PaperFullCityPerception perception = new PaperFullCityPerception();
        perception.initialise(new Config(),world);
        var changes = perception.getVisibleEntities(null);
        assertTrue(changes.getChangedEntities().contains(civilian.getID()));
        assertTrue(changes.getChangedEntities().contains(brigade.getID()));
        assertTrue(changes.getChangedProperties(civilian.getID()).stream().anyMatch(p ->
                p.getURN() == civilian.getHPProperty().getURN() && ((IntProperty)p).getValue() == 1234));
        perception.setTime(2);
        assertTrue(perception.getVisibleEntities(null).getChangedEntities().isEmpty());
        civilian.setHP(1200);
        perception.setTime(3);
        assertTrue(perception.getVisibleEntities(null).getChangedEntities().contains(civilian.getID()));
    }
    @Test void commandCollectorDoesNotWaitForCivilianCentreOrDeadAgentActions() {
        assertFalse(PaperCommandCollector.requiresAction(new Civilian(new EntityID(1))));
        assertFalse(PaperCommandCollector.requiresAction(new FireStation(new EntityID(2))));
        FireBrigade brigade = new FireBrigade(new EntityID(3));
        brigade.setHP(100);
        assertTrue(PaperCommandCollector.requiresAction(brigade));
        brigade.setHP(0);
        assertFalse(PaperCommandCollector.requiresAction(brigade));
    }
}
