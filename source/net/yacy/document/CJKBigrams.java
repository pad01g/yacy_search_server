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
     * Split a token into its non-CJK parts, which are kept as they are, and the
     * overlapping bigrams of its CJK runs. A CJK run of a single character is kept
     * as a single character token.
     *
     * @param token a token without white space
     * @return the parts in their original order; the token itself if it has no CJK characters
     */
    public static List<String> split(final String token) {
        final List<String> parts = new ArrayList<>();
        if (!containsCJK(token)) {
            parts.add(token);
            return parts;
        }
        int i = 0;
        final int n = token.length();
        while (i < n) {
            final boolean cjk = isCJK(token.charAt(i));
            int j = i + 1;
            while (j < n && isCJK(token.charAt(j)) == cjk) j++;
            if (!cjk) {
                parts.add(token.substring(i, j));
            } else if (j - i == 1) {
                parts.add(token.substring(i, j));
            } else {
                for (int k = i; k < j - 1; k++) parts.add(token.substring(k, k + 2));
            }
            i = j;
        }
        return parts;
    }
}
