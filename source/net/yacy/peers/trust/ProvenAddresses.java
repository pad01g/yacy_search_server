// ProvenAddresses.java
// -------------------------------------
// part of YaCy
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package net.yacy.peers.trust;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.net.InetAddresses;

import net.yacy.peers.Seed;

/**
 * Addresses at which this peer saw the owner of a seed key answer its own challenge (hello, or the back-ping of a
 * hello). The IP address of a seed is not signed, so a seed relayed by a third party may point to anybody; only for
 * a proven address do we believe that answers come from the owner of the key. Peers reached through the sidecar are
 * authenticated by the libp2p handshake instead.
 */
public final class ProvenAddresses {

    private static final int MAX_ENTRIES = 20000;
    private static final Map<String, String> proven = new ConcurrentHashMap<>();

    private ProvenAddresses() {
    }

    /** remember that the owner of the seed with this hash answered at this IP */
    public static void prove(final String hash, final String ip) {
        if (hash == null || ip == null) return;
        if (proven.size() >= MAX_ENTRIES) proven.clear();
        proven.put(hash, normalize(ip));
    }

    /** @return true if answers of this peer, as we will contact it now, come from the owner of its key */
    public static boolean isProven(final Seed seed) {
        if (seed == null) return false;
        if (PeerIdentity.isMine(seed.hash)) return true;
        if (seed.isRelayed()) return seed.isSigned() && P2PRoute.baseURL(seed) != null; // libp2p authenticates the key
        final String ip = proven.get(seed.hash);
        if (ip == null) return false;
        for (final String s : seed.getIPs()) if (ip.equals(normalize(s))) return true;
        return false;
    }

    static String normalize(final String ip) {
        final String s = ip.replace("[", "").replace("]", "");
        return InetAddresses.isInetAddress(s) ? InetAddresses.toAddrString(InetAddresses.forString(s)) : s;
    }

    /** for tests */
    static void clear() {
        proven.clear();
    }
}
