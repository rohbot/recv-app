package cc.roh.soundreceiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Same 880 Hz, 210 ms alert as the web monitor; overlapping events share a tone. */
final class EventTone {
    private AudioTrack track;
    private long busyUntil;

    boolean play() {
        if (SystemClock.elapsedRealtime() < busyUntil) return true;
        try {
            if (track == null) {
                int rate = 44100;
                short[] pcm = new short[(int) (rate * 0.21)];
                for (int i = 0; i < pcm.length; i++) {
                    double t = (double) i / rate;
                    double envelope = t < 0.01 ? t / 0.01 : t < 0.14 ? 1 : Math.max(0, (0.2 - t) / 0.06);
                    pcm[i] = (short) (Math.sin(2 * Math.PI * 880 * t) * 32767 * 0.12 * envelope);
                }
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                if (track.write(pcm, 0, pcm.length) != pcm.length) throw new IllegalStateException("Audio buffer unavailable");
            }
            track.stop();
            track.reloadStaticData();
            track.play();
            busyUntil = SystemClock.elapsedRealtime() + 210;
            return true;
        } catch (RuntimeException error) {
            release();
            return false;
        }
    }

    void mute() {
        if (track != null) { try { track.stop(); } catch (IllegalStateException ignored) {} }
        busyUntil = 0;
    }
    void release() { mute(); if (track != null) track.release(); track = null; }
}
