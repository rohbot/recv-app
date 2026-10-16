package cc.roh.soundreceiver;

import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorLogTest {
    private static final Instant NOW = Instant.parse("2026-10-16T10:20:30Z");

    @Test public void retainsOnlyLatestThousandButCountsAllEvents() {
        MonitorLog log = new MonitorLog();
        for (int i = 0; i < 1100; i++) log.receive(ReceiverProtocolTest.EVENT, NOW);
        assertEquals(1100, log.totalLines);
        assertEquals(1100, log.eventCount);
        assertEquals(1000, log.visible(false).size());
        assertEquals(NOW, log.latestEventTime);
    }

    @Test public void filteringAndExportKeepRawLinesAndTimestamps() {
        MonitorLog log = new MonitorLog();
        log.receive("boot", NOW);
        log.receive("ESP-NOW receiver aa:bb:cc:dd:ee:ff, channel 1", NOW);
        log.receive(ReceiverProtocolTest.EVENT, NOW);
        assertEquals(1, log.visible(true).size());
        assertEquals(3, log.visible(false).size());
        assertEquals(1, log.eventCount);
        assertTrue(log.export().startsWith("2026-10-16T10:20:30Z\tboot\n"));
        assertTrue(log.export().contains(ReceiverProtocolTest.EVENT));
    }

    @Test public void reconnectResetsSummaryAndRetainsPreviousLogs() {
        MonitorLog log = new MonitorLog();
        log.receive(ReceiverProtocolTest.EVENT, NOW);
        log.resetSummary();
        assertEquals(0, log.eventCount);
        assertNull(log.latest);
        assertEquals(1, log.visible(false).size());
        log.receive(ReceiverProtocolTest.EVENT, NOW.plusSeconds(1));
        assertEquals(1, log.eventCount);
        assertEquals(2, log.totalLines);
    }

    @Test public void clearResetsAllData() {
        MonitorLog log = new MonitorLog();
        log.receive(ReceiverProtocolTest.EVENT, NOW);
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(0, log.totalLines);
        assertEquals(0, log.eventCount);
        assertNull(log.latest);
        assertNull(log.last);
        assertEquals("", log.export());
    }

    @Test public void evictionRemovesOldestRawLine() {
        MonitorLog log = new MonitorLog();
        for (int i = 0; i < 1001; i++) log.receive("line " + i, NOW);
        assertEquals("line 1", log.visible(false).get(0).text());
        assertFalse(log.export().contains("\tline 0\n"));
    }
}
