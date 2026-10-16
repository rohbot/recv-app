package cc.roh.soundreceiver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SerialLinesTest {
    @Test public void everyByteBoundaryPreservesUtf8AndCrLf() {
        String input = "ESP32 ready\r\n\n温度 🎤 café\r\nfinal partial";
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (int boundary = 0; boundary <= bytes.length; boundary++) {
            List<String> result = new ArrayList<>();
            SerialLines framing = new SerialLines(result::add);
            framing.push(java.util.Arrays.copyOfRange(bytes, 0, boundary), boundary);
            byte[] tail = java.util.Arrays.copyOfRange(bytes, boundary, bytes.length);
            framing.push(tail, tail.length);
            framing.finish();
            framing.finish();
            assertEquals("boundary " + boundary, List.of("ESP32 ready", "温度 🎤 café", "final partial"), result);
        }
    }

    @Test public void oneByteReadsStillProduceEvents() {
        List<String> result = new ArrayList<>();
        SerialLines framing = new SerialLines(result::add);
        for (byte b : (ReceiverProtocolTest.EVENT + "\n").getBytes(StandardCharsets.UTF_8)) framing.push(new byte[]{b}, 1);
        framing.finish();
        assertEquals(1, result.size());
        assertTrue(ReceiverProtocol.parse(result.get(0)).event());
    }

    @Test public void oversizedLineIsBoundedAndItsTailIsNotParsedAsAnotherEvent() {
        List<String> result = new ArrayList<>();
        SerialLines framing = new SerialLines(result::add);
        byte[] bytes = ("x".repeat(50000) + ReceiverProtocolTest.EVENT + "\nnext\n").getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += 97) {
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, i, Math.min(i + 97, bytes.length));
            framing.push(chunk, chunk.length);
        }
        framing.finish();
        assertEquals(2, result.size());
        assertTrue(result.get(0).length() <= SerialLines.MAX_LENGTH);
        assertTrue(result.get(0).endsWith(" [line truncated]"));
        assertEquals("next", result.get(1));
    }

    @Test public void exactlyMaximumLineIsAccepted() {
        List<String> result = new ArrayList<>();
        SerialLines framing = new SerialLines(result::add);
        byte[] bytes = ("x".repeat(8192) + "\n").getBytes(StandardCharsets.UTF_8);
        framing.push(bytes, bytes.length);
        framing.finish();
        assertEquals(List.of("x".repeat(8192)), result);
    }

    @Test public void malformedAndIncompleteUtf8DoesNotStopStream() {
        List<String> result = new ArrayList<>();
        SerialLines framing = new SerialLines(result::add);
        framing.push(new byte[]{(byte) 0xff, '\n', (byte) 0xe2}, 3);
        framing.finish();
        assertEquals(List.of("\ufffd", "\ufffd"), result);
    }
}
