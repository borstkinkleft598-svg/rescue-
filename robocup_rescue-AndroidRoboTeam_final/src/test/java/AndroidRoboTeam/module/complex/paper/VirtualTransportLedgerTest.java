package AndroidRoboTeam.module.complex.paper;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.paper.VirtualTransportLedger.*;

class VirtualTransportLedgerTest {
    private Observation seen(int buried, int hp, int duration) { return new Observation(1,buried,hp,20,duration,9); }
    @Test void transportBeginsOnlyAfterRescueAndCompletesAfterTravelDuration() {
        var ledger = new VirtualTransportLedger();
        ledger.observe(1,List.of(seen(5,100,3)));
        ledger.observe(2,List.of(seen(0,80,3)));
        assertEquals(1,ledger.rescued());
        assertEquals(0,ledger.transported());
        ledger.observe(4,List.of(seen(0,40,3)));
        assertEquals(0,ledger.transported());
        ledger.observe(5,List.of(seen(0,20,3)));
        assertEquals(1,ledger.transported());
        ledger.observe(6,List.of(seen(0,0,3)));
        assertEquals(1,ledger.transported(), "virtual arrival must not be undone by later real-world death");
    }
    @Test void deathBeforeArrivalIsNotTransported() {
        var ledger = new VirtualTransportLedger();
        ledger.observe(1,List.of(seen(2,40,3)));
        ledger.observe(2,List.of(seen(0,20,3)));
        ledger.observe(3,List.of(seen(0,0,3)));
        assertEquals(0,ledger.transported());
        assertEquals(Status.DEAD,ledger.entries().iterator().next().status());
    }
    @Test void aMissedArrivalObservationDoesNotInventSuccessFromClampedZeroHp() {
        var ledger = new VirtualTransportLedger();
        ledger.observe(1,List.of(seen(2,40,3)));
        ledger.observe(2,List.of(seen(0,20,3)));
        ledger.observe(8,List.of(seen(0,0,3)));
        assertEquals(Status.UNKNOWN,ledger.entries().iterator().next().status());
        assertEquals(0,ledger.transported());
    }
    @Test void healthyUnburiedCiviliansAreNotCountedAsRescued() {
        var ledger = new VirtualTransportLedger();
        ledger.observe(1,List.of(seen(0,10000,0)));
        assertTrue(ledger.cohort().isEmpty());
        assertEquals(0,ledger.rescued());
    }
    @Test void noRefugeNeverProducesVirtualTransportSuccess() {
        var ledger = new VirtualTransportLedger();
        ledger.observe(1,List.of(new Observation(1,2,100,20,0,-1)));
        ledger.observe(2,List.of(new Observation(1,0,80,20,0,-1)));
        assertEquals(0,ledger.transported());
    }
}
