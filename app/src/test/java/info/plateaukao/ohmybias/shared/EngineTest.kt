package info.plateaukao.ohmybias.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/// 測試環境 — 首次引用時建 temp sharedDir 並複製資料檔（phrases.bin/t2s/s2t）。
/// 單例（WikiCorpus.shared 等）首次存取即載入，故所有測試類別開頭需先 touch TestEnv。
object TestEnv {
    init {
        val dir = File(System.getProperty("java.io.tmpdir"), "ohmybias_test_shared_${UUID.randomUUID()}")
        dir.mkdirs()
        AppEnv.sharedDir = dir.path
        AppEnv.ensureDirs()
        // gradle 單元測試 working dir = app/ 模組目錄
        val assets = File("src/main/assets")
        for (name in listOf("phrases.bin", "t2s.json", "s2t.json", "zhuyin_data.bin", "pinyin_data.bin", "char_freq.bin")) {
            val src = File(assets, name)
            if (src.exists()) src.copyTo(File(dir, name), overwrite = true)
        }
    }

    fun touch() {}
}

// === Test doubles ===

class MockPrefs(
    override var suggestEnabled: Boolean = true,
    override var autoCommit: Boolean = false,
    override var overflowAutoCommit: Boolean = false,
    override var fuzzyMatch: Boolean = false,
    override var showCodeHint: Boolean = false,
    override var suggestStrategy: String = "general",
    override var wordCorpus: String = "moedict",
    override var charSuggest: Boolean = false,
    override var regionVariant: String = "tw",
    override var punctuationPairing: Boolean = true,
    override var homophoneMultiReading: Boolean = false,
) : IMEPreferences {
    override fun domainEnabled(key: String) = true
    override fun domainPriority(key: String) = 0
}

class MockEngineDelegate : InputEngineDelegate {
    val composingUpdates = mutableListOf<String>()
    val candidateUpdates = mutableListOf<List<String>>()
    val commits = mutableListOf<String>()
    val commitPairs = mutableListOf<Pair<String, String>>()
    var clearCount = 0
    val toasts = mutableListOf<String>()
    var deleteBackCount = 0
    val suggestions = mutableListOf<List<String>>()
    val pastedTexts = mutableListOf<String>()
    val codeHints = mutableListOf<String>()

    override fun engineDidUpdateComposing(text: String) { composingUpdates.add(text) }
    override fun engineDidUpdateCandidates(candidates: List<String>) { candidateUpdates.add(candidates) }
    override fun engineDidCommit(text: String) { commits.add(text) }
    override fun engineDidCommitPair(left: String, right: String) { commitPairs.add(left to right) }
    override fun engineDidClearComposing() { clearCount += 1 }
    override fun engineDidShowToast(text: String) { toasts.add(text) }
    override fun engineDidShowCodeHint(text: String, duration: Double) { codeHints.add(text) }
    override fun engineDidDeleteBack() { deleteBackCount += 1 }
    override fun engineDidSuggest(suggestions: List<String>) { this.suggestions.add(suggestions) }
    override fun engineDidPasteText(text: String) { pastedTexts.add(text) }
}

// === Fixtures ===

const val FIXTURE_CIN = """%gen_inp
%ename Test
%cname 測試表
%selkey 1234567890
%chardef begin
a 日
aa 昌
ab 明
b 月
ba 朋
hj 手
hj 乎
zb 「
zzzz 龘
%chardef end
"""

fun makeFixtureTable(): CINTable {
    val path = File(System.getProperty("java.io.tmpdir"), "ohmybias_test_${UUID.randomUUID()}.cin")
    path.writeText(FIXTURE_CIN, Charsets.UTF_8)
    val table = CINTable()
    table.load(path.path)
    path.delete()
    return table
}

fun makeEngine(prefs: MockPrefs = MockPrefs(), pinned: PinnedOrder? = null): Pair<InputEngine, MockEngineDelegate> {
    val engine = InputEngine(
        cinTable = makeFixtureTable(),
        pinnedOrder = pinned ?: PinnedOrder(File(AppEnv.sharedDir, "pinned_${UUID.randomUUID()}.txt").path),
        suggestionEngine = SuggestionEngine(prefs = prefs),
        prefs = prefs,
    )
    val mock = MockEngineDelegate()
    engine.delegate = mock
    return engine to mock
}

// === Tests ===

class CINTableTest {
    init { TestEnv.touch() }

    @Test
    fun cinCompileRoundtrip() {
        val table = makeFixtureTable()
        assertFalse("fixture table loads", table.isEmpty)
        assertEquals("cname parsed", "測試表", table.cinName)
        assertEquals("single code lookup", listOf("日"), table.lookup("a"))
        assertEquals("two-key code lookup", listOf("明"), table.lookup("ab"))
        assertEquals("multi-candidate code", setOf("手", "乎"), table.lookup("hj").toSet())
        assertTrue("hasPrefix a", table.hasPrefix("a"))
        assertFalse("no prefix q", table.hasPrefix("q"))
        assertEquals("valid next keys after a", setOf('a', 'b'), table.validNextKeys("a"))
        assertEquals("wildcard a*", setOf("昌", "明"), table.wildcardLookup("a*").toSet())
        assertTrue("reverse lookup", table.reverseLookup("明").contains("ab"))
    }
}

class InputEngineTest {
    init { TestEnv.touch() }

    @Test
    fun composeAndCommit() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")
        assertEquals("composing after letter", "a", engine.composing)
        assertEquals("candidate 日 shown", "日", mock.candidateUpdates.last().firstOrNull())
        engine.handleSpace()
        assertEquals("space commits first candidate", "日", mock.commits.last())
        assertEquals("composing cleared after commit", "", engine.composing)
    }

    @Test
    fun pinnedOrder() {
        // 檔案格式：一行一碼、tab 分欄；非 BMP 字原樣一欄
        val parsed = PinnedOrder.parse("hj\t乎\t手\nab\t𠮷\t明\n\n")
        assertEquals(listOf("乎", "手"), parsed["hj"])
        assertEquals(listOf("𠮷", "明"), parsed["ab"])
        assertEquals(parsed, PinnedOrder.parse(PinnedOrder.serialize(parsed)))

        // 沒有檔案 → 內建預設 hj → 手乎；apply 只重排、不增減
        val path = File(AppEnv.sharedDir, "pinned_${UUID.randomUUID()}.txt").path
        val store = PinnedOrder(path)
        assertEquals(listOf("手", "乎", "x"), store.apply(listOf("乎", "手", "x"), "hj"))
        assertEquals(listOf("a", "b"), store.apply(listOf("a", "b"), "zz"))

        // 引擎：,,PIN → 打 hj → 選 乎 → 空白確認 → 再打 hj 時 乎 排第一；,,UNPINhj 後回字表原序
        val (engine, _) = makeEngine(pinned = store)
        for (c in listOf(",", ",", "p", "i", "n")) engine.handleLetter(c)
        engine.handleSpace()
        engine.handleLetter("h"); engine.handleLetter("j")
        val idx = engine.currentCandidates.indexOf("乎")
        assertTrue("hj shows 乎 in pin mode", idx >= 0)
        engine.selectCandidate(idx)
        engine.handleSpace()
        engine.handleLetter("h"); engine.handleLetter("j")
        assertEquals("乎", engine.currentCandidates.first())
        engine.handleEscape()
        assertEquals(listOf("乎"), PinnedOrder(path).chars("hj"))
        for (c in listOf(",", ",", "u", "n", "p", "i", "n", "h", "j")) engine.handleLetter(c)
        engine.handleSpace()
        engine.handleLetter("h"); engine.handleLetter("j")
        assertEquals(engine.cinTable.lookup("hj"), engine.currentCandidates)
        assertEquals(null, PinnedOrder(path).chars("hj"))
    }

    @Test
    fun commitComposingRaw() {
        // 有候選但使用者要英文單字：點組字碼原樣上屏（不帶尾隨空格）
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")
        assertTrue("有候選", engine.currentCandidates.isNotEmpty())
        engine.commitComposingRaw()
        assertEquals("原樣送出字母", "a", mock.commits.last())
        assertEquals("送出後清空 composing", "", engine.composing)
        assertTrue("送出後清空候選", engine.currentCandidates.isEmpty())
        // composing 空時無動作
        val commitCount = mock.commits.size
        engine.commitComposingRaw()
        assertEquals("composing 空時無動作", commitCount, mock.commits.size)
    }

    @Test
    fun englishPassthrough() {
        val (engine, mock) = makeEngine()
        // fixture 表 maxCodeLength=4（CINTable 下限）— "hello" 無候選且超長，應續收不清除
        for (ch in "hello") engine.handleLetter(ch.toString())
        assertEquals("無候選時續打不清除", "hello", engine.composing)
        assertTrue("英文直通中無候選", engine.currentCandidates.isEmpty())
        engine.handleSpace()
        assertEquals("空白鍵原樣送出字串＋尾隨空格", "hello ", mock.commits.last())
        assertEquals("送出後清空 composing", "", engine.composing)
        // 送出後 composing 已空，再次 handleSpace 不應動作（service 層會直接輸出空白）
        val commitCount = mock.commits.size
        engine.handleSpace()
        assertEquals("composing 空時 handleSpace 無動作", commitCount, mock.commits.size)
    }

    @Test
    fun overflowAutoCommit() {
        // 預設關：滿碼有候選（zzzz=龘）再打第五鍵不頂字 — 續打成 raw、空白原樣送出
        // （weekly 情境：前四碼恰為有效字根的英文字也要能直通）
        val (engine, mock) = makeEngine()
        for (ch in "zzzzz") engine.handleLetter(ch.toString())
        assertEquals("滿碼有候選仍續打不頂字", "zzzzz", engine.composing)
        assertTrue("超長字串無候選", engine.currentCandidates.isEmpty())
        engine.handleSpace()
        assertEquals("空白鍵原樣送出＋尾隨空格", "zzzzz ", mock.commits.last())

        // 開啟頂字上屏：滿碼再打一鍵送出首選、開始下一字
        val (engine2, mock2) = makeEngine(MockPrefs(overflowAutoCommit = true))
        for (ch in "zzzzz") engine2.handleLetter(ch.toString())
        assertEquals("頂字上屏送出 zzzz 首選", "龘", mock2.commits.last())
        assertEquals("頂字後以新鍵開始下一字", "z", engine2.composing)
    }

    @Test
    fun backspaceAndEscape() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")
        engine.handleLetter("b")
        assertEquals("composing ab", "ab", engine.composing)
        engine.handleBackspace()
        assertEquals("backspace drops last key", "a", engine.composing)
        engine.handleEscape()
        assertEquals("escape clears composing", "", engine.composing)
        assertTrue("nothing committed", mock.commits.isEmpty())
    }

    @Test
    fun vrsfQuickSelect() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("h")
        engine.handleLetter("j")
        assertTrue("hj has two candidates", engine.currentCandidates.size >= 2)
        val second = engine.currentCandidates[1]
        assertTrue("VRSF v selects 2nd candidate", engine.handleVRSF("v"))
        assertEquals("v committed second candidate", second, mock.commits.last())
    }

    @Test
    fun punctuationPairing() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("z")
        engine.handleLetter("b")
        engine.handleSpace()
        assertEquals("paired punctuation committed as pair", 1, mock.commitPairs.size)
        assertEquals("「」 pair", "「" to "」", mock.commitPairs.last())
    }

    /// 符號鍵不經組字流程，改問 pairedRight —— 僅中文成對標點配對，半形括號/引號不配
    @Test
    fun pairedRightForSymbolKeys() {
        val (engine, _) = makeEngine()
        assertEquals("（ pairs", "）", engine.pairedRight("（"))
        assertEquals("【 pairs", "】", engine.pairedRight("【"))
        assertEquals("half-width ( does not pair", null, engine.pairedRight("("))
        assertEquals("half-width [ does not pair", null, engine.pairedRight("["))
        assertEquals("half-width { does not pair", null, engine.pairedRight("{"))
        assertEquals("double quote does not pair", null, engine.pairedRight("\""))
        assertEquals("single quote does not pair", null, engine.pairedRight("'"))
        assertEquals("right half does not pair", null, engine.pairedRight("）"))
        assertEquals("multi-char does not pair", null, engine.pairedRight("（）"))
    }

    @Test
    fun pairedRightRespectsPreference() {
        val (engine, _) = makeEngine(MockPrefs(punctuationPairing = false))
        assertEquals("pairing off → no right half", null, engine.pairedRight("（"))
    }

    @Test
    fun commaCommandUnknown() {
        val (engine, mock) = makeEngine()
        engine.handleLetter(",")
        engine.handleLetter(",")
        engine.handleLetter("q")
        engine.handleLetter("q")
        engine.handleSpace()
        assertTrue("unknown ,, command toast", mock.toasts.last().contains("未知命令"))
    }

    @Test
    fun modeSwitch() {
        val (engine, mock) = makeEngine()
        engine.handleLetter(",")
        engine.handleLetter(",")
        engine.handleLetter("s")
        engine.handleSpace()
        assertEquals(",,S switches to 簡中", InputEngine.InputMode.S, engine.inputMode)
        assertEquals("mode toast", "簡中", mock.toasts.last())
        engine.switchToMode("t")
        assertEquals("switchToMode back to 繁中", InputEngine.InputMode.T, engine.inputMode)
    }

    @Test
    fun setEnglishMode() {
        val (engine, mock) = makeEngine()
        assertFalse("初始為中文模式", engine.isEnglishMode)
        engine.setEnglishMode(true)
        assertTrue("setEnglishMode(true) 進入英文模式", engine.isEnglishMode)
        assertTrue("還原模式不顯示 toast", mock.toasts.isEmpty())
        engine.setEnglishMode(true)
        assertTrue("重複設定為冪等", engine.isEnglishMode)
        engine.setEnglishMode(false)
        assertFalse("setEnglishMode(false) 回中文模式", engine.isEnglishMode)
        engine.toggleEnglishMode()
        assertTrue("toggle 後為英文", engine.isEnglishMode)
        assertTrue("toggle 也不顯示 toast（工具列鍵已反映狀態）", mock.toasts.isEmpty())
    }
}

class SkinSettingsTest {
    init { TestEnv.touch() }

    @Test
    fun parseSettings() {
        val json = """
        {"skinInfo": {"name": "蝦米輸入法", "author": "Ryan"},
         "toolbar": {"toolbarButtons": [1, 3, 9, 7, 16, 17, 8, 10, 13, 2]},
         "layout": {"keyboardLayout": "row", "spaceKeyLayout": "2", "longPressLayout": "1"},
         "swipe": {"globalEnabledFeatures": ["swipeUp", "longPress"]},
         "globalSettings": {"palette": {"light": {"bg": "#FFFFFFFF", "borderSize": 2},
                                        "dark": {"bg": "#000000FF"}},
                            "groups": {"lowercaseSize": 25}}}
        """.trimIndent()
        val skin = SkinSettings.shared
        skin.apply(json)
        assertEquals("skinInfo.name", "蝦米輸入法", skin.skinName)
        assertEquals("toolbarButtons", listOf(1, 3, 9, 7, 16, 17, 8, 10, 13, 2), skin.toolbarButtons)
        assertEquals("keyboardLayout", "row", skin.keyboardLayout)
        assertEquals("spaceKeyLayout", "2", skin.spaceKeyLayout)
        assertEquals("longPressLayout", "1", skin.longPressLayout)
        assertTrue("swipe 全域開關", skin.swipeUpEnabled && !skin.swipeDownEnabled)
        assertTrue("longPress 開、角標關", skin.longPressEnabled && !skin.showSwipeUpText)
        assertEquals("light palette", "#FFFFFFFF", skin.colorHex("bg", dark = false))
        assertEquals("dark palette", "#000000FF", skin.colorHex("bg", dark = true))
        assertEquals("palette 數值", 2.0, skin.paletteNumber("borderSize", dark = false)!!, 0.0001)
        assertEquals("字級 groups", 25.0, skin.fontSize("lowercaseSize", 23.0), 0.0001)
        assertEquals("字級 fallback", 16.0, skin.fontSize("systemSize", 16.0), 0.0001)
        skin.reload()  // 還原預設，避免影響其他測試
        assertEquals("reload 還原內建預設", SkinSettings.defaultToolbarButtons, skin.toolbarButtons)
    }

    @Test
    fun parseFlatSchema() {
        // 新版 cskin 匯出器的扁平 schema（toolbarButtons/palette/groups 在頂層、滑動開關為布林）
        val json = """
        {"skinInfo": {"name": "蝦米輸入法", "author": "Ryan"},
         "spaceKeyLayout": "1",
         "handedness": "left",
         "enableSwipeUpActions": true, "enableSwipeDownActions": false,
         "enableLongPressActions": true, "showSwipeUpText": false, "showSwipeDownText": true,
         "toolbarButtons": [1, 3, 7, 0, 10, 5, 6, 0, 8, 2],
         "enableCustomColors": true,
         "palette": {"light": {"bg": "#D0D3DA01", "keySystem": "#979faf80"},
                     "dark": {"bg": "#000000"}},
         "groups": {"lowercaseSize": 17, "systemSize": 14}}
        """.trimIndent()
        val skin = SkinSettings.shared
        skin.apply(json)
        assertEquals("扁平 toolbarButtons", listOf(1, 3, 7, 0, 10, 5, 6, 0, 8, 2), skin.toolbarButtons)
        assertEquals("扁平 spaceKeyLayout", "1", skin.spaceKeyLayout)
        assertTrue("swipeUp on", skin.swipeUpEnabled)
        assertFalse("swipeDown off", skin.swipeDownEnabled)
        assertTrue("longPress on", skin.longPressEnabled)
        assertFalse("上滑角標關", skin.showSwipeUpText)
        assertTrue("下滑角標開", skin.showSwipeDownText)
        assertEquals("扁平 palette light", "#979faf80", skin.colorHex("keySystem", dark = false))
        assertEquals("扁平 palette dark", "#000000", skin.colorHex("bg", dark = true))
        assertEquals("扁平 groups", 17.0, skin.fontSize("lowercaseSize", 23.0), 0.0001)
        skin.reload()
    }
}

class WikiCorpusTest {
    init { TestEnv.touch() }

    @Test
    fun phrasesLoaded() {
        val corpus = WikiCorpus.shared
        assertEquals("phrases.bin loaded", 1, corpus.domainBinCount)
        val after = corpus.suggestDomainTerms("日", 5)
        assertTrue("single-char 日 has phrase suggestions", after.isNotEmpty())
        assertTrue("single-char suggestions are remainders", after.all { !it.startsWith("日") })
        val comp = corpus.phraseCompletions("明天", 3)
        // 明天 可能無更長詞 — 只驗證回傳為餘字形式
        assertTrue("completions are remainders", comp.all { !it.startsWith("明天") })
        val wc = corpus.suggestWordCorpus("臺灣", 5)
        assertTrue("臺灣 has word-corpus completions", wc.isNotEmpty())
    }
}

class ZhuyinLookupTest {
    init { TestEnv.touch() }

    @Test
    fun binaryLookups() {
        val zl = ZhuyinLookup()
        val ba = zl.charsForZhuyin("ㄅㄚ")
        assertTrue("ㄅㄚ has homophones", ba.isNotEmpty())
        assertTrue("ㄅㄚ contains 八", "八" in ba)
        val sorted = zl.sortByFreq(ba)
        assertEquals("freq sort keeps size", ba.size, sorted.size)
        val readings = zl.lookup("日")
        assertTrue("日 has readings with homophones", readings.isNotEmpty())
        assertTrue("日 homophones exclude self", readings.all { "日" !in it.chars })
        val ba1 = zl.charsForPinyin("ba1")
        assertTrue("pinyin ba1 non-empty", ba1.isNotEmpty())
        assertTrue("ba1 contains 八", "八" in ba1)
    }
}

class SuggestionTest {
    init { TestEnv.touch() }

    @Test
    fun suggestionEngineBasic() {
        val se = SuggestionEngine(prefs = MockPrefs())
        val s = se.suggest("日", "日")
        assertTrue("suggestions after 日", s.isNotEmpty())
        assertTrue("at most 10 suggestions", s.size <= 10)
        val skip = se.suggest("的", "的")
        assertTrue("skip char 的 yields no word suggestions", skip.isEmpty())
    }

    @Test
    fun engineSuggestFlow() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")   // 日
        engine.handleSpace()
        assertEquals("committed 日", "日", mock.commits.last())
        assertTrue("engineDidSuggest fired after commit", mock.suggestions.isNotEmpty())
        assertTrue("suggestions non-empty", mock.suggestions.last().isNotEmpty())
    }

    @Test
    fun suggestDisabled() {
        val prefs = MockPrefs(suggestEnabled = false)
        val (engine, mock) = makeEngine(prefs)
        engine.handleLetter("a")
        engine.handleSpace()
        assertTrue("no suggestions when disabled", mock.suggestions.isEmpty())
    }
}
