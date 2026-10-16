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
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the actual Android UI with protocol fixtures; does not emulate BLE radio hardware. */
public class MonitorActivityTest {
    @Rule(order = 1) public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    @Rule(order = 0) public androidx.test.rule.GrantPermissionRule permissions = androidx.test.rule.GrantPermissionRule.grant(testPermissions());
    private static String[] testPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 33) return new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.POST_NOTIFICATIONS};
        if (android.os.Build.VERSION.SDK_INT >= 31) return new String[]{android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_SCAN};
        return new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private static int bootCounter = 0x12345678;
    private static final String DEVICE = "AA:BB:CC:DD:EE:FF";
    private static byte[] event(long sequence) {
        return java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put((byte)1).put((byte)1).putShort((short)64).putInt(bootCounter)
                .putInt((int)sequence).putInt(10000).putShort((short)-4000).putShort((short)-6000).array();
    }
    private static byte[] sentinel() {
        byte[] value = event(0); value[1] = 0;
        value[16] = 0x20; value[17] = (byte)0xd1;
        value[18] = 0x20; value[19] = (byte)0xd1;
        return value;
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) { return field(activity.getActivity(), name); }
    @SuppressWarnings("unchecked")
    private <T> T field(MainActivity target, String name) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private boolean originalArmed;
    private MonitoringService monitor() { return field("monitor"); }
    @Before public void awaitService() {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (monitor() == null && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25);
        assertNotNull("UI should bind to monitoring service", monitor());
        ui(() -> {
            originalArmed = monitor().alarmArmed;
            monitor().stopMonitoring("Test setup");
            monitor().clear();
            monitor().silenceAlarm();
            monitor().armAlarm();
            activity.getActivity().getSystemService(android.app.NotificationManager.class).cancel(MonitorNotifications.EVENT_ID);
            bootCounter++;
        });
        render();
    }
    @After public void stopService() {
        ui(() -> {
            if (monitor() != null) {
                monitor().silenceAlarm(); monitor().stopMonitoring("Tests finished");
                if (originalArmed) monitor().armAlarm();
            }
            activity.getActivity().getSystemService(android.app.NotificationManager.class).cancel(MonitorNotifications.EVENT_ID);
        });
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
            monitor().onOpened(DEVICE);
            monitor().onValue(DEVICE, sentinel(), true);
            monitor().onValue(DEVICE, event(1), false);
            monitor().onValue(DEVICE, event(1), true);
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
            monitor().onValue(DEVICE, event(10), true);
        });
        render();
        ui(() -> {
            assertEquals("0", label("count"));
            assertTrue(label("latest").contains("Historical"));
            byte[] bad = event(11); bad[0] = 2;
            monitor().onValue(DEVICE, bad, false);
            assertTrue(label("notice").contains("Unsupported protocol"));
            monitor().onValue(DEVICE, event(11), false);
        });
        render();
        ui(() -> assertEquals("1", label("count")));
    }

    @Test public void reconnectReadDoesNotReplayNotificationAndQuietStateIsIndependent() {
        feed();
        ui(() -> {
            monitor().onState("BLE disconnected · reconnecting in 1 s");
            assertTrue(label("state").contains("reconnecting"));
            monitor().onOpened(DEVICE);
            monitor().onValue(DEVICE, event(1), true);
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
    private android.app.Notification activeNotification(int id) {
        android.app.NotificationManager manager = activity.getActivity().getSystemService(android.app.NotificationManager.class);
        for (android.service.notification.StatusBarNotification n : manager.getActiveNotifications())
            if (n.getId() == id) return n.getNotification();
        return null;
    }

    @Test public void backgroundServiceAlertsAfterActivityDestroyedAndNotificationCanStopIt() throws Exception {
        MonitoringService service = monitor();
        android.bluetooth.BluetoothDevice device = activity.getActivity().getSystemService(android.bluetooth.BluetoothManager.class)
                .getAdapter().getRemoteDevice(DEVICE);
        ui(() -> service.beginMonitoring(new BleConnection.Peripheral(DEVICE, "Test SoundMeter", -50, device)));
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (activeNotification(MonitorNotifications.MONITOR_ID) == null && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        assertNotNull("Must promote to foreground while monitoring", activeNotification(MonitorNotifications.MONITOR_ID));
        assertTrue((activeNotification(MonitorNotifications.MONITOR_ID).flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0);
        ui(() -> {
            service.onValue(DEVICE, event(100), true); // Historical; never alert.
            service.onValue(DEVICE, event(100), false); // Duplicate; never alert.
        });
        assertNull(activeNotification(MonitorNotifications.EVENT_ID));

        ui(() -> activity.getActivity().finish());
        deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!activity.getActivity().isDestroyed() && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        assertTrue(activity.getActivity().isDestroyed());
        ui(() -> service.onValue(DEVICE, event(101), false));
        assertTrue(service.monitoring);
        deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        while (activeNotification(MonitorNotifications.EVENT_ID) == null && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        android.app.Notification alert = activeNotification(MonitorNotifications.EVENT_ID);
        assertNotNull("An event received without an Activity must notify", alert);
        assertEquals("Sound detected", alert.extras.getString(android.app.Notification.EXTRA_TITLE));
        assertTrue(alert.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().contains("event #101"));
        android.app.NotificationChannel channel = service.getSystemService(android.app.NotificationManager.class)
                .getNotificationChannel(MonitorNotifications.EVENT_CHANNEL);
        assertTrue(channel.shouldVibrate());
        assertArrayEquals(new long[]{0,180,80,180}, channel.getVibrationPattern());
        ui(() -> service.onValue(DEVICE, event(101), true));
        assertEquals(1, service.log.eventCount);

        MainActivity reopened = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (field(reopened, "monitor") == null && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        assertSame("Reopened UI must reattach to the existing session", service, field(reopened, "monitor"));
        assertTrue("The event must latch an alarm", service.alarmActive);
        android.os.SystemClock.sleep(1200); // More than the old one-shot beep and one full cadence.
        assertTrue("The sound must still be looping", service.alarmPlaying());
        assertTrue(this.<Button>field(reopened, "silenceAlarm").isEnabled());
        activeNotification(MonitorNotifications.MONITOR_ID).actions[0].actionIntent.send();
        deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (service.monitoring && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25);
        assertFalse("Disconnect notification must stop background monitoring", service.monitoring);
        assertTrue("Disconnect must not silence an acknowledged-only alarm", service.alarmActive);
        assertTrue(service.alarmPlaying());
        assertNotNull(activeNotification(MonitorNotifications.MONITOR_ID));
        ui(() -> this.<Button>field(reopened, "silenceAlarm").performClick());
        assertFalse(service.alarmActive);
        assertFalse(service.alarmPlaying());
        ui(reopened::finish);
        deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        while (activeNotification(MonitorNotifications.MONITOR_ID) != null && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        assertNull(activeNotification(MonitorNotifications.MONITOR_ID));
    }

    @Test public void silenceDisarmsUntilArmButtonIsPressedAndDoesNotReplaySkippedEvents() {
        MonitoringService service = monitor();
        android.bluetooth.BluetoothDevice device = service.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter().getRemoteDevice(DEVICE);
        ui(() -> service.beginMonitoring(new BleConnection.Peripheral(DEVICE, "Test SoundMeter", -50, device)));
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (activeNotification(MonitorNotifications.MONITOR_ID) == null && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        ui(() -> {
            service.onValue(DEVICE, sentinel(), true);
            service.onValue(DEVICE, event(1), false);
            assertTrue(service.alarmActive);
            service.clear();
            assertTrue("Clear must not acknowledge the alarm", service.alarmActive);
            this.<Button>field("silenceAlarm").performClick();
            assertFalse(service.alarmActive);
            assertFalse(service.alarmArmed);
            assertEquals("Arm alarm", label("silenceAlarm"));
            assertFalse(this.<Button>field("testAlarm").isEnabled());
            assertTrue("Silencing must keep BLE monitoring active", service.monitoring);
            service.onValue(DEVICE, event(1), true);
            assertFalse("A duplicate read must not retrigger a silenced alarm", service.alarmActive);
            service.onValue(DEVICE, event(2), false);
            assertFalse("New detections while disarmed must not start the alarm", service.alarmActive);
            assertEquals(1, service.log.eventCount); // Clear reset the count, but monitoring still receives events.
            service.testAlarm();
            assertFalse("Test alarm is unavailable until armed", service.alarmActive);
            this.<Button>field("silenceAlarm").performClick();
            assertTrue(service.alarmArmed);
            assertEquals("Silence alarm", label("silenceAlarm"));
            assertFalse("Arming must not replay the most recent detection", service.alarmActive);
            service.onValue(DEVICE, event(2), true);
            assertFalse("A read of a detection received while disarmed stays silent", service.alarmActive);
            service.onValue(DEVICE, event(3), false);
            assertTrue("A new detection after explicit arming must sound", service.alarmActive);
            service.silenceAlarm();
        });
    }

    @Test public void testAlarmRunsWithoutBleUntilSilenceButtonIsPressed() {
        MonitoringService service = monitor();
        ui(service::testAlarm);
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!service.alarmActive && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(25);
        render();
        ui(() -> {
            assertTrue(service.alarmActive);
            assertFalse(service.monitoring);
            assertTrue(service.alarmPlaying());
            if (android.os.Build.VERSION.SDK_INT >= 29)
                assertTrue((service.getForegroundServiceType() & android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0);
            this.<Button>field("silenceAlarm").performClick();
            assertFalse(service.alarmActive);
            assertFalse(service.alarmPlaying());
        });
    }

    @Test public void disarmedStateSurvivesServiceRecreation() {
        MonitoringService previous = monitor();
        ui(() -> {
            this.<Button>field("silenceAlarm").performClick(); // Can also disarm before an alarm fires.
            assertFalse(previous.alarmArmed);
            assertEquals("Arm alarm", label("silenceAlarm"));
            activity.getActivity().finish();
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (!activity.getActivity().isDestroyed() && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(25);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        MainActivity reopened = (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(previous, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (field(reopened, "monitor") == null && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(25);
            MonitoringService restored = field(reopened, "monitor");
            assertNotNull(restored);
            assertNotSame("This must check saved state, not the old service instance", previous, restored);
            ui(() -> {
                assertFalse(restored.alarmArmed);
                assertEquals("Arm alarm", this.<Button>field(reopened, "silenceAlarm").getText().toString());
                restored.onValue(DEVICE, event(200), false);
                assertFalse(restored.alarmActive);
                this.<Button>field(reopened, "silenceAlarm").performClick();
                assertTrue(restored.alarmArmed);
                assertFalse(restored.alarmActive);
            });
        } finally { ui(reopened::finish); }
    }

}
