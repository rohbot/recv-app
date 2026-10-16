package cc.roh.soundreceiver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BleFixtures {
    static byte[] value(int type, long boot, long sequence, long uptime, int duration, int level, int floor) {
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put((byte) 1).put((byte) type)
                .putShort((short) duration).putInt((int) boot).putInt((int) sequence).putInt((int) uptime)
                .putShort((short) level).putShort((short) floor).array();
    }
    static byte[] event(long boot, long sequence) { return value(1, boot, sequence, 10000, 64, -4000, -6000); }
    static byte[] sentinel(long boot) { return value(0, boot, 0, 0, 0, -12000, -12000); }
}
