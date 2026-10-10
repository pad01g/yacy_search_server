package net.yacy.crawler.retrieval;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.junit.Test;

public class SitemapImporterTest {

    @Test
    public void testOnlyPagesChangedAfterLoadingAreLoadedAgain() {
        final Date jan = new Date(1_700_000_000_000L), feb = new Date(1_702_600_000_000L), mar = new Date(1_705_000_000_000L);
        assertTrue(SitemapImporter.changedSince(mar, feb, jan)); // changed after it was loaded
        assertFalse(SitemapImporter.changedSince(jan, feb, null)); // loaded after the change
        assertFalse(SitemapImporter.changedSince(feb, jan, mar)); // the page's own date is newer than the sitemap's
        assertTrue(SitemapImporter.changedSince(feb, null, null)); // nothing known
        assertTrue(SitemapImporter.changedSince(null, feb, feb));
    }

    @Test
    public void testSitemapEntriesOutsideTheFiltersAreSkipped() {
        final java.util.regex.Pattern match = java.util.regex.Pattern.compile("https://developer.mozilla.org/(en-US|ja)/docs/Web.*");
        final java.util.regex.Pattern never = java.util.regex.Pattern.compile(".*\\.(pdf|zip)");
        assertTrue(SitemapImporter.inFilters("https://developer.mozilla.org/ja/docs/Web/API", match, never));
        assertFalse(SitemapImporter.inFilters("https://developer.mozilla.org/de/docs/Web/API", match, never));
        assertFalse(SitemapImporter.inFilters("https://developer.mozilla.org/en-US/docs/Web/a.pdf", match, never));
        assertTrue(SitemapImporter.inFilters("https://x.org/", null, null));
    }
}
