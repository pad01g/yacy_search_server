package net.yacy.search.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SegmentLanguageTest {

    /** the start of docs.python.org/ja/3/library/dataclasses.html: long code samples, 8.5% Japanese letters */
    private static final String JA_WITH_CODE = "dataclasses --- データクラス ソースコード: Lib/dataclasses.py "
            + "このモジュールは、 __init__() や __repr__() のような 特殊メソッド を生成します。 "
            + "from dataclasses import dataclass @dataclass class InventoryItem: name: str unit_price: float quantity_on_hand: int = 0 "
            + "def total_cost(self) -> float: return self.unit_price * self.quantity_on_hand "
            + "def __init__(self, name: str, unit_price: float, quantity_on_hand: int = 0): self.name = name "
            + "self.unit_price = unit_price self.quantity_on_hand = quantity_on_hand";

    @Test
    public void declaredJapaneseWithCodeIsJapanese() {
        final double share = Segment.scriptShare(JA_WITH_CODE, "ja");
        assertTrue(share >= Segment.DECLARED_SCRIPT_SHARE);
        assertTrue(share < Segment.UNDECLARED_SCRIPT_SHARE);
    }

    @Test
    public void englishPageWithLanguageMenuIsNot() {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) text.append("Coroutines and tasks. This section outlines high-level asyncio APIs to work with coroutines. ");
        text.append("English 日本語 Français Deutsch 한국어");
        assertTrue(Segment.scriptShare(text.toString(), "ja") < Segment.DECLARED_SCRIPT_SHARE);
        assertTrue(Segment.scriptShare(text.toString(), "kana") < Segment.UNDECLARED_SCRIPT_SHARE);
        assertTrue(Segment.scriptShare(text.toString(), "ko") < Segment.UNDECLARED_SCRIPT_SHARE);
    }

    @Test
    public void japaneseProseIsKana() {
        // docs.docker.jp declares lang="en" on its translated pages
        assertTrue(Segment.scriptShare("ボリュームは、Docker コンテナが生成して使うデータを保存するための仕組みです。", "kana") >= Segment.UNDECLARED_SCRIPT_SHARE);
        // Chinese has no kana
        assertEquals(0d, Segment.scriptShare("卷是保存容器数据的首选机制", "kana"), 0d);
    }

    @Test
    public void scriptMustMatchTheLanguage() {
        assertEquals(0d, Segment.scriptShare("この節では", "ko"), 0d);
        assertEquals(1d, Segment.scriptShare("한국어 문서", "ko"), 0d);
        assertEquals(1d, Segment.scriptShare("中文文档", "zh"), 0d);
        assertEquals(0d, Segment.scriptShare("plain text", "en"), 0d);
        assertEquals(0d, Segment.scriptShare(null, "ja"), 0d);
    }
}
