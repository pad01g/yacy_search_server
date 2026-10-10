package net.yacy.search.ranking;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.apache.solr.common.SolrDocument;
import org.junit.Test;

public class AnchorIndexerTest {

    private static SolrDocument page(final List<String> stubs, final List<String> texts) {
        final SolrDocument d = new SolrDocument();
        d.setField("inboundlinks_urlstub_sxt", stubs);
        d.setField("inboundlinks_anchortext_txt", texts);
        return d;
    }

    @Test
    public void testAnchorTextsOfTheTargetMostFrequentFirst() {
        final String hpa = "kubernetes.io/docs/tasks/run-application/horizontal-pod-autoscale/";
        final SolrDocument a = page(Arrays.asList(hpa, "kubernetes.io/other/", hpa + "#walkthrough"), Arrays.asList("Horizontal Pod Autoscaler", "Other", "HPA"));
        final SolrDocument b = page(Arrays.asList(hpa, hpa), Arrays.asList("horizontal  pod autoscaler", "https://kubernetes.io/x"));
        final SolrDocument c = page(Arrays.asList(hpa), Arrays.asList("水平 Pod 自動スケーリング"));
        final List<String> texts = AnchorIndexer.anchorTexts(hpa, Arrays.asList(a, b, c));
        // case-insensitive duplicates count together, URLs as anchor texts and other targets are left out
        assertEquals(Arrays.asList("Horizontal Pod Autoscaler", "HPA", "水平 Pod 自動スケーリング"), texts);
    }

    @Test
    public void testStubAndQueries() {
        assertEquals("docs.python.org/3/library/os.html", AnchorIndexer.stub("https://docs.python.org/3/library/os.html"));
        assertEquals("\"a\\\"b\"", AnchorIndexer.quote("a\"b"));
        assertEquals("*:* AND -anchors_in_date_dt:[NOW-24HOURS TO *]", AnchorIndexer.pendingQuery(24));
    }
}
