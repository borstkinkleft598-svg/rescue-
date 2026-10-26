package AndroidRoboTeam.module.complex.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DispatchProgressTest {
    @Test void repeatedRadioMessagesCannotRenewAnUnproductiveReservationForever() {
        DispatchProgress p = null;
        for (int t=0;t<=20;t++) p=DispatchProgress.update(p,9,2,100,false,t);
        assertFalse(p.productive(20,8));
        assertEquals(0,p.progressedAt());
        assertEquals(20,p.seenAt());
    }
    @Test void ApproachingAndActualWorkKeepAReservationAlive() {
        var p=DispatchProgress.update(null,9,2,100,false,0);
        p=DispatchProgress.update(p,9,3,80,false,7);
        assertTrue(p.productive(10,8));
        p=DispatchProgress.update(p,9,4,0,true,14);
        assertTrue(p.productive(20,8));
    }
    @Test void circulatingWithoutGettingCloserIsNotProgress() {
        var p=DispatchProgress.update(null,9,2,100,false,0);
        p=DispatchProgress.update(p,9,3,120,false,4);
        p=DispatchProgress.update(p,9,2,100,false,8);
        assertFalse(p.productive(8,8));
    }
    @Test void aDifferentTargetStartsANewProgressWindow() {
        var p=DispatchProgress.update(null,9,2,100,false,0);
        p=DispatchProgress.update(p,10,2,100,false,20);
        assertTrue(p.productive(20,8));
    }
    @Test void nearbyWorkDoesNotDivertForSmallOrLateAssistance() {
        assertTrue(DispatchProgress.mayDivertToAssist(20,2,10,40,4));
        assertFalse(DispatchProgress.mayDivertToAssist(2,2,10,40,4));
        assertFalse(DispatchProgress.mayDivertToAssist(20,2,10,3,4));
        assertFalse(DispatchProgress.mayDivertToAssist(20,2,2,40,4));
    }
}
