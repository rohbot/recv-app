package cc.roh.soundreceiver;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;

/** Native looping buffer keeps the cadence running without timers or a permanent CPU wake lock. */
final class LoopingAlarm {
    private final AudioManager manager;
    private final Runnable changed;
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private AudioTrack track;
    private AudioFocusRequest focus;
    private boolean active;
    private int generation;
    private String status = "Alarm ready · uses the phone’s Alarm volume";

    LoopingAlarm(Context context, Runnable changed) {
        manager = context.getSystemService(AudioManager.class); this.changed = changed;
    }
    boolean isPlaying() { return track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING; }
    String status() { return status; }
    void start() {
        if (active) return; // Another detection must not restart or stack the alarm.
        active = true;
        final int token = ++generation;
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(change -> {
                        if (!active || token != generation) return;
                        if (change == AudioManager.AUDIOFOCUS_GAIN) play();
                        else {
                            if (track != null) track.pause();
                            status = "Alarm active · audio paused by another app or call";
                            changed.run();
                        }
                    }, new Handler(Looper.getMainLooper())).build();
            int result = manager.requestAudioFocus(focus);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) play();
            else {
                status = result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED ? "Alarm active · waiting for audio focus"
                        : "Alarm active · audio unavailable; silence and test again";
                changed.run();
            }
        } catch (RuntimeException error) {
            status = "Alarm active · audio unavailable: " + error.getMessage(); changed.run();
        }
    }
    private void play() {
        try {
            if (track == null) {
                short[] pcm = AlarmPattern.samples();
                track = new AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(AlarmPattern.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                if (track.write(pcm, 0, pcm.length) != pcm.length || track.setLoopPoints(0, pcm.length, -1) != AudioTrack.SUCCESS)
                    throw new IllegalStateException("Could not prepare alarm audio");
            }
            track.play(); status = "Alarm ringing · press Silence alarm to stop · Alarm volume";
        } catch (RuntimeException error) { status = "Alarm active · could not play audio: " + error.getMessage(); }
        changed.run();
    }
    void stop() {
        active = false;
        generation++;
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release(); track = null;
        }
        if (focus != null) { manager.abandonAudioFocusRequest(focus); focus = null; }
        status = "Alarm silenced";
        changed.run();
    }
    void release() { stop(); }
}
