package net.yacy.peers.trust;

import java.security.KeyPair;

/** gives tests in other packages access to identities made from key pairs */
public final class PeerIdentityTestAccess {
    private PeerIdentityTestAccess() {
    }

    public static PeerIdentity identity(final KeyPair k) {
        return PeerIdentity.forKeys(k);
    }
}
