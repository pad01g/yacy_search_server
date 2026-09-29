package net.yacy.peers.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class OwnAddressesTest {

    @Before
    public void clear() {
        OwnAddresses.clear();
    }

    @Test
    public void testOneRelayIsNotEnough() {
        final long now = 1_000_000_000L;
        // one relaying peer can make any number of peers see its address, but all of them were reached through it
        OwnAddresses.report("203.0.113.9", "T1", "198.51.100.7", now);
        OwnAddresses.report("203.0.113.9", "T2", "198.51.100.7", now);
        OwnAddresses.report("203.0.113.9", "T3", "198.51.100.8", now);
        assertFalse(OwnAddresses.confirmed(now).contains("203.0.113.9"));
        // a third peer in another network agrees
        OwnAddresses.report("203.0.113.9", "T4", "192.0.2.4", now);
        assertTrue(OwnAddresses.confirmed(now).contains("203.0.113.9"));
        // and the agreement expires
        assertFalse(OwnAddresses.confirmed(now + OwnAddresses.WINDOW + 1).contains("203.0.113.9"));
    }

    @Test
    public void testSamePeerCountsOnce() {
        final long now = 1_000_000_000L;
        OwnAddresses.report("203.0.113.9", "T1", "198.51.100.7", now);
        OwnAddresses.report("203.0.113.9", "T1", "192.0.2.4", now);
        OwnAddresses.report("203.0.113.9", "T2", "192.0.2.5", now);
        assertFalse(OwnAddresses.confirmed(now).contains("203.0.113.9"));
    }

    @Test
    public void testNetworks() {
        assertEquals("198.51.100.0/24", OwnAddresses.network("198.51.100.7"));
        assertEquals("10.1.2.3", OwnAddresses.network("10.1.2.3"));
        assertEquals("2001:0db8:0001::/48", OwnAddresses.network("[2001:db8:1:2::5]"));
        assertEquals("2001:db8::1", OwnAddresses.canonical("2001:0db8:0:0::1"));
    }
}
