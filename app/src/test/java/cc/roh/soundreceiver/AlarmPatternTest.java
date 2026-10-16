package cc.roh.soundreceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class AlarmPatternTest {
    private double rms(short[] pcm, double from, double to) {
        int start = (int)(from * AlarmPattern.SAMPLE_RATE), end = (int)(to * AlarmPattern.SAMPLE_RATE);
        double sum = 0;
        for (int i = start; i < end; i++) sum += (double)pcm[i] * pcm[i];
        return Math.sqrt(sum / (end - start));
    }
    @Test public void oneSecondHasTwoBeepsAndQuietGaps() {
        short[] pcm = AlarmPattern.samples();
        assertEquals(44100, pcm.length);
        assertTrue(rms(pcm, .02, .16) > 7000);
        assertTrue(rms(pcm, .32, .46) > 7000);
        assertEquals(0, rms(pcm, .18, .30), 0);
        assertEquals(0, rms(pcm, .48, 1), 0);
    }
    @Test public void noClippingAndQuietLoopBoundary() {
        short[] pcm = AlarmPattern.samples();
        for (short sample : pcm) assertTrue(Math.abs((int)sample) <= 11469);
        assertEquals(0, pcm[0]); assertEquals(0, pcm[pcm.length-1]);
        assertTrue(rms(pcm, 0, .005) < rms(pcm, .02, .025));
    }
    @Test public void beepFrequencyIs880Hz() {
        short[] pcm = AlarmPattern.samples();
        int positiveCrossings = 0;
        for (int i=1; i < (int)(.18 * AlarmPattern.SAMPLE_RATE); i++)
            if (pcm[i-1] <= 0 && pcm[i] > 0) positiveCrossings++;
        assertEquals(880, positiveCrossings / .18, 6);
    }
}
