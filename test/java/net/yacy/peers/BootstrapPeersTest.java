package net.yacy.peers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class BootstrapPeersTest {

    @Test
    public void testUrlsAreNormalizedAndLimited() {
        assertEquals(Arrays.asList("http://100.101.102.103:8090", "https://peer.example:443", "http://[fd7a:115c:a1e0::1]:8090"),
                BootstrapPeers.urls(" http://100.101.102.103:8090/ , https://peer.example, ftp://x.example, http://u:p@h.example:8090,"
                        + "http://100.101.102.103:8090/yacy, http://[fd7a:115c:a1e0::1]:8090"));
        final StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20; i++) many.append("http://10.0.0.").append(i).append(":8090,");
        assertEquals(BootstrapPeers.MAX_URLS, BootstrapPeers.urls(many.toString()).size());
        assertEquals(0, BootstrapPeers.urls(null).size());
    }

    @Test
    public void testHashIsReadFromTheOwnSeed() {
        assertEquals("NnkoZAxaheKP", BootstrapPeers.hashOf("{\"peers\":[{\"Hash\":\"NnkoZAxaheKP\",\"Name\":\"x\"}]}"));
        assertNull(BootstrapPeers.hashOf("{\"peers\":[{\"Hash\":\"../../etc\"}]}"));
        assertNull(BootstrapPeers.hashOf("{\"peers\":[]}"));
        assertNull(BootstrapPeers.hashOf("not json"));
    }
}
