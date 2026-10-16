package cc.roh.soundreceiver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One worker owns opening, reading and closing. UI callbacks are ordered on the main thread. */
final class UsbConnection {
    interface Listener {
        void onOpened();
        void onLines(List<WireLine> lines);
        void onClosed(String reason);
    }
    record WireLine(String text, Instant time) {}
    private static final class Session {
        final UsbSerialPort port;
        volatile boolean cancelled;
        volatile String reason = "Disconnected. Retained messages are kept until you clear or close the app.";
        Session(UsbSerialPort port) { this.port = port; }
    }
    private final UsbManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Session active;
    private boolean disposed;

    UsbConnection(UsbManager manager, Listener listener) { this.manager = manager; this.listener = listener; }
    boolean isBusy() { return active != null; }
    UsbDevice device() { return active == null ? null : active.port.getDevice(); }

    void open(UsbSerialPort port) {
        if (active != null || disposed) return;
        Session session = new Session(port);
        active = session;
        worker.execute(() -> read(session));
    }

    private void read(Session session) {
        UsbDeviceConnection usb = null;
        List<WireLine> batch = new ArrayList<>();
        SerialLines framing = new SerialLines(line -> batch.add(new WireLine(line, Instant.now())));
        String failure = null;
        try {
            if (session.cancelled) return;
            usb = manager.openDevice(session.port.getDevice());
            if (usb == null) throw new IOException("USB permission was lost or the device was removed");
            session.port.open(usb);
            session.port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            // Native ESP32 USB CDC needs DTR. Leave RTS low to avoid resetting UART boards.
            try { session.port.setDTR(true); } catch (UnsupportedOperationException ignored) {}
            main.post(() -> { if (active == session && !disposed && !session.cancelled) listener.onOpened(); });
            byte[] buffer = new byte[4096];
            while (!session.cancelled) {
                int count = session.port.read(buffer, 200);
                if (count > 0) {
                    framing.push(buffer, count);
                    deliver(session, batch);
                }
            }
        } catch (Exception error) {
            if (!session.cancelled) failure = "Serial connection stopped: " + error.getMessage() + ". Check USB and reconnect.";
        } finally {
            framing.finish();
            deliver(session, batch);
            try { session.port.close(); } catch (IOException ignored) {}
            if (usb != null) usb.close();
            String result = failure == null ? session.reason : failure;
            main.post(() -> {
                if (active != session) return;
                active = null;
                if (!disposed) listener.onClosed(result);
            });
        }
    }

    private void deliver(Session session, List<WireLine> batch) {
        if (batch.isEmpty()) return;
        List<WireLine> copy = new ArrayList<>(batch);
        batch.clear();
        main.post(() -> { if (active == session && !disposed) listener.onLines(copy); });
    }

    void close(String reason) {
        if (active == null) return;
        active.reason = reason;
        active.cancelled = true;
    }

    void destroy() {
        disposed = true;
        close("App closed.");
        worker.shutdown();
    }
}
