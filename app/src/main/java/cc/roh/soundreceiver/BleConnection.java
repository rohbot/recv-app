package cc.roh.soundreceiver;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All mutable state and listener calls belong to the main looper. Stale GATT callbacks are ignored. */
@SuppressLint("MissingPermission") // MainActivity obtains runtime permissions before scan/open; revocation is caught.
final class BleConnection {
    record Peripheral(String id, String name, int rssi, BluetoothDevice device) {
        String label() { return name + "\n" + id + " · " + rssi + " dBm"; }
    }
    interface Listener {
        void onState(String state);
        void onDevices(List<Peripheral> devices);
        void onOpened(String device);
        void onValue(String device, byte[] bytes, boolean read);
        void onClosed(String reason);
    }
    private final Context context;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Peripheral> devices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt active;
    private BluetoothDevice target;
    private boolean selecting, wanted, destroyed;
    private int retries;
    private int generation;
    private Runnable timeout, retry, scanTimeout;
    private GattHandshake handshake;

    BleConnection(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    boolean supported() { return adapter != null && context.getPackageManager().hasSystemFeature("android.hardware.bluetooth_le"); }
    boolean enabled() { return adapter != null && adapter.isEnabled(); }
    boolean isBusy() { return selecting || wanted; }

    void scan() {
        if (isBusy() || destroyed) return;
        selecting = true;
        devices.clear();
        listener.onState("Scanning for SoundMeter…");
        final int token = ++generation;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) throw new IllegalStateException("Bluetooth is off");
            scanCallback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) { main.post(() -> found(token, result)); }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    main.post(() -> { for (ScanResult result : results) found(token, result); });
                }
                @Override public void onScanFailed(int code) {
                    main.post(() -> { if (selecting && token == generation) close("BLE scan failed (" + code + "). Wait a moment and try again."); });
                }
            };
            scanner.startScan(List.of(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(ReceiverProtocol.SERVICE)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            scanTimeout = () -> {
                if (token != generation || !selecting) return;
                stopScan();
                if (devices.isEmpty()) close("No SoundMeter found. Check board power, BLE firmware, range, and that no other client is connected.");
                else listener.onState("Scan finished · select a SoundMeter");
            };
            main.postDelayed(scanTimeout, 12000);
        } catch (RuntimeException error) { close("Cannot scan: " + error.getMessage()); }
    }

    private void found(int token, ScanResult result) {
        if (!selecting || token != generation || scanCallback == null) return;
        try {
        BluetoothDevice device = result.getDevice();
        String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
        if (name == null) name = "SoundMeter";
        String id = device.getAddress();
        if (devices.size() >= 100 && !devices.containsKey(id)) return;
        devices.put(id, new Peripheral(id, name, result.getRssi(), device));
        listener.onDevices(new ArrayList<>(devices.values()));
        } catch (SecurityException error) {
            close("Bluetooth permission lost. Tap Connect SoundMeter to allow access again.");
        }
    }

    void open(Peripheral peripheral) {
        if (destroyed) return;
        stopScan(); selecting = false;
        target = peripheral.device(); wanted = true; retries = 0;
        attempt();
    }

    private void attempt() {
        if (!wanted || destroyed) return;
        try {
            if (!enabled()) { close("Bluetooth is off. Turn it on and tap Connect SoundMeter."); return; }
            listener.onState("Connecting to SoundMeter…");
            final int token = ++generation;
            handshake = new GattHandshake();
            active = target.connectGatt(context, false, callbacks(token), BluetoothDevice.TRANSPORT_LE);
            if (active == null) throw new IllegalStateException("Could not create GATT connection");
            timeout = () -> { if (token == generation) failed("BLE connection/setup timed out"); };
            main.postDelayed(timeout, 20000);
        } catch (SecurityException error) { close("Bluetooth permission lost. Tap Connect SoundMeter to allow access again."); }
        catch (RuntimeException error) { failed("BLE connection failed: " + error.getMessage()); }
    }

    private BluetoothGattCallback callbacks(int token) {
        return new BluetoothGattCallback() {
            private void dispatch(BluetoothGatt gatt, Runnable task) {
                main.post(() -> {
                    if (destroyed || token != generation || active != gatt || !wanted) return;
                    try { task.run(); }
                    catch (SecurityException error) { close("Bluetooth permission lost. Tap Connect SoundMeter to allow access again."); }
                    catch (RuntimeException error) { failed("BLE operation failed: " + error.getMessage()); }
                });
            }
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                dispatch(gatt, () -> {
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        failed("BLE disconnected (status " + status + ")"); return;
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && handshake.connected()) {
                        listener.onState("Discovering SoundMeter service…");
                        if (!gatt.discoverServices()) failed("Could not start service discovery");
                    }
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                dispatch(gatt, () -> {
                    if (!handshake.discovering()) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { failed("Service discovery failed (" + status + ")"); return; }
                    BluetoothGattService service = gatt.getService(ReceiverProtocol.SERVICE);
                    BluetoothGattCharacteristic event = service == null ? null : service.getCharacteristic(ReceiverProtocol.EVENT);
                    int required = BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY;
                    BluetoothGattDescriptor cccd = event == null ? null : event.getDescriptor(ReceiverProtocol.CCCD);
                    if (event == null || (event.getProperties() & required) != required || cccd == null) {
                        close("Unsupported protocol: SoundMeter read/notify characteristic or CCCD is missing."); return;
                    }
                    handshake.servicesFound();
                    listener.onState("Subscribing to sound events…");
                    if (!gatt.setCharacteristicNotification(event, true)) { failed("Could not register notifications"); return; }
                    boolean queued;
                    if (Build.VERSION.SDK_INT >= 33)
                        queued = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                    else {
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        queued = gatt.writeDescriptor(cccd);
                    }
                    if (!queued) failed("Could not enable event notifications");
                });
            }
            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                dispatch(gatt, () -> {
                    if (!ReceiverProtocol.CCCD.equals(descriptor.getUuid()) || !handshake.subscribing()) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { failed("Notification subscription failed (" + status + ")"); return; }
                    handshake.subscribed();
                    listener.onOpened(target.getAddress());
                    BluetoothGattCharacteristic event = gatt.getService(ReceiverProtocol.SERVICE).getCharacteristic(ReceiverProtocol.EVENT);
                    if (!gatt.readCharacteristic(event)) failed("Could not read latest event snapshot");
                });
            }
            private void read(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] bytes, int status) {
                byte[] copy = bytes == null ? new byte[0] : bytes.clone();
                dispatch(gatt, () -> {
                    if (!ReceiverProtocol.EVENT.equals(characteristic.getUuid()) || !handshake.reading()) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { failed("Latest snapshot read failed (" + status + ")"); return; }
                    handshake.snapshotRead(); retries = 0;
                    cancelTimeout();
                    listener.onValue(target.getAddress(), copy, true);
                });
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value, int status) { read(gatt, c, value, status); }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic c, int status) {
                if (Build.VERSION.SDK_INT < 33) read(gatt, c, c.getValue(), status);
            }
            private void changed(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] bytes) {
                byte[] copy = bytes == null ? new byte[0] : bytes.clone();
                dispatch(gatt, () -> {
                    if (ReceiverProtocol.EVENT.equals(characteristic.getUuid()) && handshake.canReceive())
                        listener.onValue(target.getAddress(), copy, false);
                });
            }
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c, byte[] value) { changed(gatt, c, value); }
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT < 33) changed(gatt, c, c.getValue());
            }
        };
    }

    private void failed(String reason) {
        disposeGatt();
        if (!wanted || destroyed) return;
        int delay = GattHandshake.retryDelaySeconds(retries++);
        listener.onState(reason + " · reconnecting in " + delay + " s");
        retry = this::attempt;
        main.postDelayed(retry, delay * 1000L);
    }
    private void cancelTimeout() { if (timeout != null) main.removeCallbacks(timeout); timeout = null; }
    private void disposeGatt() {
        ++generation;
        cancelTimeout();
        if (retry != null) main.removeCallbacks(retry);
        retry = null;
        BluetoothGatt old = active; active = null;
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) {}
            try { old.close(); } catch (RuntimeException ignored) {}
        }
    }
    private void stopScan() {
        if (scanTimeout != null) main.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        scanner = null; scanCallback = null;
    }
    void close(String reason) {
        wanted = selecting = false;
        stopScan(); disposeGatt(); target = null;
        if (!destroyed) listener.onClosed(reason);
    }
    void destroy() { destroyed = true; close("App closed."); main.removeCallbacksAndMessages(null); }
}
