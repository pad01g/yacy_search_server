package net.yacy.htroot.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.yacy.cora.protocol.RequestHeader;

public class ClickOriginTest {

    private static RequestHeader header(final String... kv) {
        final RequestHeader h = new RequestHeader();
        h.put("Host", "localhost:8090");
        for (int i = 0; i < kv.length; i += 2) h.put(kv[i], kv[i + 1]);
        return h;
    }

    @Test
    public void testTheSearchPageMayReportClicks() {
        assertTrue(click_p.sameOrigin(header("Sec-Fetch-Site", "same-origin", "Origin", "http://localhost:8090")));
        assertTrue(click_p.sameOrigin(header("Origin", "http://localhost:8090")));
        // behind a proxy that changes Host, the browser's Sec-Fetch-Site decides
        assertTrue(click_p.sameOrigin(header("Sec-Fetch-Site", "same-origin", "Origin", "https://search.example.org")));
        // curl with the password: no browser headers
        assertTrue(click_p.sameOrigin(header()));
    }

    @Test
    public void testOtherSitesMayNot() {
        assertFalse(click_p.sameOrigin(header("Sec-Fetch-Site", "cross-site", "Origin", "https://evil.example")));
        assertFalse(click_p.sameOrigin(header("Sec-Fetch-Site", "same-site")));
        assertFalse(click_p.sameOrigin(header("Origin", "https://evil.example")));
        assertFalse(click_p.sameOrigin(header("Origin", "null")));
        assertFalse(click_p.sameOrigin(header("Referer", "https://evil.example/page")));
        assertTrue(click_p.sameOrigin(header("Referer", "http://localhost:8090/yacysearch.html?query=x")));
    }
}
