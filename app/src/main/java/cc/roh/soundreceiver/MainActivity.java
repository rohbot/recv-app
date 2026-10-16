package cc.roh.soundreceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.widget.ArrayAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements BleConnection.Listener {
    private static final int BG = 0xff101a23, PANEL = 0xff172630, TEXT = 0xffedf4f5,
            MUTED = 0xffa0b4c1, TEAL = 0xff62dcc5, ORANGE = 0xffffbd8a;
    private static final int EXPORT = 40;
    private static final int BLE_PERMISSIONS = 41, ENABLE_BLUETOOTH = 42;
    private final MonitorLog log = new MonitorLog();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final EventTone tone = new EventTone();
    private final DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private BleConnection connection;
    private AlertDialog picker;
    private ArrayAdapter<String> deviceAdapter;
    private List<BleConnection.Peripheral> peripherals = new ArrayList<>();
    private String phase = "Disconnected";
    private boolean foreground, enablingBluetooth;
    private boolean waitingPermission, connected, closing, exporting, destroyed, renderPending;
    private String exportSnapshot;
    private TextView state, identity, notice, count, level, floor, rise, latest, duration, lineCount, lastMessage, empty, audioStatus;
    private Button connect, disconnect, save, clear;
    private CheckBox autoScroll, eventsOnly, beepEnabled;
    private ListView terminal;
    private LogAdapter adapter;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF)
                connection.close("Bluetooth is off. Turn it on and tap Connect SoundMeter.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connection = new BleConnection(this, this);
        buildUi();
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        updateState();
        render();
    }

    @Override protected void onStart() { super.onStart(); foreground = true; }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(22), dp(20), dp(24));
        page.addView(body);
        setContentView(page);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                page.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        TextView brand = text("▂▆█▄▂   SOUND EVENT / RECEIVER", 13, TEAL);
        brand.setTypeface(null, Typeface.BOLD);
        body.addView(brand);
        addText(body, "ESP32-S3 · BLUETOOTH LOW ENERGY", 11, MUTED, 20);
        TextView title = addText(body, "Listen to what’s\nhappening.", 32, TEXT, 8);
        title.setTypeface(null, Typeface.BOLD);
        addText(body, "Connect your SoundMeter wirelessly to view confirmed sound events.", 15, MUTED, 8);
        LinearLayout controls = row();
        connect = button("Connect SoundMeter", true, v -> connectBle());
        disconnect = button("Disconnect", false, v -> disconnect("Disconnected. Retained messages are kept until you clear or close the app."));
        controls.addView(connect, weight(2));
        controls.addView(disconnect, weight(1));
        add(body, controls, 18);

        state = addText(body, "Disconnected · BLE", 13, TEAL, 12);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        identity = addText(body, "No SoundMeter connected", 12, MUTED, 6);
        identity.setTextIsSelectable(true);
        notice = addText(body, "Ready to connect. Power your SoundMeter and allow Bluetooth access when asked.", 13, MUTED, 10);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout first = row();
        TextView[] events = metric(first, "NEW EVENTS RECEIVED", "0", "Waiting for the first event", false);
        count = events[0]; latest = events[1];
        TextView[] sound = metric(first, "LATEST SOUND LEVEL", "— dBFS", "RMS level at detection", false);
        level = sound[0];
        add(body, first, 20);
        LinearLayout second = row();
        TextView[] noise = metric(second, "NOISE FLOOR", "— dBFS", "Background at latest event", false);
        floor = noise[0];
        TextView[] above = metric(second, "ABOVE THE FLOOR", "— dB", "No event received yet", true);
        rise = above[0]; duration = above[1];
        add(body, second, 8);

        LinearLayout panel = column();
        panel.setPadding(dp(12), dp(14), dp(12), dp(12));
        panel.setBackground(shape(PANEL, 12));
        TextView stream = text(">_  Event stream", 18, TEXT);
        stream.setTypeface(null, Typeface.BOLD);
        panel.addView(stream);
        lineCount = addText(panel, "0 lines", 12, MUTED, 5);
        LinearLayout actions = row();
        save = button("Download log", false, v -> exportLog());
        clear = button("Clear", false, v -> { log.clear(); scheduleRender(); });
        actions.addView(save, weight(1)); actions.addView(clear, weight(1));
        add(panel, actions, 6);
        LinearLayout filters = row();
        autoScroll = check("Auto-scroll", true);
        eventsOnly = check("Events only", false);
        filters.addView(autoScroll, weight(1)); filters.addView(eventsOnly, weight(1));
        panel.addView(filters);
        LinearLayout audio = row();
        beepEnabled = check("Beep on events", true);
        beepEnabled.setOnCheckedChangeListener((b, enabled) -> {
            if (!enabled) tone.mute();
            audioStatus.setText(enabled ? "Event beeps ready · uses media volume" : "Event beeps muted");
        });
        audio.addView(beepEnabled, weight(1));
        audio.addView(button("Test beep", false, v -> playTone()), weight(1));
        panel.addView(audio);
        audioStatus = addText(panel, "Event beeps ready · uses media volume", 12, MUTED, 0);
        empty = addText(panel, "Ready when you are. Connect your SoundMeter over Bluetooth.", 14, MUTED, 12);
        empty.setPadding(dp(8), dp(16), dp(8), dp(16));
        terminal = new ListView(this);
        terminal.setBackgroundColor(0xff0c151c);
        terminal.setDividerHeight(dp(1));
        terminal.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        terminal.setOnTouchListener((v, event) -> {
            // Let a drag scroll the log independently of the surrounding page.
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        adapter = new LogAdapter();
        terminal.setAdapter(adapter);
        panel.addView(terminal, new LinearLayout.LayoutParams(-1, terminalHeight()));
        lastMessage = addText(panel, "No data yet", 11, MUTED, 10);
        add(body, panel, 20);
        addText(body, "Getting connected", 15, TEXT, 20);
        addText(body, "Power a board running the SoundMeter BLE firmware. Tap Connect SoundMeter, allow Nearby devices access, then select your board. Wait five seconds for noise learning, then make a loud sound. Only one client can connect at a time.", 13, MUTED, 8);
        addText(body, "Quiet is normal: there is no heartbeat or continuous audio stream. The first latest-event snapshot is historical and does not beep. Monitoring keeps the screen awake; leaving the app disconnects BLE. Rotation keeps your session.", 13, MUTED, 8);
        addText(body, "LOCAL ONLY · Latest 1,000 log entries kept in memory. No internet access or automatic storage. Download log saves to a location you choose. Session data is lost when the app is destroyed.", 12, MUTED, 12);
        add(body, button("Open-source licenses", false, v -> showLicenses()), 12);
    }

    private void showLicenses() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                getAssets().open("third-party-notices.txt"), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
            new AlertDialog.Builder(this).setTitle("Open-source licenses")
                    .setMessage(content).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) { notice.setText("Could not read license notices."); }
    }

    private String[] requiredPermissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasBlePermissions() {
        for (String permission : requiredPermissions())
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @SuppressLint("MissingPermission")
    private void connectBle() {
        if (connection.isBusy() || waitingPermission || enablingBluetooth) return;
        if (!connection.supported()) { notice.setText("Bluetooth LE is unavailable on this device."); return; }
        if (!hasBlePermissions()) {
            waitingPermission = true;
            notice.setText(Build.VERSION.SDK_INT >= 31 ? "Allow Nearby devices access to scan and connect."
                    : "Android 8–11 requires Location permission for Bluetooth scanning.");
            updateState();
            requestPermissions(requiredPermissions(), BLE_PERMISSIONS);
            return;
        }
        try {
            if (!connection.enabled()) {
                enablingBluetooth = true;
                updateState();
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH);
                return;
            }
            if (Build.VERSION.SDK_INT < 31) {
                LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (location == null || (!location.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) {
                    notice.setText("Turn on Android Location services for BLE scanning on Android 8–11, then try again.");
                    return;
                }
            }
            deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
            peripherals.clear();
            picker = new AlertDialog.Builder(this).setTitle("Searching for SoundMeter…")
                    .setAdapter(deviceAdapter, (dialog, which) -> {
                        BleConnection.Peripheral device = peripherals.get(which);
                        // Clear the reference before open: programmatic dismiss must not cancel a selected device.
                        picker = null;
                        identity.setText(device.name() + " · " + device.id());
                        connection.open(device);
                    }).setNegativeButton("Cancel", (dialog, which) -> connection.close("Scan cancelled."))
                    .setOnCancelListener(dialog -> connection.close("Scan cancelled.")).create();
            picker.show();
            connection.scan();
        } catch (RuntimeException error) {
            enablingBluetooth = false;
            connection.close("Could not start Bluetooth: " + error.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != BLE_PERMISSIONS) return;
        boolean requested = waitingPermission;
        waitingPermission = false;
        updateState();
        if (hasBlePermissions()) { if (requested && foreground) connectBle(); }
        else notice.setText("Bluetooth permission denied. Tap Connect SoundMeter to retry, or allow access in Android app settings.");
    }

    @Override public void onDevices(List<BleConnection.Peripheral> devices) {
        if (picker == null) return;
        peripherals = devices;
        deviceAdapter.clear();
        for (BleConnection.Peripheral device : devices) deviceAdapter.add(device.label());
        picker.setTitle("Choose SoundMeter");
    }

    @Override public void onState(String value) {
        phase = value;
        connected = false;
        notice.setText(value);
        if (picker != null && value.startsWith("Scan finished")) picker.setTitle("Choose SoundMeter · scan finished");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
    }

    @Override public void onOpened(String device) {
        connected = true;
        phase = "Connected · subscribed";
        identity.setText("SoundMeter · " + device);
        notice.setText("Notifications enabled. Reading latest snapshot; quiet periods are normal.");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
        scheduleRender();
    }

    @Override public void onValue(String device, byte[] bytes, boolean read) {
        MonitorLog.Entry entry = log.receive(device, bytes, read, Instant.now());
        if (entry == null) {
            if (read && connected) notice.setText("Connected and subscribed. Snapshot already seen; quiet periods are normal.");
            return;
        }
        if (entry.warning()) notice.setText(entry.text());
        else if (entry.isEvent()) {
            if (!entry.historical() && beepEnabled.isChecked()) playTone();
            notice.setText(entry.historical() ? "Showing the board’s retained latest event, not a new alert."
                    : "Sound event received. Quiet periods are normal. Estimated missed detections: " + log.missedCount);
        } else notice.setText("Connected and subscribed. No event yet; quiet periods are normal.");
        scheduleRender();
    }

    @Override public void onClosed(String reason) {
        connected = closing = false;
        phase = "Disconnected";
        if (picker != null) { picker.dismiss(); picker = null; }
        identity.setText("No SoundMeter connected");
        notice.setText(reason);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
        scheduleRender();
    }

    private void disconnect(String reason) { connection.close(reason); }

    private void updateState() {
        String label = waitingPermission ? "Waiting for Bluetooth permission…" : enablingBluetooth ? "Enable Bluetooth…" : phase;
        state.setText(label + " · BLE");
        connect.setEnabled(!connection.isBusy() && !waitingPermission && !enablingBluetooth);
        disconnect.setEnabled(connection.isBusy());
    }

    private void scheduleRender() {
        if (renderPending) return;
        renderPending = true;
        main.postDelayed(() -> { renderPending = false; if (!destroyed) render(); }, 50);
    }

    private void render() {
        count.setText(Long.toString(log.eventCount));
        ReceiverProtocol.Message event = log.latest;
        level.setText(event == null ? "— dBFS" : String.format(Locale.getDefault(), "%.1f dBFS", event.level()));
        floor.setText(event == null ? "— dBFS" : String.format(Locale.getDefault(), "%.1f dBFS", event.floor()));
        rise.setText(event == null ? "— dB" : String.format(Locale.getDefault(), "%+.1f dB", event.rise()));
        latest.setText(event == null ? "Waiting for the first event" : (log.latestHistorical ? "Historical · " : "") + "#" + event.sequence() + " · " + log.latestDevice + " · boot " + event.bootId());
        duration.setText(event == null ? "No event received yet" : event.duration() + (event.duration() == 65535 ? "+" : "") + " ms confirmation · " + clock.format(log.latestEventTime));
        lineCount.setText(log.totalLines + " lines" + (log.totalLines > MonitorLog.MAX_LINES ? " · 1,000 retained" : ""));
        lastMessage.setText(log.last == null ? "No data yet" : "Last message " + clock.format(log.last.time()));
        save.setEnabled(!log.isEmpty() && !exporting);
        clear.setEnabled(!log.isEmpty());
        int index = terminal.getFirstVisiblePosition();
        View first = terminal.getChildAt(0);
        int offset = first == null ? 0 : first.getTop();
        adapter.rows = log.visible(eventsOnly.isChecked());
        adapter.notifyDataSetChanged();
        empty.setVisibility(adapter.rows.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(!log.isEmpty() ? "No sound events in the retained log. Turn off Events only to see all messages."
                : connected ? "Connected and subscribed. Quiet is normal; waiting for sound events…" : "Ready when you are. Connect your SoundMeter over Bluetooth.");
        if (autoScroll.isChecked() && !adapter.rows.isEmpty()) terminal.setSelection(adapter.rows.size() - 1);
        else terminal.setSelectionFromTop(index, offset);
    }

    private void playTone() {
        audioStatus.setText(tone.play() ? (beepEnabled.isChecked() ? "Event beeps ready · uses media volume" : "Test beep · event beeps muted")
                : "Audio unavailable. Tap Test beep to retry.");
    }

    private void exportLog() {
        exportSnapshot = log.export();
        exporting = true;
        save.setEnabled(false);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                        "sound-events-" + Instant.now().toString().replace(':', '-').replace('.', '-') + ".txt");
        try { startActivityForResult(intent, EXPORT); }
        catch (RuntimeException error) {
            exporting = false; exportSnapshot = null;
            notice.setText("No document picker available: " + error.getMessage());
            scheduleRender();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ENABLE_BLUETOOTH) {
            boolean requested = enablingBluetooth;
            enablingBluetooth = false;
            updateState();
            if (result == RESULT_OK && requested && foreground) connectBle();
            else notice.setText("Bluetooth is off. Turn it on and tap Connect SoundMeter.");
            return;
        }
        if (request != EXPORT) return;
        String snapshot = exportSnapshot;
        exportSnapshot = null;
        Uri target = data == null ? null : data.getData();
        if (result != RESULT_OK || target == null || snapshot == null) {
            exporting = false;
            scheduleRender();
            return;
        }
        files.execute(() -> {
            String message;
            try (OutputStream output = getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new java.io.IOException("Could not open the selected document");
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                message = "Log saved with UTC receipt timestamps (all retained messages, including filtered lines).";
            } catch (Exception error) { message = "Could not save log: " + error.getMessage(); }
            String status = message;
            main.post(() -> {
                if (destroyed) return;
                exporting = false;
                notice.setText(status);
                scheduleRender();
            });
        });
    }

    @Override protected void onStop() {
        super.onStop();
        foreground = false;
        // Runtime permission / Bluetooth enable dialogs and the document picker belong to this flow.
        if (!exporting && !waitingPermission && !enablingBluetooth && !isChangingConfigurations()) {
            disconnect("Monitoring paused because the app left the screen. Tap Connect SoundMeter to resume.");
            tone.mute();
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        terminal.getLayoutParams().height = terminalHeight();
        terminal.requestLayout();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterReceiver(receiver);
        connection.destroy();
        main.removeCallbacksAndMessages(null);
        files.shutdown(); tone.release();
        super.onDestroy();
    }

    private final class LogAdapter extends BaseAdapter {
        List<MonitorLog.Entry> rows = new ArrayList<>();
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert instanceof LinearLayout) row = (LinearLayout) convert;
            else {
                row = column(); row.setPadding(dp(10), dp(10), dp(10), dp(10));
                row.addView(text("", 11, MUTED));
                TextView message = text("", 12, TEXT);
                message.setTypeface(Typeface.MONOSPACE);
                message.setTextIsSelectable(true);
                row.addView(message);
            }
            MonitorLog.Entry entry = rows.get(position);
            ((TextView) row.getChildAt(0)).setText(clock.format(entry.time()));
            TextView message = (TextView) row.getChildAt(1);
            message.setText(entry.text());
            message.setTextColor(entry.isEvent() ? TEAL : entry.warning() ? ORANGE : TEXT);
            row.setBackgroundColor(entry.isEvent() ? 0xff142e2e : 0xff0c151c);
            return row;
        }
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int terminalHeight() { return dp(Math.max(220, Math.min(420, getResources().getConfiguration().screenHeightDp * 0.42f))); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout.LayoutParams weight(float n) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, n);
        p.setMargins(dp(2), 0, dp(2), 0); return p;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(2), 1); return t;
    }
    private TextView addText(LinearLayout parent, String value, int size, int color, int top) {
        TextView t = text(value, size, color); add(parent, t, top); return t;
    }
    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); parent.addView(view, p);
    }
    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(13);
        b.setMinHeight(dp(48)); b.setMinimumWidth(0); b.setMinWidth(0);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xff687d8a, primary ? BG : TEXT}));
        b.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xff20303b, primary ? TEAL : 0xff2b3e4b}));
        b.setOnClickListener(click); return b;
    }
    private CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(this); c.setText(label); c.setTextSize(12); c.setTextColor(TEXT);
        c.setMinHeight(dp(48)); c.setChecked(checked); c.setOnCheckedChangeListener((v, on) -> scheduleRender()); return c;
    }
    private TextView[] metric(LinearLayout parent, String label, String value, String caption, boolean accent) {
        LinearLayout card = column(); card.setPadding(dp(12), dp(16), dp(12), dp(16));
        card.setBackground(shape(accent ? 0xff183632 : PANEL, 10));
        card.setMinimumHeight(dp(155));
        card.addView(text(label, 10, accent ? TEAL : MUTED));
        TextView number = addText(card, value, 24, accent ? TEAL : TEXT, 12);
        number.setTypeface(null, Typeface.BOLD);
        TextView foot = addText(card, caption, 11, MUTED, 10);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(3), 0, dp(3), 0); parent.addView(card, p);
        return new TextView[]{number, foot};
    }
}
