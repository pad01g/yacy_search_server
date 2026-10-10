package net.yacy.search.ranking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public class ClickStoreTest {

    @Test
    public void testQueriesAreNormalizedWithoutOperators() {
        assertEquals("rust ownership", ClickStore.normalize("  Rust   OWNERSHIP site:doc.rust-lang.org /language/en "));
        assertEquals("所有権", ClickStore.normalize("所有権"));
        assertEquals("", ClickStore.normalize(null));
    }

    @Test
    public void testRecordAndWeight() throws Exception {
        final File f = Files.createTempFile("clicks", ".json").toFile();
        f.delete();
        final ClickStore s = new ClickStore(f);
        assertTrue(s.record("Rust ownership", "AbCdEf012-_x"));
        assertTrue(s.record("rust  ownership", "AbCdEf012-_x"));
        assertFalse(s.record("rust ownership", "not a hash"));
        assertFalse(s.record(" ", "AbCdEf012-_x"));
        assertEquals(Integer.valueOf(2), s.clicks("rust ownership").get("AbCdEf012-_x"));
        assertTrue(s.clicks("other").isEmpty());
        s.save();
        // stored and read again
        assertEquals(Integer.valueOf(2), new ClickStore(f).clicks("RUST ownership").get("AbCdEf012-_x"));
        f.delete();
        // weight: 1 without clicks, growing, at most 1 + max
        assertEquals(1.0d, ClickStore.weight(0, 0.5d), 1e-9);
        assertTrue(ClickStore.weight(1, 0.5d) > 1.0d && ClickStore.weight(1, 0.5d) < ClickStore.weight(4, 0.5d));
        assertEquals(1.5d, ClickStore.weight(100, 0.5d), 1e-9);
        assertEquals(1.0d, ClickStore.weight(5, 0d), 1e-9);
    }

    @Test
    public void testLimits() {
        final ClickStore s = new ClickStore(null);
        for (int i = 0; i < ClickStore.MAX_PAGES_PER_QUERY + 5; i++) s.record("q", String.format("h%011d", i));
        assertEquals(ClickStore.MAX_PAGES_PER_QUERY, s.clicks("q").size());
        for (int i = 0; i < ClickStore.MAX_QUERIES + 5; i++) s.record("query " + i, "AbCdEf012-_x");
        assertTrue(s.clicks("q").isEmpty()); // the least recently used query was dropped
        assertEquals(1, s.clicks("query " + (ClickStore.MAX_QUERIES + 4)).size());
    }
}
