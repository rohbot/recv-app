package cc.roh.soundreceiver;

import android.content.pm.ActivityInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.lang.reflect.Field;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the actual Android UI with protocol fixtures; does not emulate BLE radio hardware. */
public class MonitorActivityTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private static final String DEVICE = "AA:BB:CC:DD:EE:FF";
    private static byte[] event(long sequence) {
        return java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put((byte)1).put((byte)1).putShort((short)64).putInt(0x12345678)
                .putInt((int)sequence).putInt(10000).putShort((short)-4000).putShort((short)-6000).array();
    }
    private static byte[] sentinel() {
        byte[] value = event(0); value[1] = 0;
        value[16] = 0x20; value[17] = (byte)0xd1;
        value[18] = 0x20; value[19] = (byte)0xd1;
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(activity.getActivity());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void ui(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void render() {
        // Production rendering batches incoming BLE values over 50 ms.
        android.os.SystemClock.sleep(100);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private String label(String name) { return this.<TextView>field(name).getText().toString(); }
    private void feed() {
        ui(() -> {
            this.<CheckBox>field("beepEnabled").setChecked(false);
            activity.getActivity().onOpened(DEVICE);
            activity.getActivity().onValue(DEVICE, sentinel(), true);
            activity.getActivity().onValue(DEVICE, event(1), false);
            activity.getActivity().onValue(DEVICE, event(1), true);
        });
        render();
    }

    @Test public void eventUpdatesMetricsIdentityAndRawLog() {
        feed();
        ui(() -> {
            assertEquals("1", label("count"));
            assertTrue(label("level").contains("40.0"));
            assertTrue(label("floor").contains("60.0"));
            assertTrue(label("rise").contains("+20.0"));
            assertTrue(label("latest").contains("#1 · AA:BB:CC:DD:EE:FF"));
            assertTrue(label("duration").contains("64 ms confirmation"));
            assertEquals("SoundMeter · " + DEVICE, label("identity"));
            assertEquals(2, this.<ListView>field("terminal").getCount());
            assertTrue(this.<Button>field("save").isEnabled());
        });
    }

    @Test public void filterDoesNotLoseRawLinesAndClearResetsSummary() {
        feed();
        ui(() -> this.<CheckBox>field("eventsOnly").setChecked(true));
        render();
        ui(() -> {
            assertEquals(1, this.<ListView>field("terminal").getCount());
            assertTrue(this.<MonitorLog>field("log").export().contains("No event yet"));
            this.<Button>field("clear").performClick();
        });
        render();
        ui(() -> {
            assertEquals("0", label("count"));
            assertEquals("— dBFS", label("level"));
            assertEquals(0, this.<ListView>field("terminal").getCount());
            assertFalse(this.<Button>field("save").isEnabled());
        });
    }

    @Test public void historicalSnapshotDoesNotCountAndUnsupportedPacketKeepsHandlerAlive() {
        ui(() -> {
            this.<CheckBox>field("beepEnabled").setChecked(false);
            activity.getActivity().onValue(DEVICE, event(10), true);
        });
        render();
        ui(() -> {
            assertEquals("0", label("count"));
            assertTrue(label("latest").contains("Historical"));
            byte[] bad = event(11); bad[0] = 2;
            activity.getActivity().onValue(DEVICE, bad, false);
            assertTrue(label("notice").contains("Unsupported protocol"));
            activity.getActivity().onValue(DEVICE, event(11), false);
        });
        render();
        ui(() -> assertEquals("1", label("count")));
    }

    @Test public void reconnectReadDoesNotReplayNotificationAndQuietStateIsIndependent() {
        feed();
        ui(() -> {
            activity.getActivity().onState("BLE disconnected · reconnecting in 1 s");
            assertTrue(label("state").contains("reconnecting"));
            activity.getActivity().onOpened(DEVICE);
            activity.getActivity().onValue(DEVICE, event(1), true);
            assertTrue(label("state").contains("Connected"));
            assertTrue(label("notice").contains("quiet periods are normal"));
        });
        render();
        ui(() -> assertEquals("1", label("count")));
    }

    @Test public void rotationPreservesSessionAndControls() {
        feed();
        MainActivity before = activity.getActivity();
        ui(() -> {
            this.<CheckBox>field("autoScroll").setChecked(false);
            before.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (before.getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(50);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        ui(() -> {
            assertFalse(before.isDestroyed());
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    before.getResources().getConfiguration().orientation);
            assertEquals("1", label("count"));
            assertFalse(this.<CheckBox>field("autoScroll").isChecked());
            assertEquals(2, this.<ListView>field("terminal").getCount());
            before.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test public void documentExportWritesSnapshotIncludingFilteredLines() throws Exception {
        feed();
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "export-test.txt");
        if (file.exists()) assertTrue(file.delete());
        Intent result = new Intent().setData(Uri.fromFile(file));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        IntentFilter documentFilter = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        documentFilter.addCategory(Intent.CATEGORY_OPENABLE);
        documentFilter.addDataType("text/plain");
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(documentFilter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK, result), true);
        try {
            ui(() -> {
                this.<CheckBox>field("eventsOnly").setChecked(true);
                this.<Button>field("save").performClick();
            });
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while ((!file.exists() || file.length() == 0) && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(50);
            assertTrue("Export should create a document", file.exists());
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(content.contains("No event yet"));
            assertTrue(content.contains("SOUND EVENT device=" + DEVICE));
            assertEquals(2, content.split("\n").length);
        } finally {
            instrumentation.removeMonitor(monitor);
            file.delete();
        }
    }
}
