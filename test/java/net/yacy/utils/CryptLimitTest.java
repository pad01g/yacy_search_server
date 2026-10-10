package net.yacy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CryptLimitTest {

    @Test
    public void testDecodedTextIsLimited() {
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200000; i++) big.append('a'); // compresses to a few hundred bytes
        final String encoded = crypt.simpleEncode(big.toString(), null, 'z');
        assertNull(crypt.simpleDecode(encoded, 65536));
        assertEquals(big.toString(), crypt.simpleDecode(encoded, 300000));
        final String small = crypt.simpleEncode("a short title", null, 'z');
        assertEquals("a short title", crypt.simpleDecode(small, 100));
        assertNull(crypt.simpleDecode(small, 5));
    }
}
