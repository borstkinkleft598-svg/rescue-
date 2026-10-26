package AndroidRoboTeam.module.complex.paper;

import kernel.AgentProxy;
import kernel.Perception;
import rescuecore2.config.Config;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.*;
import java.util.*;

/** Experiment-only exact global dynamic observations. Do not depend on legacy
 * perception's spatial index to discover humans inside buildings. Static map
 * geometry is already supplied in the initial world model. */
public final class PaperFullCityPerception implements Perception {
    private WorldModel<? extends Entity> world;
    private final Map<EntityID, Map<Integer, Property>> previous = new HashMap<>();
    private ChangeSet cached;
    @Override public void initialise(Config config, WorldModel<? extends Entity> model) { world = model; }
    @Override public void setTime(int time) { cached = null; }
    @Override public ChangeSet getVisibleEntities(AgentProxy agent) {
        if (cached != null) return cached;
        ChangeSet changes = new ChangeSet();
        for (Entity entity : world) {
            if (entity instanceof Human human) {
                changed(changes, human, human.getPositionProperty());
                changed(changes, human, human.getXProperty());
                changed(changes, human, human.getYProperty());
                changed(changes, human, human.getHPProperty());
                changed(changes, human, human.getDamageProperty());
                changed(changes, human, human.getBuriednessProperty());
                changed(changes, human, human.getStaminaProperty());
                changed(changes, human, human.getDirectionProperty());
                changed(changes, human, human.getPositionHistoryProperty());
                if (human instanceof FireBrigade fb) changed(changes, fb, fb.getWaterProperty());
            } else if (entity instanceof Building building) {
                changed(changes, building, building.getBrokennessProperty());
                changed(changes, building, building.getFierynessProperty());
                changed(changes, building, building.getTemperatureProperty());
            } else if (entity instanceof Road road) {
                changed(changes, road, road.getBlockadesProperty());
            } else if (entity instanceof Blockade blockade) {
                for (Property property : blockade.getProperties()) changed(changes, blockade, property);
            }
        }
        Iterator<EntityID> oldIds = previous.keySet().iterator();
        while (oldIds.hasNext()) {
            EntityID id = oldIds.next();
            if (world.getEntity(id) == null) { changes.entityDeleted(id); oldIds.remove(); }
        }
        cached = changes;
        return cached;
    }
    private void changed(ChangeSet changes, Entity entity, Property property) {
        Map<Integer, Property> seen = previous.computeIfAbsent(entity.getID(), ignored -> new HashMap<>());
        Property old = seen.get(property.getURN());
        if (old == null || old.isDefined() != property.isDefined() || !Objects.deepEquals(old.getValue(), property.getValue())) {
            changes.addChange(entity, property);
            seen.put(property.getURN(), property.copy());
        }
    }
}
