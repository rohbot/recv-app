package cc.roh.soundreceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverProtocolTest {
    @Test public void referenceVectorMatchesFirmware() {
        byte[] bytes = {1,1,0x40,0,0x78,0x56,0x34,0x12,1,0,0,0,0x10,0x27,0,0,0x60,(byte)0xf0,(byte)0x90,(byte)0xe8};
        ReceiverProtocol.Message event = ReceiverProtocol.decode(bytes);
        assertEquals("0101400078563412010000001027000060f090e8", ReceiverProtocol.hex(bytes));
        assertEquals(305419896L, event.bootId());
        assertEquals(1, event.sequence()); assertEquals(10000, event.uptime()); assertEquals(64, event.duration());
        assertEquals(-40, event.level(), 0); assertEquals(-60, event.floor(), 0); assertEquals(20, event.rise(), 0);
    }
    @Test public void initialSnapshotIsSentinelNotMeasurement() {
        ReceiverProtocol.Message m = ReceiverProtocol.decode(BleFixtures.sentinel(0));
        assertFalse(m.event()); assertEquals(0, m.sequence()); assertEquals(0, m.bootId());
        assertEquals(-120, m.level(), 0);
    }
    @Test public void unsignedCountersAndSaturatedDurationStayPositive() {
        ReceiverProtocol.Message m = ReceiverProtocol.decode(BleFixtures.value(1, 0xffffffffL, 0xffffffffL, 0xffffffffL, 65535, 0, -12000));
        assertEquals(4294967295L, m.bootId()); assertEquals(4294967295L, m.sequence()); assertEquals(4294967295L, m.uptime());
        assertEquals(65535, m.duration()); assertEquals(120, m.rise(), 0);
    }
    @Test public void signedRiseAndHundredthsArePreserved() {
        ReceiverProtocol.Message m = ReceiverProtocol.decode(BleFixtures.value(1, 0, 1, 0, 1, -1234, -1001));
        assertEquals(-12.34, m.level(), 0.000001); assertEquals(-2.33, m.rise(), 0.000001);
    }
    @Test public void rejectsEveryWrongLength() {
        for (int size : new int[]{0,1,19,21,40}) assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(new byte[size]));
        assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(null));
    }
    @Test public void rejectsUnsupportedVersionAndType() {
        for (int offset : new int[]{0,1}) {
            byte[] bytes = BleFixtures.event(1,1); bytes[offset] = 2;
            assertTrue(assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(bytes)).getMessage().contains("Unsupported protocol"));
        }
    }
    @Test public void rejectsInvalidTypeSequenceCombinations() {
        assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(BleFixtures.value(0,0,1,0,0,-12000,-12000)));
        assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(BleFixtures.event(0,0)));
    }
    @Test public void rejectsOutOfRangeLevels() {
        for (int level : new int[]{-12001,1,32767,-32768}) {
            assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(BleFixtures.value(1,1,1,0,0,level,-6000)));
            assertThrows(IllegalArgumentException.class, () -> ReceiverProtocol.decode(BleFixtures.value(1,1,1,0,0,-4000,level)));
        }
    }
}
