package cc.roh.soundreceiver;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Session state owned by the UI thread. Filtering never removes retained raw data. */
public final class MonitorLog {
    public static final int MAX_LINES = 1000;
    public record Entry(String text, Instant time, ReceiverProtocol.Message parsed) {
        public boolean isEvent() { return parsed != null && parsed.event(); }
    }
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    public long totalLines;
    public long eventCount;
    public ReceiverProtocol.Message latest;
    public Instant latestEventTime;
    public Entry last;

    public Entry receive(String text, Instant time) {
        Entry entry = new Entry(text, time, ReceiverProtocol.parse(text));
        entries.addLast(entry);
        if (entries.size() > MAX_LINES) entries.removeFirst();
        totalLines++;
        last = entry;
        if (entry.isEvent()) {
            eventCount++;
            latest = entry.parsed();
            latestEventTime = time;
        }
        return entry;
    }

    public List<Entry> visible(boolean eventsOnly) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (!eventsOnly || entry.isEvent()) result.add(entry);
        return result;
    }

    public void resetSummary() { eventCount = 0; latest = null; latestEventTime = null; }
    public void clear() { entries.clear(); totalLines = 0; last = null; resetSummary(); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public String export() {
        StringBuilder content = new StringBuilder();
        for (Entry entry : entries) content.append(entry.time()).append('\t').append(entry.text()).append('\n');
        return content.toString();
    }
}
