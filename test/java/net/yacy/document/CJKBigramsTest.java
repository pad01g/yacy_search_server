package net.yacy.document;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class CJKBigramsTest {

    @Test
    public void testIsCJK() {
        assertTrue(CJKBigrams.isCJK('暗'));
        assertTrue(CJKBigrams.isCJK('の'));
        assertTrue(CJKBigrams.isCJK('ス'));
        assertTrue(CJKBigrams.isCJK('ー'));
        assertTrue(CJKBigrams.isCJK('한'));
        assertFalse(CJKBigrams.isCJK('a'));
        assertFalse(CJKBigrams.isCJK('4'));
        assertFalse(CJKBigrams.isCJK('。'));
    }

    @Test
    public void testSplitKeepsNonCJKTokens() {
        assertEquals(Arrays.asList("bundler"), CJKBigrams.split("bundler"));
        assertEquals(Arrays.asList("erc-4337"), CJKBigrams.split("erc-4337"));
    }

    @Test
    public void testSplitCJKRunIntoBigrams() {
        assertEquals(Arrays.asList("暗号", "号資", "資産"), CJKBigrams.split("暗号資産"));
        assertEquals(Arrays.asList("登録"), CJKBigrams.split("登録"));
        assertEquals(Arrays.asList("東"), CJKBigrams.split("東"));
        assertEquals(Arrays.asList("ステ", "テー", "ーブ", "ブル"), CJKBigrams.split("ステーブル"));
    }

    @Test
    public void testSplitMixedToken() {
        assertEquals(Arrays.asList("python", "で学", "学ぶ"), CJKBigrams.split("pythonで学ぶ"));
        assertEquals(Arrays.asList("web3", "財布"), CJKBigrams.split("web3財布"));
    }

    @Test
    public void testPositionsOfOverlappingBigrams() {
        final WordTokenizer.Positions p = new WordTokenizer.Positions();
        // "暗号資産 金融": 暗号 0, 号資 1, 資産 2, 金融 5 (character offsets)
        assertEquals(0, p.next("暗号"));
        assertEquals(1, p.next("号資"));
        assertEquals(2, p.next("資産"));
        assertEquals(5, p.next("金融"));
        final WordTokenizer.Positions q = new WordTokenizer.Positions();
        assertEquals(0, q.next("hello"));
        assertEquals(6, q.next("world"));
    }

    /**
     * A Japanese clause without spaces used to become a single word, so a query for a part of it never matched.
     */
    @Test
    public void testWordTokenizerSplitsJapaneseClause() {
        final List<String> words = new ArrayList<>();
        final WordTokenizer wt = new WordTokenizer(new SentenceReader("暗号資産交換業者の登録について。金融庁 FSA"), null);
        try {
            while (wt.hasMoreElements()) words.add(wt.nextElement().toString());
        } finally {
            wt.close();
        }
        assertTrue(words.contains("暗号"));
        assertTrue(words.contains("交換"));
        assertTrue(words.contains("登録"));
        assertTrue(words.contains("金融"));
        assertTrue(words.contains("融庁"));
        assertTrue(words.contains("FSA"));
        assertFalse(words.contains("暗号資産交換業者の登録について"));
    }
}
