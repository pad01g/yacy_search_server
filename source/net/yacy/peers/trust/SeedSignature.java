// SeedSignature.java
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.yacy.peers.Seed;

/**
 * Signs and verifies the part of a seed that only its owner decides. Receivers rewrite the observed values
 * (IP addresses, peer type, flags, counters, last-seen time) before they relay a seed, so those are not signed;
 * the address of a peer is authenticated by the hello challenge instead (see docs/trust-and-nat.md).
 */
public final class SeedSignature {

    public enum Status {
        /** PK matches the hash and Sig verifies */
        VALID,
        /** no PK / Sig: a seed of an unmodified YaCy */
        UNSIGNED,
        /** PK does not match the hash, or the signature is wrong */
        INVALID
    }

    /** the signed fields, in canonical (sorted) order; Hash is added separately */
    static final String[] CORE = {
            Seed.BDATE, Seed.NAME, Seed.P2PA, Seed.PK, Seed.PORT, Seed.PORTSSL, Seed.RDS, Seed.REACH, Seed.SIGT, Seed.TAGS
    };

    private static final String DOMAIN = "yacy-seed-v1\n";
    /** the owner signs again after this time even without changes, so that its signature stays recent */
    public static final long RESIGN_AFTER = 7L * 24L * 60L * 60L * 1000L;
    /** results of verifications: seeds are copied often (SeedDB creates new objects), the signed part rarely changes */
    private static final Map<String, Status> verified = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Status>(1024, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Status> eldest) {
            return size() > 20000;
        }
    });

    private SeedSignature() {
    }

    static String canonical(final Seed seed) {
        final StringBuilder sb = new StringBuilder(256).append(DOMAIN);
        sb.append(Seed.HASH).append('=').append(seed.hash).append('\n');
        for (final String key : CORE) {
            final String v = seed.get(key, null);
            if (v != null) sb.append(key).append('=').append(v).append('\n');
        }
        return sb.toString();
    }

    /** @return a string that changes whenever the result of {@link #verify(Seed)} can change */
    public static String cacheKey(final Seed seed) {
        return canonical(seed) + seed.get(Seed.SIG, "");
    }

    /**
     * Sign the own seed if its signed part changed since the last signature.
     * @param seed the seed of this peer
     * @param identity the identity of this peer; its hash must be the seed hash
     */
    public static void sign(final Seed seed, final PeerIdentity identity) {
        if (identity == null || !identity.peerHash().equals(seed.hash)) return;
        synchronized (seed.getMap()) {
            seed.put(Seed.PK, identity.publicKeyB64());
            final String previous = seed.get(Seed.SIG, null);
            if (previous != null && verifyCanonical(seed, previous) && System.currentTimeMillis() - sigTime(seed) < RESIGN_AFTER) return; // still valid, keep SigT stable
            seed.put(Seed.SIGT, Long.toString(System.currentTimeMillis()));
            seed.put(Seed.SIG, identity.sign(canonical(seed)));
        }
    }

    private static boolean verifyCanonical(final Seed seed, final String sig) {
        final String pk = seed.get(Seed.PK, null);
        return pk != null && Ed25519.verify(pk, canonical(seed), sig);
    }

    /** @return the signature time of a seed in ms, 0 if missing */
    public static long sigTime(final Seed seed) {
        try {
            return Long.parseLong(seed.get(Seed.SIGT, "0"));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** {@link #verify(Seed)} with a process-wide cache of results */
    public static Status verifyCached(final Seed seed) {
        final String key = cacheKey(seed);
        final Status cached = verified.get(key);
        if (cached != null) return cached;
        final Status status = verify(seed);
        verified.put(key, status);
        return status;
    }

    public static Status verify(final Seed seed) {
        final String pk = seed.get(Seed.PK, null);
        final String sig = seed.get(Seed.SIG, null);
        if (pk == null && sig == null) return Status.UNSIGNED;
        if (pk == null || sig == null) return Status.INVALID;
        final String expectedHash = PeerIdentity.peerHashOf(pk);
        if (expectedHash == null || !expectedHash.equals(seed.hash)) return Status.INVALID;
        return Ed25519.verify(pk, canonical(seed), sig) ? Status.VALID : Status.INVALID;
    }

    private static final String HELLO_DOMAIN = "yacy-hello-v2|";
    /** the observed address of requests that the libp2p sidecar carried */
    public static final String OBSERVED_SIDECAR = "p2p";

    /**
     * The answer covers the address this peer saw the request coming from. Without it, a peer E could forward a
     * challenge it got to the real peer V and present V's answer as its own; with it, the challenger sees that V
     * answered a request that came from E, not from the challenger.
     * @param observed the client address of the request, or {@link #OBSERVED_SIDECAR}
     * @return the answer of this peer to a hello challenge
     */
    public static String answerChallenge(final PeerIdentity identity, final String challenge, final String observed) {
        return identity.sign(HELLO_DOMAIN + challenge + "|" + identity.peerHash() + "|" + observed);
    }

    /** @return true if the answer was made with the key in the seed, for a request seen from the given address */
    public static boolean checkChallenge(final Seed seed, final String challenge, final String observed, final String answer) {
        final String pk = seed.get(Seed.PK, null);
        if (pk == null || answer == null || !isValidChallenge(challenge) || !isValidObserved(observed)) return false;
        if (!seed.hash.equals(PeerIdentity.peerHashOf(pk))) return false;
        return Ed25519.verify(Ed25519.decode(pk), (HELLO_DOMAIN + challenge + "|" + seed.hash + "|" + observed).getBytes(StandardCharsets.UTF_8), Ed25519.decode(answer));
    }

    /** challenges are base64url strings, so that the signed message cannot be read in two ways */
    public static boolean isValidChallenge(final String challenge) {
        return challenge != null && challenge.length() >= 8 && challenge.length() <= 64 && challenge.matches("[A-Za-z0-9_-]+");
    }

    /** the observed address is an IP literal or the sidecar marker */
    public static boolean isValidObserved(final String observed) {
        if (observed == null) return false;
        if (OBSERVED_SIDECAR.equals(observed)) return true;
        return com.google.common.net.InetAddresses.isInetAddress(observed.replace("[", "").replace("]", ""));
    }

    /** multiaddrs and tags travel in seed fields: they must not contain the separators of the seed format */
    public static boolean isSafeFieldValue(final String v) {
        if (v == null) return true;
        for (int i = 0; i < v.length(); i++) {
            final char c = v.charAt(i);
            if (c == ',' || c == '=' || c == '\n' || c == '\r' || c == '{' || c == '}') return false;
        }
        return true;
    }
}
