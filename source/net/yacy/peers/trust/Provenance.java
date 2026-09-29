// Provenance.java
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.kelondro.data.word.Word;

/**
 * The author signature of an indexed document (see docs/trust-and-nat.md, section 5).
 *
 * Value format: {@code 1|<author public key>|<signature>|<word bloom filter>}, all base64url. The signature covers
 * {@code "yacy-doc-v1\n" + normalized URL + "\n" + title + "\n" + bloom}. The bloom filter holds the word hashes
 * of the document, so that a peer that stores the document cannot attach it to words it does not contain.
 */
public final class Provenance {

    public static final String VERSION = "1";
    private static final String DOMAIN = "yacy-doc-v1\n";
    static final int BLOOM_MIN_BITS = 512;
    static final int BLOOM_MAX_BITS = 32768;
    static final int BLOOM_HASHES = 4;
    /** above this share of set bits, a word matches by chance with probability > 0.5^4 = 6% */
    static final double BLOOM_MAX_FILL = 0.5d;

    static double fillRatio(final byte[] filter) {
        if (filter == null || filter.length == 0) return 1.0d;
        int set = 0;
        for (final byte b : filter) set += Integer.bitCount(b & 0xff);
        return (double) set / (filter.length * 8);
    }
    /** longest accepted value; a 32768 bit bloom filter is 5462 base64 characters */
    public static final int MAX_LENGTH = 6000;

    public enum Status {
        /** written by this peer */
        SELF,
        /** valid signature of an author in the effective trust set */
        TRUSTED,
        /** valid signature, but the author is not trusted */
        SIGNED,
        /** no author signature */
        UNSIGNED,
        /** a signature that does not match the document: always rejected */
        INVALID,
        /** a result of an external search engine the administrator configured (heuristics, federated search) */
        EXTERNAL
    }

    public static final class Verdict {
        public final Status status;
        public final String authorHash;
        /** trust entry of the author, null unless TRUSTED */
        public final TrustStore.Entry entry;
        final byte[] bloom;

        Verdict(final Status status, final String authorHash, final TrustStore.Entry entry, final byte[] bloom) {
            this.status = status;
            this.authorHash = authorHash;
            this.entry = entry;
            this.bloom = bloom;
        }

        public boolean isTrusted() {
            return this.status == Status.SELF || this.status == Status.TRUSTED;
        }

        public Set<String> tags() {
            return this.entry == null ? Collections.<String>emptySet() : this.entry.tags;
        }

        /** factor for the ranking score: the priority weight of the author, 1 for own documents */
        public double weight() {
            return this.entry == null ? 1.0d : this.entry.weight();
        }

        /**
         * @return true if every word hash is (probably) in the document; always true for documents without a filter.
         *         The filter of a very long document of another author can be so full that it matches almost any word;
         *         it then does not vouch for the words, and word index results of that document are not used (they
         *         can still be found through Solr, which matches the text itself).
         */
        public boolean containsAll(final Iterable<byte[]> wordHashes) {
            if (this.bloom == null) return true;
            if (this.status != Status.SELF && fillRatio(this.bloom) > BLOOM_MAX_FILL) return false;
            for (final byte[] h : wordHashes) if (!bloomContains(this.bloom, h)) return false;
            return true;
        }
    }

    private Provenance() {
    }

    private static String payload(final String url, final String title, final String bloomB64) {
        return DOMAIN + normalize(url) + "\n" + (title == null ? "" : title) + "\n" + bloomB64;
    }

    private static String normalize(final String url) {
        try {
            return new DigestURL(url).toNormalform(true);
        } catch (final Exception e) {
            return url;
        }
    }

    /**
     * @param url the document URL
     * @param title the title as it is stored (first title value)
     * @param words the words of the document as the condenser found them (the words that go into the RWI)
     * @return the provenance value signed by this peer, or null without identity
     */
    public static String sign(final String url, final String title, final Collection<String> words) {
        final PeerIdentity identity = PeerIdentity.get();
        if (identity == null) return null;
        final byte[] bloom = bloom(words);
        final String bloomB64 = Ed25519.encode(bloom);
        final String sig = identity.sign(payload(url, title, bloomB64));
        return VERSION + "|" + identity.publicKeyB64() + "|" + sig + "|" + bloomB64;
    }

    /**
     * @return the effective trust set of this peer, or null if every validly signed author counts as trusted: only
     *         with trust.signedOnly=true and no coordinator, or without a store (tools, unit tests). Without
     *         coordinators the set is empty, so that only this peer's own documents are trusted (fail closed).
     */
    public static Map<String, TrustStore.Entry> currentTrustSet() {
        final TrustStore store = TrustStore.get();
        if (store == null) return null;
        if (TrustPolicy.coordinators().isEmpty()) return TrustPolicy.signedOnly() ? null : Collections.<String, TrustStore.Entry>emptyMap();
        return store.effective();
    }

    /**
     * @param value the provenance value of the document, may be null
     * @param url the document URL
     * @param title the document title as delivered
     * @param trusted the effective trust set, or null if no coordinator is configured (every valid author is trusted)
     */
    public static Verdict verify(final String value, final String url, final String title, final Map<String, TrustStore.Entry> trusted) {
        if (value == null || value.isEmpty()) return new Verdict(Status.UNSIGNED, null, null, null);
        if (value.length() > MAX_LENGTH) return new Verdict(Status.INVALID, null, null, null);
        final String[] parts = value.split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) return new Verdict(Status.INVALID, null, null, null);
        final String author = PeerIdentity.peerHashOf(parts[1]);
        final byte[] bloom = Ed25519.decode(parts[3]);
        if (author == null || bloom == null || !isValidBloomSize(bloom.length)) return new Verdict(Status.INVALID, author, null, null);
        if (!Ed25519.verify(parts[1], payload(url, title, parts[3]), parts[2])) return new Verdict(Status.INVALID, author, null, null);
        if (PeerIdentity.isMine(author)) return new Verdict(Status.SELF, author, null, bloom);
        if (trusted == null) return new Verdict(Status.TRUSTED, author, null, bloom);
        // compare the full key, not only the 72 bit peer hash
        final TrustStore.Entry entry = trusted.get(author);
        return entry == null || !entry.publicKey.equals(TrustStore.canonicalKey(parts[1]))
                ? new Verdict(Status.SIGNED, author, null, bloom) : new Verdict(Status.TRUSTED, author, entry, bloom);
    }

    // ---- decisions of the search (see docs/trust-and-nat.md, sections 5 and 8)

    /**
     * @return true if a result with this verdict may be shown: never for INVALID or for authors with an excluded tag;
     *         trusted authors always; others only with trust.search.acceptUnverified=true
     */
    public static boolean accept(final Verdict v) {
        if (v == null || v.status == Status.INVALID) return false;
        if (v.status == Status.EXTERNAL) return true; // the administrator chose these sources
        if (!v.tags().isEmpty()) {
            for (final String t : TrustPolicy.excludedTags()) if (v.tags().contains(t)) return false;
        }
        return v.isTrusted() || TrustPolicy.acceptUnverifiedResults();
    }

    /**
     * The decision of a peer that answers the search of another peer: it only drops what is certainly wrong
     * (invalid signatures); the searcher applies its own trust set to the rest.
     */
    public static boolean acceptForRemotePeer(final Verdict v) {
        return v != null && v.status != Status.INVALID;
    }

    /** @return the verdict for results of external search engines */
    public static Verdict external() {
        return new Verdict(Status.EXTERNAL, null, null, null);
    }

    /** @return the verdict for an unsigned document of the own index that did not come from other peers */
    public static Verdict localDocument() {
        return new Verdict(Status.SELF, null, null, null);
    }

    /** @return true if the peer that sent the result wrote the document and is trusted */
    public static boolean isFromAuthor(final Verdict v, final net.yacy.peers.Seed answering) {
        return v != null && answering != null && v.authorHash != null && v.authorHash.equals(answering.hash)
                && v.isTrusted() && isTrustedPeer(answering) && ProvenAddresses.isProven(answering);
    }

    /** @return true if the peer is in the trust set, or signed when no coordinator is configured */
    public static boolean isTrustedPeer(final net.yacy.peers.Seed seed) {
        if (seed == null) return false;
        if (PeerIdentity.isMine(seed.hash)) return true;
        final Map<String, TrustStore.Entry> set = currentTrustSet();
        if (set == null) return seed.isSigned();
        final TrustStore.Entry e = set.get(seed.hash);
        return e != null && seed.isSigned() && e.publicKey.equals(TrustStore.canonicalKey(seed.get(net.yacy.peers.Seed.PK, null)));
    }

    /**
     * The snippet of a result is not signed. Keep it only if the peer that answered wrote the document or is trusted.
     * @param answering the peer that sent the result, null for the own index
     */
    public static boolean keepSnippet(final Verdict v, final net.yacy.peers.Seed answering) {
        if (answering == null) return true;
        // the address of a seed is not signed: only believe that the owner answered at a proven address
        if (!ProvenAddresses.isProven(answering)) return false;
        if (v != null && v.authorHash != null && v.authorHash.equals(answering.hash)) return true;
        return isTrustedPeer(answering);
    }

    // ---- bloom filter of word hashes

    private static boolean isValidBloomSize(final int bytes) {
        final int bits = bytes * 8;
        return bits >= BLOOM_MIN_BITS && bits <= BLOOM_MAX_BITS && Integer.bitCount(bits) == 1;
    }

    static byte[] bloom(final Collection<String> words) {
        int bits = BLOOM_MIN_BITS;
        final long wanted = (long) words.size() * 12L;
        while (bits < wanted && bits < BLOOM_MAX_BITS) bits <<= 1;
        final byte[] filter = new byte[bits / 8];
        for (final String w : words) {
            final byte[] h = Word.word2hash(w);
            set(filter, h);
        }
        return filter;
    }

    private static int[] positions(final byte[] wordHash, final int bits) {
        final byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256").digest(wordHash);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final int h1 = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        final int h2 = ((d[4] & 0xff) << 24) | ((d[5] & 0xff) << 16) | ((d[6] & 0xff) << 8) | (d[7] & 0xff);
        final int[] p = new int[BLOOM_HASHES];
        for (int i = 0; i < BLOOM_HASHES; i++) p[i] = Math.floorMod(h1 + i * h2, bits);
        return p;
    }

    private static void set(final byte[] filter, final byte[] wordHash) {
        for (final int p : positions(wordHash, filter.length * 8)) filter[p >>> 3] |= (byte) (1 << (p & 7));
    }

    static boolean bloomContains(final byte[] filter, final byte[] wordHash) {
        for (final int p : positions(wordHash, filter.length * 8)) {
            if ((filter[p >>> 3] & (1 << (p & 7))) == 0) return false;
        }
        return true;
    }

    /** @return the word hashes of a hash string (a concatenation of 12 character word hashes) */
    public static java.util.List<byte[]> splitHashes(final String hashes) {
        final java.util.List<byte[]> list = new java.util.ArrayList<>();
        if (hashes == null) return list;
        for (int i = 0; i + Word.commonHashLength <= hashes.length(); i += Word.commonHashLength) {
            list.add(ASCII.getBytes(hashes.substring(i, i + Word.commonHashLength)));
        }
        return list;
    }
}
