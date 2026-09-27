// TrustPolicy.java
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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import net.yacy.search.Switchboard;

/**
 * The configuration of identity, trust and NAT traversal (see docs/trust-and-nat.md, section 7).
 * Without a running Switchboard (tools, unit tests) the checks fall back to the behavior of an unmodified YaCy.
 */
public final class TrustPolicy {

    public static final String SEED_ACCEPT_UNSIGNED = "trust.seed.acceptUnsigned";
    public static final String COORDINATORS = "trust.coordinators";
    public static final String BUNDLE_URLS = "trust.bundle.urls";
    public static final String SEARCH_ACCEPT_UNVERIFIED = "trust.search.acceptUnverified";
    public static final String POLICY_EXCLUDE_TAGS = "trust.policy.excludeTags";
    public static final String SELF_TAGS = "trust.selfTags";

    public static final String P2P_SIDECAR_URL = "p2p.sidecar.url";
    public static final String P2P_MODE = "p2p.mode";
    public static final String P2P_MODE_AUTO = "auto";
    public static final String P2P_MODE_DIRECT = "direct";
    public static final String P2P_MODE_LEECHER = "leecher";
    public static final String P2P_RELAY_DHT_STORAGE = "p2p.relay.dhtStorage";

    /** the remote search waiting time may be configured up to this many milliseconds */
    public static final long REMOTESEARCH_MAXTIME_LIMIT = 10000;
    public static final long REMOTESEARCH_MAXTIME_DEFAULT = 5000;

    private TrustPolicy() {
    }

    private static Switchboard sb() {
        return Switchboard.getSwitchboard();
    }

    /** @return true if seeds without owner signature (unmodified YaCy peers) are accepted */
    public static boolean acceptUnsignedSeeds() {
        final Switchboard sb = sb();
        return sb == null || sb.getConfigBool(SEED_ACCEPT_UNSIGNED, false);
    }

    /** @return true if documents that are not signed by a trusted author are shown, marked as unverified */
    public static boolean acceptUnverifiedResults() {
        final Switchboard sb = sb();
        return sb == null || sb.getConfigBool(SEARCH_ACCEPT_UNVERIFIED, false);
    }

    public static Set<String> excludedTags() {
        final Switchboard sb = sb();
        return sb == null ? Collections.<String>emptySet() : tags(sb.getConfig(POLICY_EXCLUDE_TAGS, ""));
    }

    public static Set<String> selfTags() {
        final Switchboard sb = sb();
        return sb == null ? Collections.<String>emptySet() : tags(sb.getConfig(SELF_TAGS, ""));
    }

    /** parse a list of tags separated by comma, space or '|'; tags are lower case */
    public static Set<String> tags(final String list) {
        final Set<String> tags = new LinkedHashSet<>();
        if (list == null) return tags;
        for (final String t : list.split("[,|\\s]+")) {
            final String tag = t.trim().toLowerCase(Locale.ROOT);
            if (isValidTag(tag)) tags.add(tag);
        }
        return tags;
    }

    /** tags are short tokens of letters, digits and -_.: so that they can travel in seeds and lists */
    public static boolean isValidTag(final String tag) {
        return tag != null && !tag.isEmpty() && tag.length() <= 64 && tag.matches("[a-z0-9][a-z0-9._:-]*");
    }

    /** @return the base URL of the libp2p sidecar, or null if NAT traversal is not used */
    public static String sidecarURL() {
        final Switchboard sb = sb();
        if (sb == null) return null;
        String url = sb.getConfig(P2P_SIDECAR_URL, "").trim();
        if (url.isEmpty()) return null;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    public static String p2pMode() {
        final Switchboard sb = sb();
        final String mode = sb == null ? P2P_MODE_AUTO : sb.getConfig(P2P_MODE, P2P_MODE_AUTO).trim().toLowerCase(Locale.ROOT);
        return P2P_MODE_DIRECT.equals(mode) || P2P_MODE_LEECHER.equals(mode) ? mode : P2P_MODE_AUTO;
    }

    public static boolean relayDhtStorage() {
        final Switchboard sb = sb();
        return sb != null && sb.getConfigBool(P2P_RELAY_DHT_STORAGE, false);
    }

    /** clamp a configured remote search time to (0, 10000] ms */
    public static long clampRemoteSearchTime(final long configured) {
        if (configured <= 0) return REMOTESEARCH_MAXTIME_DEFAULT;
        return Math.min(configured, REMOTESEARCH_MAXTIME_LIMIT);
    }
}
