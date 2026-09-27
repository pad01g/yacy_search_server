package net.yacy.search.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.solr.common.SolrDocument;
import org.junit.Test;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.kelondro.data.meta.URIMetadataNode;
import net.yacy.search.schema.CollectionSchema;

public class SearchEventCoverageTest {

    private static URIMetadataNode node(final String url, final String title) throws MalformedURLException {
        final DigestURL u = new DigestURL(url);
        final SolrDocument doc = new SolrDocument();
        doc.addField(CollectionSchema.id.name(), ASCII.String(u.hash()));
        doc.addField(CollectionSchema.sku.name(), u.toNormalform(false));
        doc.addField(CollectionSchema.title.name(), title);
        return new URIMetadataNode(doc);
    }

    @Test
    public void testAllTermsInTitle() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/a.html", "Running your own ERC-4337 bundler");
        assertEquals(1.0d, SearchEvent.termCoverage(n, Arrays.asList("erc-4337", "bundler"), null), 0.0d);
    }

    @Test
    public void testMissingTermLowersCoverage() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/ruby.html", "Bundler::Fetcher - Ruby Bundler");
        assertEquals(0.5d, SearchEvent.termCoverage(n, Arrays.asList("erc-4337", "bundler"), null), 0.0d);
    }

    @Test
    public void testSnippetsCountWithoutMarkup() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/b.html", "Account abstraction");
        final List<String> snippets = Collections.singletonList("An <b>ERC</b>-<b>4337</b> <b>bundler</b> collects UserOperations");
        assertEquals(1.0d, SearchEvent.termCoverage(n, Arrays.asList("erc-4337", "bundler"), snippets), 0.0d);
    }

    @Test
    public void testCJKSubstring() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/c.html", "暗号資産交換業者の登録制度について");
        assertEquals(2.0d / 3.0d, SearchEvent.termCoverage(n, Arrays.asList("暗号資産交換業", "登録", "金融庁"), null), 1e-9);
    }

    @Test
    public void testEntitiesAndWidthForms() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/e.html", "ＥＲＣ-4337 &amp; bundler");
        assertEquals(1.0d, SearchEvent.termCoverage(n, Arrays.asList(SearchEvent.normalizeForCoverage("erc-4337"), "&", "bundler"), null), 0.0d);
    }

    @Test
    public void testTrustRanking() {
        final net.yacy.peers.trust.Provenance.Verdict self = net.yacy.peers.trust.Provenance.localDocument();
        assertEquals(1000L, SearchEvent.trustRanking(1000L, self));
        // unverified results always rank below verified ones, whatever their own ranking
        assertTrue(SearchEvent.trustRanking(Long.MAX_VALUE / 2, null) < SearchEvent.trustRanking(-1000000L, self));
        assertTrue(SearchEvent.trustRanking(-10L, null) < SearchEvent.trustRanking(-1000000L, self));
    }

    @Test
    public void testNoTermsMeansFullCoverage() throws MalformedURLException {
        final URIMetadataNode n = node("http://example.org/d.html", "anything");
        assertEquals(1.0d, SearchEvent.termCoverage(n, Collections.emptyList(), null), 0.0d);
    }

    @Test
    public void testThinWeight() {
        assertEquals(1.0d, SearchEvent.thinWeight(150, 100, 1.0d), 0.0d);
        assertEquals(1.0d, SearchEvent.thinWeight(100, 100, 1.0d), 0.0d);
        assertEquals(0.4d, SearchEvent.thinWeight(40, 100, 1.0d), 1e-9);
        assertEquals(0.2d, SearchEvent.thinWeight(4, 100, 0.5d), 1e-9);
        // floor, unknown count, switched off
        assertEquals(0.1d, SearchEvent.thinWeight(1, 100, 1.0d), 0.0d);
        assertEquals(1.0d, SearchEvent.thinWeight(0, 100, 1.0d), 0.0d);
        assertEquals(1.0d, SearchEvent.thinWeight(10, 0, 1.0d), 0.0d);
        assertEquals(1.0d, SearchEvent.thinWeight(10, 100, 0.0d), 0.0d);
    }

    @Test
    public void testThinRankingLowersPositiveAndNegative() {
        assertEquals(400L, SearchEvent.thinRanking(1000L, 40, 100, 1.0d));
        assertTrue(SearchEvent.thinRanking(-1000L, 40, 100, 1.0d) < -1000L);
        assertEquals(1000L, SearchEvent.thinRanking(1000L, 200, 100, 1.0d));
        // no overflow at the limits
        assertTrue(SearchEvent.thinRanking(Long.MAX_VALUE, 40, 100, 1.0d) > 0);
        assertTrue(SearchEvent.thinRanking(Long.MIN_VALUE, 40, 100, 1.0d) < 0);
    }
}
