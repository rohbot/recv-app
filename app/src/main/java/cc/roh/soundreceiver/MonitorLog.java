package cc.roh.soundreceiver;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** UI-thread state. Receipt time is independent of wrapping board uptime. */
public final class MonitorLog {
    public static final int MAX_LINES = 1000;
    public record Entry(String text, Instant time, ReceiverProtocol.Message parsed,
                        String device, boolean historical, boolean warning) {
        public boolean isEvent() { return parsed != null && parsed.event(); }
    }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final EventDeduplicator deduplicator = new EventDeduplicator();
    public long totalLines, eventCount, missedCount;
    public ReceiverProtocol.Message latest;
    public Instant latestEventTime;
    public String latestDevice;
    public boolean latestHistorical;
    public Entry last;

    public Entry receive(String device, byte[] value, boolean read, Instant time) {
        ReceiverProtocol.Message message;
        try { message = ReceiverProtocol.decode(value); }
        catch (IllegalArgumentException error) {
            return status(error.getMessage() + " · device=" + device + " raw=" + ReceiverProtocol.hex(value), time, true);
        }
        EventDeduplicator.Decision decision = deduplicator.accept(device, message, read);
        if (decision.kind() == EventDeduplicator.Kind.DUPLICATE || decision.kind() == EventDeduplicator.Kind.OLDER) return null;
        if (decision.kind() == EventDeduplicator.Kind.SENTINEL)
            return status("No event yet · device=" + device + " boot=" + message.bootId(), time, false);
        boolean historical = decision.kind() == EventDeduplicator.Kind.HISTORICAL;
        if (!historical) eventCount++;
        missedCount += decision.missed();
        latest = message; latestEventTime = time; latestDevice = device; latestHistorical = historical;
        String text = String.format(Locale.ROOT,
                "%s device=%s boot=%d #%d level=%.2f dBFS floor=%.2f dBFS rise=%.2f dB duration=%d%s ms uptime=%d ms source=%s missed=%d raw=%s",
                historical ? "LATEST SNAPSHOT (historical)" : "SOUND EVENT", device, message.bootId(), message.sequence(),
                message.level(), message.floor(), message.rise(), message.duration(), message.duration() == 65535 ? "+" : "",
                message.uptime(), read ? "read" : "notification", decision.missed(), ReceiverProtocol.hex(value));
        return append(new Entry(text, time, message, device, historical, false));
    }
    public Entry status(String text, Instant time, boolean warning) {
        return append(new Entry(text, time, null, "", false, warning));
    }
    private Entry append(Entry entry) {
        entries.addLast(entry);
        if (entries.size() > MAX_LINES) entries.removeFirst();
        totalLines++; last = entry;
        return entry;
    }
    public List<Entry> visible(boolean eventsOnly) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (!eventsOnly || entry.isEvent()) result.add(entry);
        return result;
    }
    public void clear() {
        entries.clear(); totalLines = eventCount = missedCount = 0;
        last = null; latest = null; latestEventTime = null; latestDevice = null; latestHistorical = false;
        // Keep deduplication baselines: clearing the display must not replay an alert.
    }
    public boolean isEmpty() { return entries.isEmpty(); }
    public String export() {
        StringBuilder content = new StringBuilder();
        for (Entry entry : entries) content.append(entry.time()).append('\t').append(entry.text()).append('\n');
        return content.toString();
    }
}
