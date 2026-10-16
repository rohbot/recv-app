package cc.roh.soundreceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
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
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements UsbConnection.Listener {
    private static final int BG = 0xff101a23, PANEL = 0xff172630, TEXT = 0xffedf4f5,
            MUTED = 0xffa0b4c1, TEAL = 0xff62dcc5, ORANGE = 0xffffbd8a;
    private static final int EXPORT = 40;
    private static final String USB_PERMISSION = "cc.roh.soundreceiver.USB_PERMISSION";
    private final MonitorLog log = new MonitorLog();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final EventTone tone = new EventTone();
    private final DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private UsbManager usb;
    private UsbConnection connection;
    private UsbSerialPort pendingPort;
    private PendingIntent permissionIntent;
    private AlertDialog picker;
    private int permissionRequest;
    private boolean waitingPermission, connected, closing, exporting, destroyed, renderPending;
    private String exportSnapshot;
    private TextView state, identity, notice, count, level, floor, rise, latest, duration, lineCount, lastMessage, empty, audioStatus;
    private Button connect, disconnect, save, clear;
    private CheckBox autoScroll, eventsOnly, beepEnabled;
    private ListView terminal;
    private LogAdapter adapter;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action) && waitingPermission && pendingPort != null) {
                // Check actual permission rather than trusting a broadcast's grant extra.
                UsbSerialPort port = pendingPort;
                waitingPermission = false;
                pendingPort = null;
                cancelPermissionIntent();
                if (usb.hasPermission(port.getDevice())) open(port);
                else {
                    notice.setText("USB permission denied. Tap Connect receiver to try again.");
                    updateState();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) return;
                if (connection.device() != null && connection.device().equals(device))
                    disconnect("Receiver removed. Reconnect the USB cable, then tap Connect receiver.");
                if (pendingPort != null && pendingPort.getDevice().equals(device)) {
                    cancelPermission();
                    notice.setText("Receiver removed before connecting. Reconnect and try again.");
                }
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        connection = new UsbConnection(usb, this);
        buildUi();
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        updateState();
        render();
    }

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
        addText(body, "ESP32-S3 · ESP-NOW · USB SERIAL", 11, MUTED, 20);
        TextView title = addText(body, "Listen to what’s\nhappening.", 32, TEXT, 8);
        title.setTypeface(null, Typeface.BOLD);
        addText(body, "Connect your USB receiver to view sound events and serial messages.", 15, MUTED, 8);
        LinearLayout controls = row();
        connect = button("Connect receiver", true, v -> choosePort());
        disconnect = button("Disconnect", false, v -> disconnect("Disconnected. Retained messages are kept until you clear or close the app."));
        controls.addView(connect, weight(2));
        controls.addView(disconnect, weight(1));
        add(body, controls, 18);

        state = addText(body, "Disconnected  ·  115200 baud · 8N1", 13, TEAL, 12);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        identity = addText(body, "No receiver connected", 12, MUTED, 6);
        identity.setTextIsSelectable(true);
        notice = addText(body, "Ready to connect. Use a USB data cable or OTG adapter and close other serial monitors.", 13, MUTED, 10);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout first = row();
        TextView[] events = metric(first, "EVENTS RECEIVED", "0", "Waiting for the first event", false);
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
        TextView stream = text(">_  Serial stream", 18, TEXT);
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
        empty = addText(panel, "Ready when you are. Connect your receiver by USB.", 14, MUTED, 12);
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
        addText(body, "Use an Android device with USB host / OTG support. Connect the receiver with a data cable, tap Connect receiver, select its serial port, then allow USB access. Power the T-Camera sender, wait five seconds for noise learning, then make a loud sound.", 13, MUTED, 8);
        addText(body, "Cards show event measurements, not continuous microphone telemetry. The receiver identifies itself every five seconds. Monitoring keeps the screen awake; leaving the app disconnects USB. Rotation keeps your session.", 13, MUTED, 8);
        addText(body, "LOCAL ONLY · Latest 1,000 lines kept in memory. No network access or automatic storage. Download log saves to a location you choose. Closing the app clears the session.", 12, MUTED, 12);
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

    private void choosePort() {
        if (connection.isBusy() || waitingPermission) return;
        List<UsbSerialPort> ports = new ArrayList<>();
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb);
            drivers.sort(Comparator.comparing(d -> d.getDevice().getDeviceName()));
            for (UsbSerialDriver driver : drivers) ports.addAll(driver.getPorts());
        } catch (RuntimeException error) {
            notice.setText("Could not inspect USB devices: " + error.getMessage());
            return;
        }
        if (ports.isEmpty()) {
            notice.setText(usb.getDeviceList().isEmpty()
                    ? "No USB device detected. Check the OTG adapter, data cable, and receiver power, then try again."
                    : "No supported serial port found. Use a CDC/ACM, CP210x, CH34x, FTDI, or supported Prolific receiver.");
            return;
        }
        String[] labels = new String[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            UsbSerialPort port = ports.get(i);
            UsbDevice d = port.getDevice();
            String name = d.getProductName();
            labels[i] = (name == null ? "USB serial device" : name) + String.format(Locale.ROOT,
                    "\n%04X:%04X · port %d · %s", d.getVendorId(), d.getProductId(), port.getPortNumber(), d.getDeviceName());
        }
        picker = new AlertDialog.Builder(this).setTitle("Choose receiver serial port")
                .setItems(labels, (dialog, which) -> requestPort(ports.get(which)))
                .setNegativeButton("Cancel", null).show();
    }

    private void requestPort(UsbSerialPort port) {
        if (usb.hasPermission(port.getDevice())) { open(port); return; }
        pendingPort = port;
        waitingPermission = true;
        permissionIntent = PendingIntent.getBroadcast(this, ++permissionRequest,
                new Intent(USB_PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        notice.setText("Allow USB access in the Android permission dialog.");
        updateState();
        try { usb.requestPermission(port.getDevice(), permissionIntent); }
        catch (RuntimeException error) {
            cancelPermission();
            notice.setText("Could not request USB access: " + error.getMessage());
        }
    }

    private void open(UsbSerialPort port) {
        closing = false;
        identity.setText("Waiting for receiver identity…");
        notice.setText("Opening USB serial port…");
        connection.open(port);
        updateState();
    }

    @Override public void onOpened() {
        if (closing) return;
        connected = true;
        log.resetSummary();
        notice.setText("Connected at 115200 baud. Waiting for receiver messages.");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
        scheduleRender();
    }

    @Override public void onLines(List<UsbConnection.WireLine> lines) {
        for (UsbConnection.WireLine line : lines) {
            MonitorLog.Entry entry = log.receive(line.text(), line.time());
            if (entry.parsed() != null && !entry.isEvent() && !closing)
                identity.setText(entry.parsed().mac() + " · CH " + entry.parsed().channel());
            if (entry.isEvent() && beepEnabled.isChecked() && !closing) playTone();
        }
        if (connected && !closing) notice.setText("Receiving serial messages. Cards update when a sound alert arrives.");
        scheduleRender();
    }

    @Override public void onClosed(String reason) {
        connected = closing = false;
        identity.setText("No receiver connected");
        notice.setText(reason);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateState();
        scheduleRender();
    }

    private void disconnect(String reason) {
        if (!connection.isBusy()) return;
        closing = true;
        connection.close(reason);
        updateState();
    }

    private void updateState() {
        String label = closing ? "Disconnecting…" : connected ? "Connected" : connection.isBusy()
                ? "Connecting…" : waitingPermission ? "Waiting for USB permission…" : "Disconnected";
        state.setText(label + "  ·  115200 baud · 8N1");
        connect.setEnabled(!connection.isBusy() && !waitingPermission);
        disconnect.setEnabled(connected && !closing);
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
        latest.setText(event == null ? "Waiting for the first event" : "#" + event.sequence() + " from " + event.mac());
        duration.setText(event == null ? "No event received yet" : event.duration() + " ms confirmation · " + clock.format(log.latestEventTime));
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
                : connected ? "Connected. Waiting for receiver data…" : "Ready when you are. Connect your receiver by USB.");
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

    private void cancelPermissionIntent() {
        if (permissionIntent != null) permissionIntent.cancel();
        permissionIntent = null;
    }
    private void cancelPermission() {
        waitingPermission = false; pendingPort = null;
        cancelPermissionIntent(); updateState();
    }

    @Override protected void onStop() {
        super.onStop();
        // The system document picker is part of the export flow; keep receiving during it.
        if (!exporting && !isChangingConfigurations()) {
            if (picker != null) picker.dismiss();
            cancelPermission();
            disconnect("Monitoring paused because the app left the screen. Tap Connect receiver to resume.");
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
        cancelPermissionIntent();
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
            message.setTextColor(entry.isEvent() ? TEAL : ReceiverProtocol.isWarning(entry.text()) ? ORANGE : TEXT);
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
