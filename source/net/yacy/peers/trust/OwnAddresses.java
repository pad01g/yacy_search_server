// OwnAddresses.java
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.net.InetAddresses;

/**
 * The public addresses of this peer as other peers see them, for the hello proof (a peer behind a NAT does not see
 * its public address on an interface). The "yourip" field and the signed "challengeFor" of one hello answer are no
 * evidence: a peer that relays our request to another peer chooses what the other peer sees. An address counts as
 * ours only after peers with {@value #MIN_PEERS} different keys, reached at {@value #MIN_NETWORKS} different networks
 * (/24 for IPv4, /48 for IPv6, the whole address for local ranges), reported it within {@link #WINDOW}. Both address
 * families are kept, so that a dual-stack peer stays proven over IPv4 after a hello over IPv6.
 */
public final class OwnAddresses {

    static final int MIN_PEERS = 3;
    static final int MIN_NETWORKS = 2;
    static final long WINDOW = 6L * 60L * 60L * 1000L;
    private static final int MAX_ADDRESSES = 64;
    private static final int MAX_REPORTS = 32;

    /** a report: the peer that answered and the network at which we reached it */
    private record Report(String peer, String network, long time) {}

    private static final Map<String, Map<String, Report>> reports = new LinkedHashMap<>(16, 0.75f, true);

    private OwnAddresses() {
    }

    /** a peer whose key answered our challenge (reached at targetIp) saw our request coming from address */
    public static synchronized void report(final String address, final String peerHash, final String targetIp, final long now) {
        final String a = canonical(address);
        final String network = network(targetIp);
        if (a == null || network == null || peerHash == null) return;
        final Map<String, Report> r = reports.computeIfAbsent(a, k -> new LinkedHashMap<>());
        r.put(peerHash, new Report(peerHash, network, now));
        while (r.size() > MAX_REPORTS) r.remove(r.keySet().iterator().next());
        while (reports.size() > MAX_ADDRESSES) reports.remove(reports.keySet().iterator().next());
    }

    /** the addresses that enough independent peers reported recently */
    public static synchronized Set<String> confirmed(final long now) {
        final Set<String> out = new HashSet<>();
        for (final Iterator<Map.Entry<String, Map<String, Report>>> i = reports.entrySet().iterator(); i.hasNext();) {
            final Map.Entry<String, Map<String, Report>> e = i.next();
            e.getValue().values().removeIf(r -> r.time() < now - WINDOW);
            if (e.getValue().isEmpty()) {
                i.remove();
                continue;
            }
            final Set<String> networks = new HashSet<>();
            for (final Report r : e.getValue().values()) networks.add(r.network());
            if (e.getValue().size() >= MIN_PEERS && networks.size() >= MIN_NETWORKS) out.add(e.getKey());
        }
        return out;
    }

    static synchronized void clear() {
        reports.clear();
    }

    static String canonical(final String address) {
        if (address == null) return null;
        final String x = address.replace("[", "").replace("]", "");
        if (!InetAddresses.isInetAddress(x)) return null;
        return InetAddresses.toAddrString(InetAddresses.forString(x));
    }

    static String network(final String ip) {
        final String c = canonical(ip);
        if (c == null) return null;
        final InetAddress ia = InetAddresses.forString(c);
        if (ia.isLoopbackAddress() || ia.isSiteLocalAddress() || ia.isLinkLocalAddress() || ia.isAnyLocalAddress()) return c;
        final byte[] b = ia.getAddress();
        if (ia instanceof Inet4Address) return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0/24";
        if ((b[0] & 0xfe) == 0xfc) return c; // unique local addresses
        return String.format("%02x%02x:%02x%02x:%02x%02x::/48", b[0], b[1], b[2], b[3], b[4], b[5]);
    }
}
