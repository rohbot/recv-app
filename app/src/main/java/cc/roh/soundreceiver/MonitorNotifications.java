package cc.roh.soundreceiver;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.Locale;

/** Android channels own vibration and respect the user's notification/DND settings. */
final class MonitorNotifications {
    static final String MONITOR_CHANNEL = "ble_monitoring";
    static final String EVENT_CHANNEL = "sound_events";
    static final int MONITOR_ID = 1, EVENT_ID = 2;
    private final Context context;
    private final NotificationManager manager;

    MonitorNotifications(Context context) {
        this.context = context;
        manager = context.getSystemService(NotificationManager.class);
        NotificationChannel monitoring = new NotificationChannel(MONITOR_CHANNEL, "Background monitoring", NotificationManager.IMPORTANCE_LOW);
        monitoring.setDescription("Connection status while SoundMeter monitoring is running");
        monitoring.setSound(null, null);
        monitoring.enableVibration(false);
        NotificationChannel events = new NotificationChannel(EVENT_CHANNEL, "Sound event alerts", NotificationManager.IMPORTANCE_HIGH);
        events.setDescription("Notifications and vibration for new sound detections");
        events.setSound(null, null); // The service plays the repeating alarm using Alarm volume.
        events.enableVibration(true);
        events.setVibrationPattern(new long[]{0, 180, 80, 180});
        manager.createNotificationChannels(java.util.List.of(monitoring, events));
    }
    private PendingIntent openApp() {
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    Notification ongoing(String status, boolean monitoring, boolean alarmActive) {
        PendingIntent stop = PendingIntent.getService(context, 1,
                new Intent(context, MonitoringService.class).setAction(MonitoringService.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MONITOR_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(alarmActive ? "SoundMeter alarm active — tap to silence" : "SoundMeter monitoring")
                .setContentText(status).setStyle(new NotificationCompat.BigTextStyle().bigText(status))
                .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (monitoring) builder.addAction(0, "Disconnect", stop);
        return builder.build();
    }
    boolean canAlert() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = manager.getNotificationChannel(EVENT_CHANNEL);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    String alertStatus() {
        if (!canAlert()) return "Event notifications blocked · enable them in Notification settings.";
        return manager.getNotificationChannel(EVENT_CHANNEL).shouldVibrate()
                ? "Event notifications and vibration enabled · system sound/DND settings apply."
                : "Event notifications enabled · vibration is off in Notification settings.";
    }
    boolean showEvent(MonitorLog.Entry entry) {
        if (!entry.shouldAlert() || !canAlert()) return false;
        ReceiverProtocol.Message event = entry.parsed();
        String summary = String.format(Locale.getDefault(), "%.1f dBFS · %+.1f dB above background", event.level(), event.rise());
        String details = summary + "\n" + event.duration() + (event.duration() == 65535 ? "+" : "")
                + " ms confirmation · event #" + event.sequence() + "\nSoundMeter " + entry.device();
        Notification notification = new NotificationCompat.Builder(context, EVENT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Sound detected")
                .setContentText(summary).setStyle(new NotificationCompat.BigTextStyle().bigText(details))
                .setWhen(entry.time().toEpochMilli()).setShowWhen(true).setContentIntent(openApp())
                .setAutoCancel(true).setOnlyAlertOnce(false).setNumber(1)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build();
        try { manager.notify(EVENT_ID, notification); return true; }
        catch (SecurityException ignored) { return false; }
    }
    void updateOngoing(String status, boolean monitoring, boolean alarmActive) {
        try { manager.notify(MONITOR_ID, ongoing(status, monitoring, alarmActive)); } catch (SecurityException ignored) {}
    }
}
