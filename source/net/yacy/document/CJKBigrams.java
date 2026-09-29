/**
 *  CJKBigrams
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.document;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits runs of Chinese, Japanese and Korean characters into overlapping bigrams.
 *
 * CJK text has no spaces between words, so the word tokenizer used to produce one
 * "word" per run of characters between punctuation, e.g. the whole clause
 * "暗号資産交換業者の登録". Such a word never has the same hash as a query word,
 * so the RWI index could not find CJK text. Overlapping bigrams are the same
 * representation that Lucene's CJKBigramFilter produces for the Solr index:
 * "暗号資産" becomes "暗号", "号資", "資産". A query run is split the same way,
 * and the RWI join then requires all of its bigrams.
 */
public final class CJKBigrams {

    private CJKBigrams() {
    }

    /**
     * @return true for Han, Hiragana, Katakana and Hangul characters, including
     *         the iteration mark and the Katakana prolonged sound mark, which belong to
     *         the COMMON script
     */
    public static boolean isCJK(final char c) {
        if (c < 0x1100) return false; // fast path for Latin and most other alphabets
        if (c == '々' || c == 'ー' || c == 'ｰ') return true; // 々, ー, half width ｰ
        final Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(c);
        } catch (final IllegalArgumentException e) {
            return false;
        }
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    public static boolean containsCJK(final CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (isCJK(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * The number of words of a text: runs of letters or digits separated by other characters. Chinese, Japanese and
     * Korean text has no spaces; a run of n CJK characters counts as n / 2 words (rounded up), about the average
     * word length. Counting spaces (as before) gave a whole CJK paragraph the count 1.
     */
    public static int wordCount(final CharSequence text) {
        if (text == null) return 0;
        int words = 0;
        int cjkRun = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (isCJK(c)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                cjkRun++;
                continue;
            }
            if (cjkRun > 0) {
                words += (cjkRun + 1) / 2;
                cjkRun = 0;
            }
            if (Character.isLetterOrDigit(c)) {
                inWord = true;
            } else if (inWord) {
                words++;
                inWord = false;
            }
        }
        if (inWord) words++;
        if (cjkRun > 0) words += (cjkRun + 1) / 2;
        return words;
    }

    /**
     * Split a token into its non-CJK parts, which are kept as they are, and the
     * overlapping bigrams of its CJK runs. A CJK run of a single character is kept
     * as a single character token.
     *
     * Tokens are brought to NFKC (half-width katakana ｶﾀｶﾅ becomes カタカナ, like Solr's CJKWidthFilter). Surrogate
     * pairs (supplementary Han characters, emoji) separate parts, as in the index, where the sentence reader treats
     * them as invisible; parts without letters or digits are dropped. Index and query both split with this method.
     *
     * @param token a token without white space
     * @return the parts in their original order; the token itself if it has no CJK characters
     */
    public static List<String> split(final String rawToken) {
        final List<String> parts = new ArrayList<>();
        if (!containsCJK(rawToken)) {
            parts.add(rawToken);
            return parts;
        }
        final String token = java.text.Normalizer.normalize(rawToken, java.text.Normalizer.Form.NFKC);
        int i = 0;
        final int n = token.length();
        while (i < n) {
            if (Character.isSurrogate(token.charAt(i))) {
                i++;
                continue;
            }
            final boolean cjk = isCJK(token.charAt(i));
            int j = i + 1;
            while (j < n && !Character.isSurrogate(token.charAt(j)) && isCJK(token.charAt(j)) == cjk) j++;
            if (!cjk) {
                final String part = token.substring(i, j);
                if (hasLetterOrDigit(part)) parts.add(part);
            } else if (j - i == 1) {
                parts.add(token.substring(i, j));
            } else {
                for (int k = i; k < j - 1; k++) parts.add(token.substring(k, k + 2));
            }
            i = j;
        }
        return parts;
    }

    private static boolean hasLetterOrDigit(final String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return true;
        return false;
    }
}
