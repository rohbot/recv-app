package cc.roh.soundreceiver;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.time.Instant;
import java.util.List;

/** Owns BLE and the latched alarm independently of any Activity. All state is on the main looper. */
public final class MonitoringService extends Service implements BleConnection.Listener {
    static final String START = "cc.roh.soundreceiver.START_MONITORING";
    static final String STOP = "cc.roh.soundreceiver.STOP_MONITORING";
    static final String TEST_ALARM = "cc.roh.soundreceiver.TEST_ALARM";
    interface Observer {
        void onMonitorChanged();
        void onDevices(List<BleConnection.Peripheral> devices);
    }
    public final class LocalBinder extends Binder { MonitoringService service() { return MonitoringService.this; } }
    final MonitorLog log = new MonitorLog();
    BleConnection connection;
    MonitorNotifications notifications;
    private LoopingAlarm alarm;
    private Observer observer;
    private boolean foregroundStarted, connectionStarted, destroyed;
    boolean monitoring, connected, alarmActive, alarmArmed;
    private String alarmFailure;
    String phase = "Disconnected", identity = "No SoundMeter connected";
    String notice = "Ready to connect. New detections sound an alarm until you press Silence alarm.";
    private final BroadcastReceiver bluetoothState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF)
                connection.close("Bluetooth is off. The active alarm, if any, continues until silenced.");
        }
    };
    private SharedPreferences preferences() { return getSharedPreferences("monitoring", MODE_PRIVATE); }
    @Override public void onCreate() {
        super.onCreate();
        alarmArmed = preferences().getBoolean("alarm_armed", true);
        notifications = new MonitorNotifications(this);
        alarm = new LoopingAlarm(this, () -> { if (!destroyed) { updateOngoing(); changed(); } });
        connection = new BleConnection(this, this);
        ContextCompat.registerReceiver(this, bluetoothState, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    void attach(Observer value) { observer = value; changed(); }
    void detach(Observer value) { if (observer == value) observer = null; }
    boolean alarmPlaying() { return alarm.isPlaying(); }
    String alarmStatus() {
        if (!alarmArmed) return "Alarm disarmed · new detections will not sound · press Arm alarm";
        if (alarmFailure != null) return alarmFailure;
        return alarmActive ? alarm.status() : "Alarm armed · waiting for a new detection · Alarm volume";
    }
    void testAlarm() {
        if (!alarmArmed) { changed(); return; }
        try { ContextCompat.startForegroundService(this, new Intent(this, MonitoringService.class).setAction(TEST_ALARM)); }
        catch (RuntimeException error) { alarmFailure = "Could not start alarm: " + error.getMessage(); changed(); }
    }
    boolean notificationPermissionAsked() { return preferences().getBoolean("notification_permission_asked", false); }
    void notificationPermissionAsked(boolean value) { preferences().edit().putBoolean("notification_permission_asked", value).apply(); }

    void beginMonitoring(BleConnection.Peripheral device) {
        monitoring = true;
        try {
            ContextCompat.startForegroundService(this, new Intent(this, MonitoringService.class).setAction(START)
                    .putExtra("address", device.id()).putExtra("name", device.name()));
        } catch (RuntimeException error) { connection.close("Could not start background monitoring: " + error.getMessage()); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopMonitoring("Disconnected. Press Silence alarm to stop any active alarm.");
            return alarmActive ? START_STICKY : START_NOT_STICKY;
        }
        if (intent != null && TEST_ALARM.equals(intent.getAction())) {
            raiseAlarm(); changed();
            if (!monitoring && !alarmActive) stopSelf();
            return alarmActive || monitoring ? START_STICKY : START_NOT_STICKY;
        }
        if (connectionStarted && connection.isBusy()) return START_STICKY;
        String address = intent == null ? preferences().getString("address", null) : intent.getStringExtra("address");
        String name = intent == null ? preferences().getString("name", "SoundMeter") : intent.getStringExtra("name");
        boolean restoreAlarm = intent == null && alarmArmed && preferences().getBoolean("alarm_active", false);
        boolean resumeBle = address != null && (intent != null || preferences().getBoolean("active", false));
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            resumeBle = false;
        monitoring = resumeBle;
        alarmActive = alarmActive || restoreAlarm;
        if (!monitoring && !alarmActive) {
            stopMonitoring("Monitoring stopped. Tap Connect SoundMeter to start.");
            return START_NOT_STICKY;
        }
        try {
            promote(); // Declare audio playback before requesting audio focus, including in the background.
            if (restoreAlarm) alarm.start();
            preferences().edit().putBoolean("active", monitoring).putBoolean("alarm_active", alarmActive).apply();
            if (monitoring) {
                preferences().edit().putString("address", address).putString("name", name).apply();
                BluetoothManager manager = getSystemService(BluetoothManager.class);
                if (manager == null || manager.getAdapter() == null) throw new IllegalStateException("Bluetooth is unavailable");
                connectionStarted = true;
                connection.open(new BleConnection.Peripheral(address, name == null ? "SoundMeter" : name, 0, manager.getAdapter().getRemoteDevice(address)));
            }
        } catch (RuntimeException error) {
            stopMonitoring("Could not start monitoring: " + error.getMessage());
            if (!alarmActive) return START_NOT_STICKY;
        }
        changed();
        return START_STICKY;
    }
    private void raiseAlarm() {
        if (!alarmArmed || alarmActive) return;
        alarmActive = true; alarmFailure = null;
        try {
            promote();
            preferences().edit().putBoolean("alarm_active", true).apply();
            alarm.start();
        } catch (RuntimeException error) {
            alarmActive = false;
            alarmFailure = "Could not start alarm: " + error.getMessage();
        }
    }
    void silenceAlarm() {
        alarmActive = alarmArmed = false; alarmFailure = null;
        preferences().edit().putBoolean("alarm_active", false).putBoolean("alarm_armed", false).apply();
        alarm.stop();
        getSystemService(android.app.NotificationManager.class).cancel(MonitorNotifications.EVENT_ID);
        maintainLifetime(); changed();
    }
    void armAlarm() {
        alarmArmed = true; alarmFailure = null;
        preferences().edit().putBoolean("alarm_armed", true).apply();
        // Arming does not replay an event received while disarmed.
        updateOngoing(); changed();
    }
    void stopMonitoring(String reason) { connection.close(reason); }
    private void promote() {
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (monitoring) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (alarmActive) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        }
        ServiceCompat.startForeground(this, MonitorNotifications.MONITOR_ID,
                notifications.ongoing(statusText(), monitoring, alarmActive), types);
        foregroundStarted = true;
    }
    private void maintainLifetime() {
        if (monitoring || alarmActive) {
            try { promote(); } catch (RuntimeException error) { alarmFailure = "Service update failed: " + error.getMessage(); }
        } else {
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            stopSelf(); // Bound UI may still export the retained log.
        }
    }
    @Override public void onDevices(List<BleConnection.Peripheral> devices) {
        if (observer != null) observer.onDevices(devices);
    }
    @Override public void onState(String state) {
        phase = notice = state; connected = false;
        updateOngoing(); changed();
    }
    @Override public void onOpened(String device) {
        connected = true; phase = "Connected · subscribed";
        identity = "SoundMeter · " + device;
        notice = "Notifications enabled. Reading latest snapshot; quiet periods are normal.";
        updateOngoing(); changed();
    }
    @Override public void onValue(String device, byte[] bytes, boolean read) {
        MonitorLog.Entry entry = log.receive(device, bytes, read, Instant.now());
        if (entry == null) {
            if (read && connected) notice = "Connected and subscribed. Snapshot already seen; quiet periods are normal.";
            changed(); return;
        }
        if (entry.warning()) notice = entry.text();
        else if (entry.isEvent()) {
            if (monitoring && entry.shouldAlert()) {
                raiseAlarm();
                notifications.showEvent(entry);
            }
            notice = entry.historical() ? "Showing the board’s retained latest event, not a new alert."
                    : alarmArmed ? "Sound event received. Press Silence alarm to stop and disarm the alarm."
                    : "Sound event received while disarmed. Press Arm alarm to enable future alarms.";
        } else notice = "Connected and subscribed. No event yet; quiet periods are normal.";
        updateOngoing(); changed();
    }
    @Override public void onClosed(String reason) {
        connected = monitoring = connectionStarted = false;
        phase = "Disconnected"; identity = "No SoundMeter connected"; notice = reason;
        preferences().edit().putBoolean("active", false).apply();
        // BLE loss and Disconnect do not acknowledge an already triggered alarm.
        maintainLifetime(); changed();
    }
    void clear() { log.clear(); updateOngoing(); changed(); }
    private String statusText() {
        return (alarmActive || !alarmArmed ? alarmStatus() + " · " : "") + phase + " · " + log.eventCount + " new events";
    }
    private void updateOngoing() {
        if (foregroundStarted && !destroyed) notifications.updateOngoing(statusText(), monitoring, alarmActive);
    }
    private void changed() { if (observer != null) observer.onMonitorChanged(); }
    @Override public void onDestroy() {
        destroyed = true; observer = null;
        unregisterReceiver(bluetoothState);
        connection.destroy(); alarm.release();
        super.onDestroy();
    }
}
