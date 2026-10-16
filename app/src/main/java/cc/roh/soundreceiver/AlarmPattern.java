package cc.roh.soundreceiver;

/** One second: two 180 ms beeps at 880 Hz, with soft edges and a quiet gap. */
final class AlarmPattern {
    static final int SAMPLE_RATE = 44100;
    static short[] samples() {
        short[] pcm = new short[SAMPLE_RATE];
        for (int i = 0; i < pcm.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            double local = t < 0.18 ? t : t >= 0.30 && t < 0.48 ? t - 0.30 : -1;
            if (local < 0) continue;
            double envelope = Math.min(1, Math.min(local / 0.01, (0.18 - local) / 0.01));
            pcm[i] = (short) (Math.sin(2 * Math.PI * 880 * t) * 32767 * 0.35 * Math.max(0, envelope));
        }
        return pcm;
    }
    private AlarmPattern() {}
}
