package cc.roh.soundreceiver;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorLogTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:20:30Z");
    @Test public void historicalReadUpdatesCardsWithoutCountingAsNew() {
        MonitorLog log = new MonitorLog();
        assertTrue(log.receive("A", BleFixtures.event(1,42), true, NOW).historical());
        assertEquals(0, log.eventCount); assertEquals(42, log.latest.sequence());
        assertNull(log.receive("A", BleFixtures.event(1,42), false, NOW));
        assertEquals(1, log.totalLines);
    }
    @Test public void alertsOnlyForUniqueNewDetectionsAcrossReadsAndNotifications() {
        MonitorLog log = new MonitorLog();
        assertFalse(log.receive("A", BleFixtures.sentinel(1), true, NOW).shouldAlert());
        assertFalse(log.receive("B", BleFixtures.event(1, 10), true, NOW).shouldAlert());
        assertTrue(log.receive("A", BleFixtures.event(1, 1), false, NOW).shouldAlert());
        assertNull(log.receive("A", BleFixtures.event(1, 1), true, NOW));
        assertTrue(log.receive("A", BleFixtures.event(1, 3), true, NOW).shouldAlert());
        assertFalse(log.receive("A", new byte[0], false, NOW).shouldAlert());
        log.clear();
        assertNull(log.receive("A", BleFixtures.event(1, 3), false, NOW));
    }
    @Test public void retainedLogIsBoundedButReceivedCountIsNotDeviceCounter() {
        MonitorLog log = new MonitorLog();
        for (int i=100; i<1200; i++) log.receive("A", BleFixtures.event(1,i), false,NOW);
        assertEquals(1100, log.eventCount); assertEquals(1100, log.totalLines); assertEquals(1000, log.visible(false).size());
        assertEquals(1199, log.latest.sequence());
    }
    @Test public void noEventSentinelDoesNotPopulateCardsOrCount() {
        MonitorLog log = new MonitorLog();
        log.receive("A", BleFixtures.sentinel(0), true,NOW);
        assertNull(log.latest); assertEquals(0, log.eventCount); assertEquals(0, log.visible(true).size());
    }
    @Test public void filteringExportAndClearPreserveDeduplication() {
        MonitorLog log = new MonitorLog();
        log.status("connected",NOW,false);
        log.receive("A", BleFixtures.event(1,1),false,NOW);
        assertEquals(1, log.visible(true).size()); assertEquals(2, log.visible(false).size());
        assertTrue(log.export().startsWith("2026-10-16T10:20:30Z\tconnected\n"));
        assertTrue(log.export().contains("source=notification")); assertTrue(log.export().contains("raw=0101"));
        log.clear(); assertTrue(log.isEmpty()); assertEquals(0,log.eventCount); assertNull(log.latest);
        assertNull(log.receive("A", BleFixtures.event(1,1),true,NOW));
        assertEquals(0,log.eventCount);
    }
    @Test public void malformedPayloadDoesNotKillSubsequentEvents() {
        MonitorLog log = new MonitorLog();
        byte[] unknown = BleFixtures.event(1,1); unknown[0] = 2;
        assertTrue(log.receive("A",unknown,false,NOW).warning());
        log.receive("A",BleFixtures.event(1,2),false,NOW);
        assertEquals(1,log.eventCount); assertEquals(2,log.totalLines);
    }
    @Test public void uptimeWrapDoesNotAffectOrderingOrReceiptTime() {
        MonitorLog log = new MonitorLog();
        log.receive("A",BleFixtures.value(1,1,1,0xffffffffL,65535,-4000,-6000),false,NOW);
        log.receive("A",BleFixtures.value(1,1,2,1,64,-4000,-6000),false,NOW.plusSeconds(1));
        assertEquals(2,log.eventCount); assertEquals(1,log.latest.uptime()); assertEquals(NOW.plusSeconds(1),log.latestEventTime);
        assertTrue(log.export().contains("duration=65535+ ms"));
    }
    @Test public void missingEventsReportedSeparatelyFromDeliveredCount() {
        MonitorLog log = new MonitorLog();
        log.receive("A",BleFixtures.event(1,1),false,NOW);
        log.receive("A",BleFixtures.event(1,5),false,NOW);
        assertEquals(2,log.eventCount); assertEquals(3,log.missedCount);
    }
}
