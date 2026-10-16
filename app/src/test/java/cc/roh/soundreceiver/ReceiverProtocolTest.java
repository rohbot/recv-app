package cc.roh.soundreceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverProtocolTest {
    static final String EVENT = "SOUND EVENT from aa:bb:cc:dd:ee:ff #42 level=-23.4 dBFS floor=-60.1 dBFS rise=36.7 dB duration=80 ms uptime=123456 ms";

    @Test public void parsesAllEventFields() {
        ReceiverProtocol.Message message = ReceiverProtocol.parse(EVENT);
        assertNotNull(message);
        assertTrue(message.event());
        assertEquals("AA:BB:CC:DD:EE:FF", message.mac());
        assertEquals(42, message.sequence());
        assertEquals(-23.4, message.level(), 0.0001);
        assertEquals(-60.1, message.floor(), 0.0001);
        assertEquals(36.7, message.rise(), 0.0001);
        assertEquals(80, message.duration());
        assertEquals(123456, message.uptime());
    }

    @Test public void heartbeatIsNotAnEvent() {
        ReceiverProtocol.Message message = ReceiverProtocol.parse("ESP-NOW receiver 01:23:45:67:89:ab, channel 6");
        assertFalse(message.event());
        assertEquals("01:23:45:67:89:AB", message.mac());
        assertEquals(6, message.channel());
    }

    @Test public void supportsCaseInsensitiveAndIntegerMeasurements() {
        ReceiverProtocol.Message message = ReceiverProtocol.parse(EVENT.toLowerCase().replace("-23.4", "0").replace("36.7", "-2"));
        assertTrue(message.event());
        assertEquals(0, message.level(), 0);
        assertEquals(-2, message.rise(), 0);
    }

    @Test public void preservesUnrecognizedAndMalformedInputAsRawMessages() {
        for (String line : new String[]{"", "ESP-ROM:esp32s3", EVENT + " extra", EVENT.replace("aa:bb", "invalid"),
                EVENT.replace("-23.4", "NaN"), EVENT.replace("#42", "#9999999999999999999999999"),
                EVENT.replace("36.7", "9".repeat(400)), "E (12) startup error"}) {
            assertNull(line, ReceiverProtocol.parse(line));
        }
        assertTrue(ReceiverProtocol.isWarning("E (12) driver failed"));
        assertFalse(ReceiverProtocol.isWarning("Boot complete"));
    }

    @Test public void unsignedFirmwareCountersFitInLong() {
        ReceiverProtocol.Message message = ReceiverProtocol.parse(EVENT.replace("#42", "#4294967295").replace("123456", "4294967295"));
        assertEquals(4294967295L, message.sequence());
        assertEquals(4294967295L, message.uptime());
    }
}
