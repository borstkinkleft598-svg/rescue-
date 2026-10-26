package AndroidRoboTeam.module.complex.common;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static AndroidRoboTeam.module.complex.common.ReadyTransportPolicy.*;

class ReadyTransportPolicyTest {
    @Test void imminentButFeasiblePassengerHasPriority() {
        assertEquals(2,choose(List.of(new Pickup(1,1,10,100),new Pickup(2,3,20,23)),null,4));
    }
    @Test void otherwiseChooseEarlierCompletedPickupNotTheLongestTrip() {
        assertEquals(1,choose(List.of(new Pickup(1,1,10,100),new Pickup(2,20,30,90)),null,4));
    }
    @Test void tinyAdvantagesDoNotCauseRepeatedTargetSwitches() {
        assertEquals(2,choose(List.of(new Pickup(1,1,10,100),new Pickup(2,2,12,100)),2,4));
    }
    @Test void impossibleAndUnreachablePassengersCannotBlockReadyTasks() {
        assertEquals(3,choose(List.of(new Pickup(1,1,10,9),new Pickup(2,TemporalSchedule.UNREACHABLE,1,100),
                new Pickup(3,1,10,100)),1,4));
        assertNull(choose(List.of(),null,4));
    }
    @Test void readySelectionIsDeterministicAndUrgentPassengerMayPreempt() {
        List<Pickup> p=new ArrayList<>(List.of(new Pickup(2,1,10,100),new Pickup(1,1,10,100)));
        assertEquals(1,choose(p,null,4));
        Collections.reverse(p);
        assertEquals(1,choose(p,null,4));
        assertEquals(3,choose(List.of(p.get(0),new Pickup(3,2,11,13)),1,4));
    }
}
