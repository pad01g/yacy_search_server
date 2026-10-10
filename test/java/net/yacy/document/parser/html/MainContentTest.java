package net.yacy.document.parser.html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.util.HashSet;

import org.junit.After;
import org.junit.Test;

import net.yacy.cora.document.id.AnchorURL;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.document.VocabularyScraper;
import net.yacy.kelondro.util.FileUtils;

public class MainContentTest {

    private static final String LONG = "The main text explains how ownership works, with enough words to count as the main text of the page. ";

    private static ContentScraper scrape(final String page) throws IOException {
        final ContentScraper scraper = new ContentScraper(new DigestURL("http://test.org/doc/page.html"), 100, 100,
                new HashSet<String>(), TagValency.EVAL, new VocabularyScraper(), 0);
        final Writer writer = new TransformerWriter(null, null, scraper, false);
        FileUtils.copy(new StringReader(page), writer);
        writer.close();
        return scraper;
    }

    @After
    public void restore() {
        ContentScraper.extractMainContent = true;
    }

    @Test
    public void testMainElementWins() throws IOException {
        final ContentScraper s = scrape("<html><body><header><a href='/'>Site name</a> Search the docs</header>"
                + "<nav><ul><li><a href='/a.html'>Chapter one</a><li><a href='/b.html'>Chapter two</a></ul></nav>"
                + "<main><article><header><h1>Ownership</h1></header><p>" + LONG + "</p></article></main>"
                + "<aside>Related: borrowing</aside><footer>Copyright 2026 Example</footer></body></html>");
        final String text = s.getText();
        assertTrue(text, text.contains("Ownership")); // the article header stays
        assertTrue(text, text.contains("ownership works"));
        assertFalse(text, text.contains("Chapter one"));
        assertFalse(text, text.contains("Site name"));
        assertFalse(text, text.contains("Copyright"));
        assertFalse(text, text.contains("borrowing"));
        // the links in the navigation are still followed
        assertTrue(s.getAnchors().stream().map(u -> u.toNormalform(true)).anyMatch(u -> u.endsWith("/a.html")));
        assertTrue(s.getAnchors().stream().map(u -> u.toNormalform(true)).anyMatch(u -> u.endsWith("/b.html")));
        s.close();
    }

    @Test
    public void testRolesAndClasses() throws IOException {
        // Sphinx: role=main on a div, the sidebar is a div with role=navigation; DocBook: navheader/navfooter
        final ContentScraper s = scrape("<html><body><div class='related' role='navigation'>previous | next</div>"
                + "<div class='navheader'>Prev Up Next</div><div class='body' role='main'><p>" + LONG + "</p></div>"
                + "<div class='sphinxsidebar' role='navigation'>Table of contents</div></body></html>");
        final String text = s.getText();
        assertTrue(text, text.contains("ownership works"));
        assertFalse(text, text.contains("previous"));
        assertFalse(text, text.contains("Prev Up"));
        assertFalse(text, text.contains("Table of contents"));
        s.close();
    }

    @Test
    public void testWithoutMainOnlyFurnitureIsRemoved() throws IOException {
        final ContentScraper s = scrape("<html><body><nav>Home Docs Blog</nav><div><p>" + LONG + "</p></div>"
                + "<div><p>A second section outside of any article.</p></div><footer>Imprint</footer></body></html>");
        final String text = s.getText();
        assertTrue(text, text.contains("ownership works"));
        assertTrue(text, text.contains("second section"));
        assertFalse(text, text.contains("Home Docs"));
        assertFalse(text, text.contains("Imprint"));
        s.close();
    }

    @Test
    public void testSmallMainAndUnclosedRegions() throws IOException {
        // a tiny <main> (a search box) does not replace the page text
        ContentScraper s = scrape("<html><body><main>Search</main><div><p>" + LONG + LONG + "</p></div></body></html>");
        assertTrue(s.getText().contains("ownership works"));
        s.close();
        // an unclosed <li> inside <nav> does not turn the rest of the page into navigation
        s = scrape("<html><body><nav><ul><li>One<li>Two</ul></nav><p>" + LONG + "</p></body></html>");
        assertTrue(s.getText(), s.getText().contains("ownership works"));
        assertFalse(s.getText(), s.getText().contains("Two"));
        s.close();
        // a page that is all furniture keeps its text
        s = scrape("<html><body><nav>" + LONG + "</nav></body></html>");
        assertTrue(s.getText().contains("ownership works"));
        s.close();
    }

    @Test
    public void testSwitchedOff() throws IOException {
        ContentScraper.extractMainContent = false;
        final ContentScraper s = scrape("<html><body><nav>Home Docs Blog</nav><main><p>" + LONG + "</p></main></body></html>");
        assertTrue(s.getText().contains("Home Docs"));
        assertEquals(true, s.getText().contains("ownership works"));
        s.close();
    }

    @Test
    public void testEndTagInScriptDoesNotCloseThePage() throws IOException {
        // a template or a string in a script holds end tags: they must not close the elements around the script
        final ContentScraper s = scrape("<html><body><nav>Menu entries</nav><main><div id='content'><p>" + LONG + "</p>"
                + "<script>el.innerHTML = '<b>x</b></div></main>'; foo();</script>"
                + "<script type='text/template'><div class='card'></div></div></script>"
                + "<p>The second paragraph of the main text continues after the scripts.</p></div></main>"
                + "<footer>Copyright 2026 Example</footer></body></html>");
        final String text = s.getText();
        assertTrue(text, text.contains("ownership works"));
        assertTrue(text, text.contains("second paragraph"));
        assertFalse(text, text.contains("foo()"));
        assertFalse(text, text.contains("Copyright"));
        assertFalse(text, text.contains("Menu entries"));
        s.close();
    }

    @Test
    public void testAsideInsideTheArticleIsText() throws IOException {
        final ContentScraper s = scrape("<html><body><aside>Sidebar links</aside><main><article><p>" + LONG + "</p>"
                + "<aside class='note'>Note: borrowing rules apply here.</aside></article>"
                + "<aside>Related posts beside the article</aside></main></body></html>");
        final String text = s.getText();
        assertTrue(text, text.contains("borrowing rules"));
        assertFalse(text, text.contains("Sidebar links"));
        assertFalse(text, text.contains("Related posts"));
        s.close();
    }

    @Test
    public void testDeepUnclosedElementsStayBounded() throws IOException {
        final StringBuilder page = new StringBuilder("<html><body><div>");
        for (int i = 0; i < 5000; i++) page.append("<span>");
        page.append(LONG);
        for (int i = 0; i < 5000; i++) page.append("</i>");
        page.append("</div></body></html>");
        final long t = System.currentTimeMillis();
        final ContentScraper s = scrape(page.toString());
        assertTrue(s.getText().contains("ownership works"));
        assertTrue("took " + (System.currentTimeMillis() - t) + " ms", System.currentTimeMillis() - t < 10000);
        s.close();
    }
}
