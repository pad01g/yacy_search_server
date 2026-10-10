package net.yacy.search.vector;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Test;

public class VectorSearchTest {

    private static SolrDocumentList list(final long numFound, final String... ids) {
        final SolrDocumentList l = new SolrDocumentList();
        for (final String id : ids) {
            final SolrDocument d = new SolrDocument();
            d.setField("id", id);
            d.setField("score", 1f);
            l.add(d);
        }
        l.setNumFound(numFound);
        return l;
    }

    private static String[] ids(final SolrDocumentList l) {
        return l.stream().map(d -> (String) d.getFieldValue("id")).toArray(String[]::new);
    }

    @Test
    public void testFusionKeepsBothListsAndRanksAgreementFirst() {
        // the keyword search missed "s" (it lacked some query words); the vector search found it first
        final SolrDocumentList fused = VectorSearch.fuse(list(120, "a", "b", "c"), list(50, "s", "b", "x"), 1.0f, 10);
        assertEquals("b", fused.get(0).getFieldValue("id")); // in both lists
        assertTrue(Arrays.asList(ids(fused)).contains("s"));
        assertEquals(Boolean.FALSE, fused.stream().filter(d -> d.getFieldValue("id").equals("s")).findFirst().get().getFieldValue(VectorSearch.FIELD_KEYWORD_MATCH));
        assertEquals(120, fused.getNumFound());
        // scores fall with the rank
        for (int i = 1; i < fused.size(); i++) assertTrue((Float) fused.get(i - 1).getFieldValue("score") >= (Float) fused.get(i).getFieldValue("score"));
    }

    private static SolrDocumentList scored(final long numFound, final Object... idScore) {
        final SolrDocumentList l = new SolrDocumentList();
        for (int i = 0; i < idScore.length; i += 2) {
            final SolrDocument d = new SolrDocument();
            d.setField("id", idScore[i]);
            d.setField("score", ((Number) idScore[i + 1]).floatValue());
            l.add(d);
        }
        l.setNumFound(numFound);
        return l;
    }

    @Test
    public void testScoreFusionKeepsAClearKeywordLead() {
        // "python 3.12": the title match has a clear keyword lead; the embeddings of all "What's New" pages are close
        final SolrDocumentList keyword = scored(40, "v312", 30f, "index", 12f, "v314", 11f, "v23", 10f);
        final SolrDocumentList nearest = scored(50, "index", 0.8695f, "v312", 0.8690f, "v39", 0.8684f, "v314", 0.8600f);
        final SolrDocumentList fused = VectorSearch.fuseScores(keyword, nearest, 1.0f, 10);
        assertEquals("v312", fused.get(0).getFieldValue("id"));
        // reciprocal rank fusion would put the two documents ranked 1 and 2 in both lists almost level
        final double rrf312 = 1.0 / 61 + 1.0 / 62, rrfIndex = 1.0 / 62 + 1.0 / 61;
        assertEquals(rrf312, rrfIndex, 1e-12);
    }

    @Test
    public void testScoreFusionLetsAMissedDocumentIn() {
        // the keyword query found only weak matches; the vector search found the page that answers the question
        final SolrDocumentList keyword = scored(600, "deploy", 9f, "docker", 8.5f, "jobs", 8f);
        final SolrDocumentList nearest = scored(50, "httpserver", 0.91f, "httpclient", 0.88f, "deploy", 0.80f);
        final SolrDocumentList fused = VectorSearch.fuseScores(keyword, nearest, 1.0f, 10);
        final java.util.List<String> order = Arrays.asList(ids(fused));
        assertTrue(order.indexOf("httpserver") < order.indexOf("docker"));
        assertEquals(Boolean.FALSE, fused.get(order.indexOf("httpserver")).getFieldValue(VectorSearch.FIELD_KEYWORD_MATCH));
        // weight 0: only the keyword order counts, missed documents come last
        assertEquals("deploy", VectorSearch.fuseScores(keyword, nearest, 0f, 10).get(0).getFieldValue("id"));
    }

    @Test
    public void testFusionLimitAndWeight() {
        assertEquals(2, VectorSearch.fuse(list(3, "a", "b", "c"), list(3, "x", "y", "z"), 1.0f, 2).size());
        // weight 0: the vector list only adds documents behind the keyword results
        assertArrayEquals(new String[] {"a", "b", "x", "y"}, ids(VectorSearch.fuse(list(2, "a", "b"), list(2, "x", "y"), 0f, 10)));
    }

    @Test
    public void testPassage() {
        assertEquals("Title\nSome description\nbody text", VectorSearch.passage("Title", "Some   description", " body\n text ", 100));
        assertEquals("Title\nSo", VectorSearch.passage("Title", "Some description", "body", 8));
        assertEquals("only text", VectorSearch.passage(null, " ", "only text", 100));
    }

    @Test
    public void testKnnQueryKeepsFiltersAndDropsKeywordParameters() {
        final SolrQuery kw = new SolrQuery("python http server");
        kw.set("defType", "edismax");
        kw.set("qf", "title^5 text_t");
        kw.set("mm", "2<-1 5<80%");
        kw.addFilterQuery("content_type:text/html");
        kw.setFields("id", "sku", "score");
        kw.set("hl", true);
        final SolrQuery knn = VectorSearch.knnQuery(kw, new float[] {0.5f, -0.25f}, 7);
        assertEquals("{!knn f=embedding_knn topK=7}[0.500000,-0.250000]", knn.getQuery());
        assertArrayEquals(new String[] {"content_type:text/html"}, knn.getFilterQueries());
        assertEquals(null, knn.get("mm"));
        assertEquals(null, knn.get("qf"));
        assertEquals("false", knn.get("hl"));
        assertEquals(Integer.valueOf(7), knn.getRows());
        assertEquals("id,sku,score", knn.getFields());
        // the keyword query is unchanged
        assertEquals("2<-1 5<80%", kw.get("mm"));
    }

    @Test
    public void testEmbeddingAnswerIsCheckedAndNormalized() throws IOException {
        final float[][] v = EmbeddingClient.parse("{\"data\":[{\"index\":1,\"embedding\":[0,2]},{\"index\":0,\"embedding\":[3,4]}]}", 2, 2);
        assertArrayEquals(new float[] {0.6f, 0.8f}, v[0], 1e-6f);
        assertArrayEquals(new float[] {0f, 1f}, v[1], 1e-6f);
        for (final String bad : new String[] {
                "{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}",       // other dimension
                "{\"data\":[]}",                                            // too few
                "{\"data\":[{\"index\":5,\"embedding\":[1,2]}]}",          // bad index
                "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",          // zero vector
                "not json"}) {
            try {
                EmbeddingClient.parse(bad, 1, 2);
                fail("accepted " + bad);
            } catch (final IOException expected) {
                // fine
            }
        }
    }

    @Test
    public void testPendingQueryQuotesTheModel() {
        assertEquals("*:* AND -embedding_model_s:\"intfloat/multilingual-e5-small\"", VectorIndexer.pendingQuery("intfloat/multilingual-e5-small"));
        assertEquals("*:* AND -embedding_model_s:\"a\\\"b\"", VectorIndexer.pendingQuery("a\"b"));
    }

    @Test
    public void testSkipFilter() {
        assertEquals(null, VectorIndexer.skipFilter(java.util.List.of()));
        assertEquals("*:* AND NOT _query_:\"{!terms f=id}abc-_1,XYZ\"", VectorIndexer.skipFilter(java.util.List.of("abc-_1", "XYZ")));
    }

    @Test
    public void testSectionsFollowTheStartAndTheHeadings() {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) text.append("start of the page ");
        text.append("\n## First section\n");
        for (int i = 0; i < 30; i++) text.append("first words ");
        text.append("\n## Second section\n");
        for (int i = 0; i < 30; i++) text.append("second words ");
        text.append("\n## Third section\n");
        for (int i = 0; i < 30; i++) text.append("third words ");
        final java.util.List<String> s = VectorSearch.sections("Title", text.toString(), 300, 2);
        assertEquals(2, s.size());
        assertTrue(s.get(0), s.get(0).startsWith("Title\n## First section first words"));
        assertTrue(s.get(0).length() <= 300);
        assertTrue(s.get(1), s.get(1).startsWith("Title\n## Second section"));
        // a short page has no sections, and none are asked for
        assertEquals(0, VectorSearch.sections("Title", "short", 300, 2).size());
        assertEquals(0, VectorSearch.sections("Title", text.toString(), 300, 0).size());
    }

    @Test
    public void testBestOfKeepsTheBestSimilarityPerPage() {
        final SolrDocumentList start = scored(3, "a", 0.80f, "b", 0.75f);
        final SolrDocumentList section = scored(3, "b", 0.90f, "c", 0.70f);
        final SolrDocumentList best = VectorSearch.bestOf(java.util.List.of(start, section), 2);
        assertArrayEquals(new String[] {"b", "a"}, ids(best));
        assertEquals(0.90f, (Float) best.get(0).getFieldValue("score"), 1e-6f);
    }

    @Test
    public void testAbsoluteFusionGivesPoorMatchesNothing() {
        // knn scores are (1 + cosine) / 2: 0.95 is cosine 0.90, 0.90 is cosine 0.80
        final SolrDocumentList keyword = scored(10, "k1", 10f, "k2", 5f);
        final SolrDocumentList poor = scored(50, "p1", 0.90f, "p2", 0.89f);
        final SolrDocumentList fusedPoor = VectorSearch.fuseScoresAbsolute(keyword, poor, 1.0f, 10, 0.80, 0.90);
        // a list of poor matches: the best of them gets nothing and stays behind the keyword results
        assertArrayEquals(new String[] {"k1", "k2", "p1", "p2"}, ids(fusedPoor));
        // min-max scaling would give the best poor match the full weight, in front of k2
        assertEquals("p1", VectorSearch.fuseScores(keyword, poor, 1.0f, 10).get(1).getFieldValue("id"));
        // a strong match the keyword query missed: the full weight (1.5) puts it before the best keyword match (1.0)
        final SolrDocumentList strong = scored(50, "s1", 0.95f);
        assertEquals("s1", VectorSearch.fuseScoresAbsolute(keyword, strong, 1.5f, 10, 0.80, 0.90).get(0).getFieldValue("id"));
        // a page in both lists adds both parts: k2 (0.5 + 1.5 * 0.6) before k1 (1.0)
        final SolrDocumentList both = scored(50, "k2", 0.93f);
        assertEquals("k2", VectorSearch.fuseScoresAbsolute(keyword, both, 1.5f, 10, 0.80, 0.90).get(0).getFieldValue("id"));
        assertEquals(0.90, VectorSearch.cosine(0.95), 1e-9);
    }
}
