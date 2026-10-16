package cc.roh.soundreceiver;

import org.junit.Test;
import static org.junit.Assert.*;

public class GattHandshakeTest {
    @Test public void serializesSetupAndAcceptsNotificationBeforeReadCompletes() {
        GattHandshake setup = new GattHandshake();
        assertFalse(setup.reading()); assertFalse(setup.canReceive());
        assertTrue(setup.connected()); assertFalse(setup.connected()); assertTrue(setup.discovering());
        setup.servicesFound(); assertTrue(setup.subscribing()); assertFalse(setup.reading()); assertTrue(setup.canReceive());
        setup.subscribed(); assertTrue(setup.canReceive()); assertTrue(setup.reading());
        setup.snapshotRead(); assertTrue(setup.canReceive()); assertFalse(setup.reading());
    }
    @Test public void cannotReadUntilSubscribed() {
        GattHandshake setup = new GattHandshake();
        assertThrows(IllegalStateException.class, setup::snapshotRead);
        assertThrows(IllegalStateException.class, setup::subscribed);
    }
    @Test public void eachReconnectStartsWithFreshDiscovery() {
        GattHandshake old = new GattHandshake(); old.connected(); old.servicesFound(); old.subscribed(); old.snapshotRead();
        GattHandshake fresh = new GattHandshake();
        assertFalse(fresh.canReceive()); assertTrue(fresh.connected()); assertTrue(fresh.discovering());
    }
    @Test public void retryDelayIsCapped() {
        int[] expected = {1,2,4,8,10,10,10};
        for (int i=0;i<expected.length;i++) assertEquals(expected[i],GattHandshake.retryDelaySeconds(i));
        assertEquals(10,GattHandshake.retryDelaySeconds(100));
    }
}
