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

import java.net.MalformedURLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.common.net.InetAddresses;

import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.protocol.Domains;
import net.yacy.peers.Seed;

/**
 * Addresses at which this peer saw the owner of a seed key answer its own challenge (hello, or the back-ping of a
 * hello). The IP address of a seed is not signed, so a seed relayed by a third party may point to anybody; only for
 * a proven address do we believe that answers come from the owner of the key. Peers reached through the sidecar are
 * authenticated by the libp2p handshake instead.
 */
public final class ProvenAddresses {

    private static final int MAX_ENTRIES = 20000;
    /** the least recently used entries go first (clearing all at the limit would let new keys wipe every proof) */
    /** per key the proven IPv4 and IPv6 address (a dual-stack peer is searched at the first address of its seed) */
    private static final Map<String, String[]> proven = Collections.synchronizedMap(new LinkedHashMap<String, String[]>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, String[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    private ProvenAddresses() {
    }

    /** remember that the owner of the seed with this hash answered at this IP */
    public static void prove(final String hash, final String ip) {
        if (hash == null || ip == null) return;
        final String n = normalize(ip);
        final int family = n.indexOf(':') >= 0 ? 1 : 0;
        synchronized (proven) {
            final String[] old = proven.get(hash);
            final String[] now = old == null ? new String[2] : old.clone();
            now[family] = n;
            proven.put(hash, now);
        }
    }

    /**
     * @param contactedBaseURL the URL the request went to (a seed may list several addresses, and only the address that
     *        answered matters: an address listed next to a proven one may belong to somebody else)
     * @return true if the answer of this peer at this URL came from the owner of its key
     */
    public static boolean isProvenAt(final Seed seed, final String contactedBaseURL) {
        if (seed == null || contactedBaseURL == null) return false;
        if (PeerIdentity.isMine(seed.hash)) return true;
        final MultiProtocolURL url;
        try {
            url = new MultiProtocolURL(contactedBaseURL);
        } catch (final MalformedURLException e) {
            return false;
        }
        if (P2PRoute.isRouted(url)) {
            // libp2p authenticates the key, if the tunnel is the one of this peer
            final String route = seed.isRelayed() && seed.isSigned() ? P2PRoute.baseURL(seed) : null;
            return route != null && sameEndpoint(route, url);
        }
        final String[] ips = proven.get(seed.hash);
        if (ips == null) return false;
        final String host = Domains.stripToHostName(url.getHost());
        if (host == null) return false;
        final String h = normalize(host);
        return h.equals(ips[0]) || h.equals(ips[1]);
    }

    private static boolean sameEndpoint(final String baseURL, final MultiProtocolURL url) {
        try {
            final MultiProtocolURL b = new MultiProtocolURL(baseURL);
            return b.getPort() == url.getPort() && b.getHost().equals(url.getHost());
        } catch (final MalformedURLException e) {
            return false;
        }
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
