package net.yacy.peers.trust;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Test;

import net.yacy.peers.Seed;

public class TrustServiceTest {

    private static Seed seed(final String type) {
        final ConcurrentHashMap<String, String> dna = new ConcurrentHashMap<>();
        dna.put(Seed.NAME, "me");
        dna.put(Seed.PORT, "8090");
        dna.put(Seed.PEERTYPE, type);
        return new Seed("AAAAAAAAAAAA", dna);
    }

    @Test
    public void testRelayNeedsThreeJuniorReportsAndStaysUntilPublic() {
        final TrustService.SidecarStatus unknown = new TrustService.SidecarStatus("id", "unknown", Collections.singletonList("/ip4/1.2.3.4/tcp/4001/p2p/R/p2p-circuit/p2p/id"));
        final Seed junior = seed(Seed.PEERTYPE_JUNIOR);
        final Seed senior = seed(Seed.PEERTYPE_SENIOR);
        // reset
        TrustService.useRelay(new TrustService.SidecarStatus("id", "public", Collections.<String>emptyList()), senior);
        assertFalse(TrustService.useRelay(unknown, junior));
        assertFalse(TrustService.useRelay(unknown, junior));
        assertTrue(TrustService.useRelay(unknown, junior));
        // once relayed, the peer becomes senior through the relay: keep the relay
        assertTrue(TrustService.useRelay(unknown, senior));
        // AutoNAT says public: back to direct
        assertFalse(TrustService.useRelay(new TrustService.SidecarStatus("id", "public", unknown.relayAddrs), senior));
        // private is enough at once
        assertTrue(TrustService.useRelay(new TrustService.SidecarStatus("id", "private", unknown.relayAddrs), senior));
        // no relay address, no relay
        assertFalse(TrustService.useRelay(new TrustService.SidecarStatus("id", "private", Collections.<String>emptyList()), junior));
    }

    @Test
    public void testSingleJuniorReportIsNotEnough() {
        final TrustService.SidecarStatus unknown = new TrustService.SidecarStatus("id", "unknown", Collections.singletonList("/ip4/1.2.3.4/tcp/4001/p2p/R/p2p-circuit/p2p/id"));
        TrustService.useRelay(new TrustService.SidecarStatus("id", "public", Collections.<String>emptyList()), seed(Seed.PEERTYPE_SENIOR));
        assertFalse(TrustService.useRelay(unknown, seed(Seed.PEERTYPE_JUNIOR)));
        assertFalse(TrustService.useRelay(unknown, seed(Seed.PEERTYPE_SENIOR)));
        assertFalse(TrustService.useRelay(unknown, seed(Seed.PEERTYPE_JUNIOR)));
    }
}
