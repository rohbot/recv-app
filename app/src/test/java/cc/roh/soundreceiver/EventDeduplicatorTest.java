package cc.roh.soundreceiver;

import org.junit.Test;
import static org.junit.Assert.*;
import static cc.roh.soundreceiver.EventDeduplicator.Kind.*;

public class EventDeduplicatorTest {
    private final EventDeduplicator dedup = new EventDeduplicator();
    private EventDeduplicator.Decision accept(String id, long boot, long seq, boolean read) {
        return dedup.accept(id, ReceiverProtocol.decode(BleFixtures.event(boot, seq)), read);
    }
    @Test public void firstReadIsHistoricalAndNotificationDuplicateIsIgnored() {
        assertEquals(HISTORICAL, accept("A",0,10,true).kind());
        assertEquals(DUPLICATE, accept("A",0,10,false).kind());
    }
    @Test public void notificationBeforeReadAlertsOnlyOnce() {
        assertEquals(NEW, accept("A",1,20,false).kind());
        assertEquals(DUPLICATE, accept("A",1,20,true).kind());
        assertEquals(OLDER, accept("A",1,19,true).kind());
    }
    @Test public void reconnectReadDetectsNewerAndMissedEvents() {
        accept("A",1,1,true);
        EventDeduplicator.Decision decision = accept("A",1,4,true);
        assertEquals(NEW, decision.kind()); assertEquals(2, decision.missed());
    }
    @Test public void sequenceWrapSkipsZero() {
        accept("A",1,0xffffffffL,false);
        assertEquals(0, accept("A",1,1,false).missed());
        assertEquals(DUPLICATE, accept("A",1,1,true).kind());
        assertEquals(OLDER, accept("A",1,0xffffffffL,true).kind());
    }
    @Test public void gapAcrossWrapIsComputedOnNonzeroRing() {
        accept("A",1,0xfffffffeL,false);
        assertEquals(2, accept("A",1,2,false).missed());
    }
    @Test public void halfwayBoundaryIsNewerAndLargerDeltaIsRejected() {
        accept("A",1,1,false);
        assertEquals(NEW, accept("A",1,2147483648L,false).kind());
        accept("B",1,1,false);
        assertEquals(OLDER, accept("B",1,2147483649L,false).kind());
    }
    @Test public void bootAndDeviceIdentityAreIndependent() {
        accept("A",1,100,false);
        assertEquals(NEW, accept("A",2,1,false).kind());
        assertEquals(NEW, accept("B",1,1,false).kind());
        assertEquals(DUPLICATE, accept("A",1,100,true).kind());
    }
    @Test public void sentinelEstablishesQuietBaselineWithoutAlert() {
        assertEquals(SENTINEL, dedup.accept("A", ReceiverProtocol.decode(BleFixtures.sentinel(0)), true).kind());
        assertEquals(NEW, accept("A",0,1,true).kind());
        assertEquals(OLDER, dedup.accept("A", ReceiverProtocol.decode(BleFixtures.sentinel(0)), true).kind());
    }
    @Test public void rebootReadIsHistoricalButNextNotificationIsNew() {
        accept("A",1,100,false);
        assertEquals(HISTORICAL, accept("A",2,1,true).kind());
        assertEquals(NEW, accept("A",2,2,false).kind());
    }
}
