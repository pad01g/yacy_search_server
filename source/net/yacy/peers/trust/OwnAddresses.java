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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.net.InetAddresses;

/**
 * The public addresses of this peer as other peers see them, for the hello proof (a peer behind a NAT does not see
 * its public address on an interface). The "yourip" field and the signed "challengeFor" of one hello answer are no
 * evidence: a peer that relays our request to another peer chooses what the other peer sees. An address counts as
 * ours only after peers with {@value #MIN_PEERS} different keys, reached at {@value #MIN_NETWORKS} different networks
 * (/24 for IPv4, /48 for IPv6, the whole address for local ranges), reported it within {@link #WINDOW}. A key has one
 * vote per address family, its latest report, so that one key cannot fill the table with made-up addresses; with
 * both families a dual-stack peer stays proven over IPv4 after a hello over IPv6.
 */
public final class OwnAddresses {

    static final int MIN_PEERS = 3;
    static final int MIN_NETWORKS = 2;
    static final long WINDOW = 6L * 60L * 60L * 1000L;
    /** reporting keys remembered; each key has one vote (its latest report), so one key cannot push others out */
    static final int MAX_REPORTERS = 256;

    /** a report: the address the peer saw, the network at which we reached it */
    private record Report(String address, String network, long time) {}

    /** reporting peer (key hash and address family) -> its latest report */
    private static final Map<String, Report> reports = new LinkedHashMap<>(64, 0.75f, false);

    private OwnAddresses() {
    }

    /** a peer whose key answered our challenge (reached at targetIp) saw our request coming from address */
    public static synchronized void report(final String address, final String peerHash, final String targetIp, final long now) {
        final String a = canonical(address);
        final String network = network(targetIp);
        if (a == null || network == null || peerHash == null) return;
        // one vote per key and address family (a dual-stack peer is seen over both)
        final String voter = peerHash + (a.indexOf(':') >= 0 ? "|6" : "|4");
        reports.remove(voter); // re-insert at the end: the oldest reports come first
        reports.put(voter, new Report(a, network, now));
        if (reports.size() > MAX_REPORTERS) {
            // make room with a report of an address that is not confirmed, so that new keys cannot evict the
            // reports that prove our real address; the oldest report only if all are confirmed
            final Set<String> ok = confirmed(now);
            String victim = null;
            for (final Map.Entry<String, Report> e : reports.entrySet()) {
                if (!ok.contains(e.getValue().address())) {
                    victim = e.getKey();
                    break;
                }
            }
            reports.remove(victim != null ? victim : reports.keySet().iterator().next());
        }
    }

    /** the addresses that enough independent peers reported recently */
    public static synchronized Set<String> confirmed(final long now) {
        reports.values().removeIf(r -> r.time() < now - WINDOW);
        final Map<String, Set<String>> peers = new HashMap<>(), networks = new HashMap<>();
        for (final Map.Entry<String, Report> e : reports.entrySet()) {
            peers.computeIfAbsent(e.getValue().address(), k -> new HashSet<>()).add(e.getKey().substring(0, e.getKey().length() - 2));
            networks.computeIfAbsent(e.getValue().address(), k -> new HashSet<>()).add(e.getValue().network());
        }
        final Set<String> out = new HashSet<>();
        for (final Map.Entry<String, Set<String>> e : peers.entrySet()) {
            if (e.getValue().size() >= MIN_PEERS && networks.get(e.getKey()).size() >= MIN_NETWORKS) out.add(e.getKey());
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
