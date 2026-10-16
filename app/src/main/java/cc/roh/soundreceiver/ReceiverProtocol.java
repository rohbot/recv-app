package cc.roh.soundreceiver;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The wire format emitted by LilyGo-Cam-ESP32S3/examples/SoundEventReceiver. */
public final class ReceiverProtocol {
    private static final String MAC = "([\\da-f]{2}(?::[\\da-f]{2}){5})";
    private static final String NUMBER = "(-?\\d+(?:\\.\\d+)?)";
    private static final Pattern STATUS = Pattern.compile("^ESP-NOW receiver " + MAC + ", channel (\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT = Pattern.compile("^SOUND EVENT from " + MAC + " #(\\d+) level=" + NUMBER
            + " dBFS floor=" + NUMBER + " dBFS rise=" + NUMBER
            + " dB duration=(\\d+) ms uptime=(\\d+) ms$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARNING = Pattern.compile("failed|error|invalid|panic|^E \\(", Pattern.CASE_INSENSITIVE);

    public record Message(boolean event, String mac, long channel, long sequence,
                          double level, double floor, double rise, long duration, long uptime) {}

    public static Message parse(String line) {
        try {
            Matcher status = STATUS.matcher(line);
            if (status.matches()) return new Message(false, status.group(1).toUpperCase(Locale.ROOT),
                    Long.parseLong(status.group(2)), 0, 0, 0, 0, 0, 0);
            Matcher event = EVENT.matcher(line);
            if (!event.matches()) return null;
            double level = Double.parseDouble(event.group(3));
            double floor = Double.parseDouble(event.group(4));
            double rise = Double.parseDouble(event.group(5));
            if (!Double.isFinite(level) || !Double.isFinite(floor) || !Double.isFinite(rise)) return null;
            return new Message(true, event.group(1).toUpperCase(Locale.ROOT), 0,
                    Long.parseLong(event.group(2)), level, floor, rise,
                    Long.parseLong(event.group(6)), Long.parseLong(event.group(7)));
        } catch (NumberFormatException ignored) {
            // Noisy/overflowing serial input remains a plain log message.
            return null;
        }
    }

    public static boolean isWarning(String line) { return WARNING.matcher(line).find(); }
    private ReceiverProtocol() {}
}
