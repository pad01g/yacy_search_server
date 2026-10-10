package net.yacy.search.ranking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.Test;

public class VersionCollapseTest {

    @Test
    public void testVersionsAndSpellingsShareAKey() {
        assertEquals(VersionCollapse.key("https://docs.docker.jp/v19.03/develop/develop-images/multistage-build.html"),
                VersionCollapse.key("https://docs.docker.jp/v20.10/develop/develop-images/multistage-build.html"));
        assertEquals(VersionCollapse.key("https://www.postgresql.org/docs/current/routine-vacuuming.html"),
                VersionCollapse.key("https://www.postgresql.org/docs/17/routine-vacuuming.html"));
        assertEquals(VersionCollapse.key("https://kubernetes.io/docs/concepts/configuration/secret/"),
                VersionCollapse.key("https://kubernetes.io/docs/concepts/configuration/secret"));
        assertEquals(VersionCollapse.key("https://docs.docker.jp/compose/index.html"), VersionCollapse.key("https://docs.docker.jp/compose/"));
        assertEquals(VersionCollapse.key("https://docs.python.org/3/library/os.html#os.walk"), VersionCollapse.key("https://docs.python.org/3.12/library/os.html"));
    }

    @Test
    public void testDifferentPagesKeepTheirKeys() {
        // the file name is the page, not a version directory
        assertNotEquals(VersionCollapse.key("https://docs.python.org/3/whatsnew/3.12.html"), VersionCollapse.key("https://docs.python.org/3/whatsnew/3.13.html"));
        // other languages are other pages
        assertNotEquals(VersionCollapse.key("https://docs.python.org/3/library/os.html"), VersionCollapse.key("https://docs.python.org/ja/3/library/os.html"));
        assertNotEquals(VersionCollapse.key("https://example.org/list?page=1"), VersionCollapse.key("https://example.org/list?page=2"));
        assertNotEquals(VersionCollapse.key("https://example.org/a/b"), VersionCollapse.key("https://example.org/b"));
    }

    @Test
    public void testCollapseKeepsTheBestRanked() {
        final SolrDocumentList l = new SolrDocumentList();
        for (final String u : new String[] {"https://docs.docker.jp/v20.10/x.html", "https://docs.docker.com/y/", "https://docs.docker.jp/v19.03/x.html", "https://docs.docker.com/y", "https://docs.docker.com/z"}) {
            final SolrDocument d = new SolrDocument();
            d.setField("sku", u);
            l.add(d);
        }
        l.setNumFound(5);
        final SolrDocumentList c = VersionCollapse.collapse(l, 10);
        assertEquals(3, c.size());
        assertEquals("https://docs.docker.jp/v20.10/x.html", c.get(0).getFieldValue("sku"));
        assertEquals("https://docs.docker.com/z", c.get(2).getFieldValue("sku"));
        assertEquals(5, c.getNumFound());
        assertEquals(2, VersionCollapse.collapse(l, 2).size());
    }

    @Test
    public void testTheSegmentThatNamesThePageIsNoVersion() {
        assertNotEquals(VersionCollapse.key("https://example.org/forum/123/"), VersionCollapse.key("https://example.org/forum/456/"));
        assertNotEquals(VersionCollapse.key("https://example.org/blog/2023/"), VersionCollapse.key("https://example.org/blog/2024/"));
        assertNotEquals(VersionCollapse.key("https://example.org/article/1001/index.html"), VersionCollapse.key("https://example.org/article/1002/index.html"));
        assertNotEquals(VersionCollapse.key("https://example.org/list/page/2/"), VersionCollapse.key("https://example.org/list/page/3/"));
    }

    @Test
    public void testOnlyCopiesWithTheSameTitleCollapse() {
        final SolrDocumentList l = new SolrDocumentList();
        final String[][] docs = {
                {"https://github.com/o/r/pull/12/files", "Fix the parser by alice"},
                {"https://github.com/o/r/pull/34/files", "Add a cache by bob"},
                {"https://www.postgresql.org/docs/17/routine-vacuuming.html", "PostgreSQL: Documentation: 17: 24.1. Routine Vacuuming"},
                {"https://www.postgresql.org/docs/current/routine-vacuuming.html", "PostgreSQL: Documentation: 18: 24.1. Routine Vacuuming"}};
        for (final String[] x : docs) {
            final SolrDocument d = new SolrDocument();
            d.setField("sku", x[0]);
            d.setField("title", java.util.List.of(x[1]));
            l.add(d);
        }
        final SolrDocumentList c = VersionCollapse.collapse(l, 10);
        assertEquals(3, c.size());
        assertEquals("https://www.postgresql.org/docs/17/routine-vacuuming.html", c.get(2).getFieldValue("sku"));
    }
}
