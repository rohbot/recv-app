package cc.roh.soundreceiver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/** SoundMeter BLE protocol v1. See BLE_PROTOCOL.md. No text framing or MTU negotiation. */
public final class ReceiverProtocol {
    public static final UUID SERVICE = UUID.fromString("9e7a0001-4f3b-4d61-9c8a-2b6f0e5d7100");
    public static final UUID EVENT = UUID.fromString("9e7a0002-4f3b-4d61-9c8a-2b6f0e5d7100");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public record Message(int type, int duration, long bootId, long sequence, long uptime,
                          double level, double floor) {
        public boolean event() { return type == 1; }
        public double rise() { return level - floor; }
    }

    public static Message decode(byte[] value) {
        if (value == null || value.length != 20) throw new IllegalArgumentException("Invalid event length: expected 20 bytes");
        ByteBuffer data = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int version = Byte.toUnsignedInt(data.get());
        int type = Byte.toUnsignedInt(data.get());
        if (version != 1 || (type != 0 && type != 1))
            throw new IllegalArgumentException("Unsupported protocol: version " + version + ", type " + type);
        int duration = Short.toUnsignedInt(data.getShort());
        long boot = Integer.toUnsignedLong(data.getInt());
        long sequence = Integer.toUnsignedLong(data.getInt());
        long uptime = Integer.toUnsignedLong(data.getInt());
        int level = data.getShort();
        int floor = data.getShort();
        if ((type == 0) != (sequence == 0) || level < -12000 || level > 0 || floor < -12000 || floor > 0)
            throw new IllegalArgumentException("Invalid event fields");
        return new Message(type, duration, boot, sequence, uptime, level / 100.0, floor / 100.0);
    }

    public static String hex(byte[] value) {
        if (value == null) return "null";
        StringBuilder result = new StringBuilder();
        for (byte b : value) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private ReceiverProtocol() {}
}
