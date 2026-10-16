package cc.roh.soundreceiver;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, in-memory baselines survive reconnects and Clear. IDs are Android peripheral addresses. */
public final class EventDeduplicator {
    public enum Kind { SENTINEL, HISTORICAL, NEW, DUPLICATE, OLDER }
    public record Decision(Kind kind, long missed) {
        public boolean accepted() { return kind == Kind.HISTORICAL || kind == Kind.NEW; }
    }
    private record Key(String device, long boot) {}
    private final Map<Key, Long> baselines = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Long> entry) { return size() > 64; }
    };
    public Decision accept(String device, ReceiverProtocol.Message message, boolean read) {
        Key key = new Key(device, message.bootId());
        Long previous = baselines.get(key);
        if (!message.event()) {
            if (previous != null && previous != 0) return new Decision(Kind.OLDER, 0);
            baselines.put(key, 0L);
            return new Decision(Kind.SENTINEL, 0);
        }
        if (previous != null && previous != 0) {
            long delta = (message.sequence() - previous + 4294967295L) % 4294967295L;
            if (delta == 0) return new Decision(Kind.DUPLICATE, 0);
            if (delta > 2147483647L) return new Decision(Kind.OLDER, 0);
            baselines.put(key, message.sequence());
            return new Decision(Kind.NEW, delta - 1);
        }
        baselines.put(key, message.sequence());
        return new Decision(read && previous == null ? Kind.HISTORICAL : Kind.NEW, 0);
    }
}
