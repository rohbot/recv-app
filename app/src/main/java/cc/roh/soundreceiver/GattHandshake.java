package cc.roh.soundreceiver;

/** Enforces one GATT request at a time: discovery → CCCD write → initial read. */
final class GattHandshake {
    private enum Stage { CONNECTING, DISCOVERING, SUBSCRIBING, READING, READY }
    private Stage stage = Stage.CONNECTING;
    boolean connected() { if (stage != Stage.CONNECTING) return false; stage = Stage.DISCOVERING; return true; }
    boolean discovering() { return stage == Stage.DISCOVERING; }
    void servicesFound() { if (!discovering()) throw new IllegalStateException(); stage = Stage.SUBSCRIBING; }
    boolean subscribing() { return stage == Stage.SUBSCRIBING; }
    void subscribed() { if (!subscribing()) throw new IllegalStateException(); stage = Stage.READING; }
    boolean reading() { return stage == Stage.READING; }
    boolean canReceive() { return stage == Stage.SUBSCRIBING || stage == Stage.READING || stage == Stage.READY; }
    void snapshotRead() { if (!reading()) throw new IllegalStateException(); stage = Stage.READY; }
    static int retryDelaySeconds(int attempt) { return attempt >= 4 ? 10 : 1 << Math.max(0, attempt); }
}
