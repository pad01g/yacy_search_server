package net.yacy.search.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import net.yacy.document.WordTokenizer;

public class QueryGoalCJKTest {

    private static List<String> list(final Iterator<String> i) {
        final List<String> l = new ArrayList<>();
        while (i.hasNext()) l.add(i.next());
        return l;
    }

    @Test
    public void testCJKWordsAreSplitLikeTheIndex() {
        final QueryGoal goal = new QueryGoal("暗号資産交換業 登録 金融庁");
        final Set<String> words = goal.getIncludeWordsSet();
        assertTrue(words.contains("暗号"));
        assertTrue(words.contains("換業"));
        assertTrue(words.contains("登録"));
        assertTrue(words.contains("金融"));
        assertTrue(words.contains("融庁"));
        assertFalse(words.contains("暗号資産交換業"));
        // every query word is a word the tokenizer produces for a document containing the terms
        final Set<String> docWords = WordTokenizer.tokenizeSentence("暗号資産交換業者の登録は金融庁が行う", 100).keySet();
        assertTrue(docWords.containsAll(words));
    }

    @Test
    public void testIncludeStringsStayUnsplit() {
        final QueryGoal goal = new QueryGoal("暗号資産交換業 登録 金融庁");
        assertEquals(List.of("暗号資産交換業", "登録", "金融庁"), list(goal.getIncludeStrings()));
        assertEquals(3, goal.getIncludeStringsSize());
        assertTrue(goal.getIncludeSize() > 3);
        assertTrue(goal.containsCJK());
    }

    @Test
    public void testLatinQueryIsUnchanged() {
        final QueryGoal goal = new QueryGoal("ERC-4337 bundler");
        assertEquals(2, goal.getIncludeStringsSize());
        assertEquals(2, goal.getIncludeSize());
        assertFalse(goal.containsCJK());
    }

    @Test
    public void testMinimumMatch() {
        // without a running Switchboard the defaults apply
        assertEquals("2<-1 5<80%", QueryParams.minimumMatch(new QueryGoal("tokio select cancellation safety")));
        assertEquals("2<-1 5<80%", QueryParams.minimumMatch(new QueryGoal("ステーブルコイン 規制")));
    }
}
