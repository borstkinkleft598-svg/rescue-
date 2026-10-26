package AndroidRoboTeam.module.complex.common;

import adf.core.agent.Agent;
import adf.core.agent.communication.MessageManager;
import adf.core.agent.communication.standard.bundle.information.*;
import adf.core.agent.develop.DevelopData;
import adf.core.agent.info.*;
import adf.core.component.module.algorithm.PathPlanning;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.common.LayeredRescuePlanner.Role.*;

/** Real ADF messages/world state with a deterministic route stub, no simulator/network. */
class LDcopWorldPlannerTest {
    @TempDir Path temporary;
    private final DevelopData data = new DevelopData(false, "unused", List.of());
    private static final class DummyAgent extends Agent<Human> {
        DummyAgent(String path, DevelopData d) { super("test",false,path,false,null,d); }
        protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() { return EnumSet.of(StandardEntityURN.AMBULANCE_TEAM); }
        protected void think() {}
    }
    private static final class Info extends AgentInfo {
        final Human self; Human passenger;
        Info(Agent<?> agent, StandardWorldModel model, Human self) { super(agent,model); this.self=self; }
        public StandardEntity me() { return self; }
        public EntityID getID() { return self.getID(); }
        public EntityID getPosition() { return self.getPosition(); }
        public Human someoneOnBoard() { return passenger; }
    }
    private static final class Route extends PathPlanning {
        EntityID from, to;
        Route(Info ai, WorldInfo wi, DevelopData d) { super(ai,wi,null,null,d); }
        public Route setFrom(EntityID id) { from=id; return this; }
        public Route setDestination(Collection<EntityID> ids) { to=ids.iterator().next(); return this; }
        public Route calc() { return this; }
        public List<EntityID> getResult() { return List.of(from,to); }
        public double getDistance() { return worldInfo.getDistance(from,to); }
    }
    private final class Fixture {
        final StandardWorldModel model = new StandardWorldModel();
        final WorldInfo world;
        final Info info;
        final LDcopWorldPlanner planner;
        Fixture(boolean fire) {
            road(2,0); road(3,100000); road(4,200000); road(5,150000);
            Refuge refuge=new Refuge(new EntityID(10)); refuge.setX(220000);refuge.setY(0);model.addEntity(refuge);
            Human self=fire?new FireBrigade(new EntityID(1)):new AmbulanceTeam(new EntityID(1));
            health(self,2); model.addEntity(self);
            world=new WorldInfo(model);
            info=new Info(new DummyAgent(temporary.resolve("unused").toString(),data),model,self);
            tick(1);
            planner=new LDcopWorldPlanner(info,world,new Route(info,world,data),data);
        }
        void road(int id,int x) { Road r=new Road(new EntityID(id));r.setX(x);r.setY(0);model.addEntity(r); }
        void health(Human h,int pos) { h.setHP(5000);h.setDamage(20);h.setBuriedness(0);h.setPosition(new EntityID(pos)); }
        Civilian victim(int id,int pos,int buried) {
            Civilian c=new Civilian(new EntityID(id));health(c,pos);c.setBuriedness(buried);model.addEntity(c);return c;
        }
        AmbulanceTeam ambulance(int id,int pos) { AmbulanceTeam a=new AmbulanceTeam(new EntityID(id));health(a,pos);model.addEntity(a);return a; }
        FireBrigade fire(int id,int pos) { FireBrigade a=new FireBrigade(new EntityID(id));health(a,pos);a.setWater(0);model.addEntity(a);return a; }
        void tick(int t) { info.setTime(t);ChangeSet changed=new ChangeSet();info.setChanged(changed);world.setChanged(changed); }
        MessageManager at(AmbulanceTeam a,EntityID target) {
            MessageManager m=new MessageManager();m.addReceivedMessage(new MessageAmbulanceTeam(true,a,MessageAmbulanceTeam.ACTION_MOVE,target));return m;
        }
    }
    @Test void readyPassengerIsDispatchedWithoutInventingAFutureFireAssignment() {
        Fixture f=new Fixture(false);Civilian ready=f.victim(20,3,0);f.victim(21,4,60);
        assertEquals(ready.getID(),f.planner.select(TRANSPORT,new MessageManager()));
    }
    @Test void sameRoadLoadingIsImmediateEvenWithAnUncertainSurvivalEstimate() {
        Fixture f=new Fixture(false);Civilian ready=f.victim(20,2,0);ready.setHP(1);ready.setDamage(100);
        f.info.getChanged().addChange(ready,ready.getHPProperty());
        assertEquals(ready.getID(),f.planner.select(TRANSPORT,new MessageManager()));
    }
    @Test void loadedAmbulanceDoesNotChooseAnotherPassenger() {
        Fixture f=new Fixture(false);f.info.passenger=f.victim(20,1,0);f.victim(21,3,0);
        assertNull(f.planner.select(TRANSPORT,new MessageManager()));
    }
    @Test void remoteHealthyPeopleDoNotConsumeReadyInjuredPickupPriority() {
        Fixture f=new Fixture(false);
        Building building=new Building(new EntityID(6));building.setX(100000);building.setY(0);building.setBrokenness(50);f.model.addEntity(building);
        Civilian healthy=f.victim(20,6,0);healthy.setDamage(0);
        assertNull(f.planner.select(TRANSPORT,new MessageManager()));
    }
    @Test void constantlyRenewedButStationaryPeerEventuallyLosesExclusivity() {
        Fixture f=new Fixture(false);Civilian victim=f.victim(20,4,0);AmbulanceTeam peer=f.ambulance(30,3);
        f.planner.update(f.at(peer,victim.getID()));assertFalse(f.planner.allowsFallback(TRANSPORT,victim.getID()));
        for(int t=2;t<=14;t++) { f.tick(t);f.planner.update(f.at(peer,victim.getID())); }
        assertTrue(f.planner.allowsFallback(TRANSPORT,victim.getID()));
    }
    @Test void productivePeerRetainsExclusivity() {
        Fixture f=new Fixture(false);Civilian victim=f.victim(20,4,0);AmbulanceTeam peer=f.ambulance(30,3);
        for(int t=1;t<=14;t++) { f.tick(t);if(t==8)peer.setPosition(new EntityID(5));f.planner.update(f.at(peer,victim.getID())); }
        assertFalse(f.planner.allowsFallback(TRANSPORT,victim.getID()));
    }
    @Test void closerRescuerIsNotExcludedByADistantFirstClaimant() {
        Fixture f=new Fixture(true);Civilian victim=f.victim(20,3,20);FireBrigade peer=f.fire(30,4);
        f.info.self.setPosition(new EntityID(5));
        MessageManager m=new MessageManager();m.addReceivedMessage(new MessageFireBrigade(true,peer,MessageFireBrigade.ACTION_MOVE,victim.getID()));
        f.planner.update(m);
        assertTrue(f.planner.allowsFallback(RESCUE,victim.getID()));
    }
    @Test void actualWorkingPeerCanReceiveUsefulNearbyAssistance() {
        Fixture f=new Fixture(true);Civilian victim=f.victim(20,3,60);FireBrigade peer=f.fire(30,3);
        MessageManager m=new MessageManager();m.addReceivedMessage(new MessageFireBrigade(true,peer,MessageFireBrigade.ACTION_RESCUE,victim.getID()));
        assertEquals(victim.getID(),f.planner.select(RESCUE,m));
    }
    @Test void directObservationsAreNotOverwrittenByAnOlderRadioPosition() {
        Fixture f=new Fixture(false);Civilian victim=f.victim(20,4,0);AmbulanceTeam peer=f.ambulance(30,3);
        MessageManager old=f.at(peer,victim.getID());peer.setPosition(new EntityID(5));
        f.info.getChanged().addChange(peer,peer.getPositionProperty());f.planner.update(old);
        assertEquals(new EntityID(5),peer.getPosition());
    }
    @Test void ordinaryAreaMoveClearsOldWorkingStateWithoutCreatingANewHumanClaim() {
        Fixture f=new Fixture(true);Civilian victim=f.victim(20,3,60);FireBrigade peer=f.fire(30,3);
        MessageManager first=new MessageManager();first.addReceivedMessage(new MessageFireBrigade(true,peer,MessageFireBrigade.ACTION_RESCUE,victim.getID()));
        f.planner.update(first);
        f.tick(2);peer.setPosition(new EntityID(4));
        MessageManager moving=new MessageManager();moving.addReceivedMessage(new MessageFireBrigade(true,peer,MessageFireBrigade.ACTION_MOVE,new EntityID(4)));
        assertNull(f.planner.select(RESCUE,moving));
    }
    @Test void aLoadedPeerCannotKeepAnotherReadyPassengerReserved() {
        Fixture f=new Fixture(false);Civilian ready=f.victim(20,4,0);AmbulanceTeam peer=f.ambulance(30,3);
        f.victim(21,30,0);
        f.planner.update(f.at(peer,ready.getID()));
        assertTrue(f.planner.allowsFallback(TRANSPORT,ready.getID()));
    }
    @Test void ownStalledRemoteTargetHasABoundedRetryCooldown() {
        Fixture f=new Fixture(false);Civilian ready=f.victim(20,4,0);
        for(int t=1;t<=13;t++) { f.tick(t);f.planner.confirmTarget(TRANSPORT,ready.getID(),null); }
        f.tick(14);assertNull(f.planner.select(TRANSPORT,new MessageManager()));
        assertFalse(f.planner.allowsFallback(TRANSPORT,ready.getID()));
        f.tick(19);f.planner.update(new MessageManager());
        assertTrue(f.planner.allowsFallback(TRANSPORT,ready.getID()));
    }
}
