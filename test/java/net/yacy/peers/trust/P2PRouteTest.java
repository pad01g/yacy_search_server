package net.yacy.peers.trust;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class P2PRouteTest {

    @Test
    public void testRequestMACMatchesTheSidecar() {
        // the same vector is in sidecar/main_test.go
        P2PRoute.setToken("0123456789abcdef0123");
        try {
            assertEquals("pnT__vOqTRzq_o8vvskl9iOlV_ARlbHXKz4FaLD9br4", P2PRoute.requestMAC("GET", "/tunnel/12D3KooWPeer", "1700000000000.abc"));
        } finally {
            P2PRoute.setToken(null);
        }
    }

    @Test
    public void testNonceCarriesTheTime() {
        final String n = P2PRoute.newNonce();
        final long t = Long.parseLong(n.substring(0, n.indexOf('.')));
        assertTrue(Math.abs(System.currentTimeMillis() - t) < 10000);
        assertTrue(n.length() >= 20 && n.length() <= 80);
    }
}
