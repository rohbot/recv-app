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

/** Exercises the actual Android UI with protocol fixtures; does not emulate USB hardware. */
public class MonitorActivityTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private static final String EVENT = "SOUND EVENT from AA:BB:CC:DD:EE:FF #42 level=-23.4 dBFS floor=-60.1 dBFS rise=36.7 dB duration=80 ms uptime=123456 ms";

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
        // Production rendering batches incoming serial lines over 50 ms.
        android.os.SystemClock.sleep(100);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private String label(String name) { return this.<TextView>field(name).getText().toString(); }
    private void feed() {
        ui(() -> {
            this.<CheckBox>field("beepEnabled").setChecked(false);
            activity.getActivity().onLines(List.of(
                    new UsbConnection.WireLine("ESP-NOW receiver 01:23:45:67:89:AB, channel 6", Instant.now()),
                    new UsbConnection.WireLine("boot message", Instant.now()),
                    new UsbConnection.WireLine(EVENT, Instant.now())));
        });
        render();
    }

    @Test public void eventUpdatesMetricsIdentityAndRawLog() {
        feed();
        ui(() -> {
            assertEquals("1", label("count"));
            assertTrue(label("level").contains("23.4"));
            assertTrue(label("floor").contains("60.1"));
            assertTrue(label("rise").contains("+36.7"));
            assertTrue(label("latest").contains("#42 from AA:BB:CC:DD:EE:FF"));
            assertTrue(label("duration").contains("80 ms confirmation"));
            assertEquals("01:23:45:67:89:AB · CH 6", label("identity"));
            assertEquals(3, this.<ListView>field("terminal").getCount());
            assertTrue(this.<Button>field("save").isEnabled());
        });
    }

    @Test public void filterDoesNotLoseRawLinesAndClearResetsSummary() {
        feed();
        ui(() -> this.<CheckBox>field("eventsOnly").setChecked(true));
        render();
        ui(() -> {
            assertEquals(1, this.<ListView>field("terminal").getCount());
            assertTrue(this.<MonitorLog>field("log").export().contains("boot message"));
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

    @Test public void noDeviceShowsActionableMessageAndAllowsRetry() {
        ui(() -> {
            this.<Button>field("connect").performClick();
            assertTrue(label("notice").contains("No USB device detected"));
            assertTrue(this.<Button>field("connect").isEnabled());
            assertFalse(this.<Button>field("disconnect").isEnabled());
        });
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
            assertEquals(3, this.<ListView>field("terminal").getCount());
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
            assertTrue(content.contains("\tboot message\n"));
            assertTrue(content.contains("\t" + EVENT + "\n"));
            assertEquals(3, content.split("\n").length);
        } finally {
            instrumentation.removeMonitor(monitor);
            file.delete();
        }
    }
}
