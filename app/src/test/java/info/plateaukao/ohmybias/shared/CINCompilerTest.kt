package info.plateaukao.ohmybias.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/// CINM 編譯／讀取 — 重點在真實字表規模（chars 區遠超過舊 u16 offset 上限）。
class CINCompilerTest {

    init { TestEnv.touch() }

    private fun codeFor(i: Int) = "" + ('a' + i / 676) + ('a' + (i / 26) % 26) + ('a' + i % 26)

    private fun charsFor(i: Int, n: Int) =
        (0 until n).map { j -> String(Character.toChars(0x4E00 + (i * 7 + j) % 20000)) }

    private fun writeCin(entries: Int, charsPer: Int): File {
        val sb = StringBuilder("%cname 測試\n%selkey 1234567890\n%chardef begin\n")
        for (i in 0 until entries) {
            for (c in charsFor(i, charsPer)) sb.append(codeFor(i)).append('\t').append(c).append('\n')
        }
        sb.append("%chardef end\n")
        val f = File.createTempFile("cintest", ".cin")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    /// 真正的嘸蝦米字表 chars 區有數十萬個 code point。val index 的 offset 舊格式只有
    /// u16，超過 65,535 之後整條尾巴都讀到別的字 —— 而且照樣回報「編譯成功」。
    @Test
    fun charOffsetBeyondU16() {
        val entries = 800
        val charsPer = 100  // 80,000 code point，遠超 u16 上限
        val cin = writeCin(entries, charsPer)
        try {
            val table = CINTable()
            table.load(cin.path)
            assertTrue("字表應載入", !table.isEmpty)
            // 頭、中、尾都要對 —— 尾端正是舊格式溢位後讀到錯字的區段
            for (i in listOf(0, 1, entries / 2, entries - 2, entries - 1)) {
                assertEquals("第 $i 筆（碼 ${codeFor(i)}）", charsFor(i, charsPer), table.lookup(codeFor(i)))
            }
        } finally {
            cin.delete()
        }
    }

    /// 小表（offset 存得下 u16）行為不變 —— 保留舊檔／iOS 產出的 .bin 相容性
    @Test
    fun smallTableUnchanged() {
        val cin = writeCin(entries = 20, charsPer = 3)
        try {
            val table = CINTable()
            table.load(cin.path)
            for (i in 0 until 20) {
                assertEquals(charsFor(i, 3), table.lookup(codeFor(i)))
            }
        } finally {
            cin.delete()
        }
    }

    /// 已編好的舊 .bin（格式版本 0）位移只有 u16，升級後仍舊是錯字。使用者不該為了
    /// 拿到修正而重新匯入一次 —— reload() 認出舊格式就該拿留著的 liu.cin 自動重編。
    @Test
    fun staleBinIsRecompiledOnReload() {
        val entries = 800
        val cin = writeCin(entries, charsPer = 100)
        val shared = File(AppEnv.sharedDir)
        val bin = File(shared, "liu.bin")
        val last = entries - 1
        try {
            // 先編一份正常的 .bin，再改造成舊格式：格式版本抹 0，且 val index 每筆的
            // 第 4 byte（位移高位元組）歸零 —— 這正是舊編譯器的產物，尾端字碼會讀錯。
            assertTrue(CINCompiler.compile(cin.path, bin.path) > 0)
            val bytes = bin.readBytes()
            fun u32(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or ((bytes[off + 3].toInt() and 0xFF) shl 24)
            val count = u32(4)
            val valsOff = u32(100)
            for (i in 0 until count) bytes[valsOff + i * 4 + 3] = 0
            for (i in 88..91) bytes[i] = 0
            bin.writeBytes(bytes)

            // 沒有 liu.cin 可重編時就照舊檔用 —— 先確認這份舊檔真的是壞的（否則等於沒測）
            File(AppEnv.cinPath).delete()
            val stale = CINTable()
            stale.reload()
            assertTrue(
                "改造後的舊格式 .bin 尾端本來就該是錯的",
                stale.lookup(codeFor(last)) != charsFor(last, 100),
            )

            // 手上還留著 liu.cin → reload() 應認出舊格式並自動重編
            cin.copyTo(File(AppEnv.cinPath), overwrite = true)
            val fixed = CINTable()
            fixed.reload()
            assertEquals(
                "舊格式 .bin 應被自動重編，尾端字碼才會正確",
                charsFor(last, 100), fixed.lookup(codeFor(last)),
            )
            assertEquals("重編後應標上新版格式", CINCompiler.FORMAT_VERSION, bin.readBytes()[88].toInt())
        } finally {
            cin.delete(); bin.delete(); File(AppEnv.cinPath).delete()
        }
    }

    /// 反查表（查碼提示／注音同音字）走同一條 readChars —— 尾端也不能讀到錯字
    @Test
    fun reverseLookupAcrossOverflowBoundary() {
        val entries = 800
        val cin = writeCin(entries, charsPer = 100)
        try {
            val table = CINTable()
            table.load(cin.path)
            val last = entries - 1
            val lastChar = charsFor(last, 100).first()
            assertTrue("尾端的字要能反查回自己的碼", codeFor(last) in table.reverseLookup(lastChar))
        } finally {
            cin.delete()
        }
    }

    /// 構造符合官方 Windows 嘸蝦米 liu-uni*.tab 格式的合成二進位資料（測試用，不含真實字表）
    private fun buildSyntheticTab(pairs: List<Pair<String, String>>): ByteArray {
        val rootKeys = " abcdefghijklmnopqrstuvwxyz,.'[]"
        fun keyIdx(ch: Char): Int = rootKeys.indexOf(ch).also { require(it >= 0) }

        // 依 (k1, k2) bucket 分組，bucket 索引 i = k1 * 32 + k2（32 <= i < 1024）
        val buckets = Array(1024) { mutableListOf<Pair<String, Int>>() }
        for ((code, ch) in pairs) {
            val k1 = keyIdx(code[0])
            val k2 = if (code.length >= 2) keyIdx(code[1]) else 0
            val bucketIdx = k1 * 32 + k2
            buckets[bucketIdx].add(code to ch.codePointAt(0))
        }

        val ordered = mutableListOf<Pair<String, Int>>()
        val bucketStart = IntArray(1025)
        var running = 0
        for (i in 0 until 1024) {
            bucketStart[i] = running
            if (i in 32 until 1024) {
                ordered.addAll(buckets[i])
                running += buckets[i].size
            }
        }
        bucketStart[1024] = running
        val words = ordered.size
        val s1 = (words * 2 + 7) / 8
        val s2 = (words + 7) / 8
        val i1 = 2050  // 1025 個 uint16 索引表（0..2049）
        val i2 = i1 + s1
        val i3 = i2 + s2
        val i4 = i3 + s2
        val buf = ByteArray(i4 + words * 3)

        fun putU16(off: Int, v: Int) {
            buf[off] = (v and 0xFF).toByte()
            buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        for (i in 32..1024) putU16(i * 2, bucketStart[i])
        // Header metadata 位於 i = 0..3（key1 = 0 的未使用列）
        putU16(0, i1)
        putU16(2, s1)
        putU16(4, words)
        putU16(6, s2)

        for ((ci, item) in ordered.withIndex()) {
            val (code, cp) = item
            val k3 = if (code.length >= 3) keyIdx(code[2]) else 0
            val k4 = if (code.length >= 4) keyIdx(code[3]) else 0
            val hi = (cp ushr 14) and 0x03
            val lo = cp and 0x3FFF
            val hiByteIdx = i1 + (ci * 2) / 8
            val shift = 6 - (ci % 4) * 2
            buf[hiByteIdx] = ((buf[hiByteIdx].toInt() and 0xFF) or (hi shl shift)).toByte()
            val bit24 = (k3 shl 19) or (k4 shl 14) or lo
            val p = i4 + ci * 3
            buf[p] = ((bit24 ushr 16) and 0xFF).toByte()
            buf[p + 1] = ((bit24 ushr 8) and 0xFF).toByte()
            buf[p + 2] = (bit24 and 0xFF).toByte()
        }
        return buf
    }

    /// 測試多個 liu-uni*.tab 同時匯入、解碼 1~4 碼（含標點字根），以及重複 (code, char) 自動去重並保留優先順序
    @Test
    fun mergeMultipleTabsAndDeduplicate() {
        val tab1 = buildSyntheticTab(listOf(
            "a" to "對",
            "a" to "寸",
            "ab" to "明",
            "abc" to "測",
            "abcd" to "試",
            "a,.'" to "標",
        ))
        val tab2 = buildSyntheticTab(listOf(
            "a" to "對",      // 與 tab1 重複，應自動去重
            "a" to "对",      // 新增簡體候選，應排在「對、寸」之後
            "ab" to "明",     // 與 tab1 重複，應自動去重
            "ab" to "盟",     // 新增候選
            "xyz" to "新",    // tab2 獨有字碼
        ))
        val tab3 = buildSyntheticTab(listOf(
            "a" to "寸",      // 與 tab1 重複
            "a" to "对",      // 與 tab2 重複
            "a" to "吋",      // tab3 新增候選
        ))

        assertTrue("應辨識為合法 .tab 檔", CINCompiler.isTabBytes(tab1))
        val cinTmp = File.createTempFile("tabmerge", ".cin")
        try {
            assertTrue(CINCompiler.mergeToCin(listOf(tab1, tab2, tab3), cinTmp.path))
            val table = CINTable()
            table.load(cinTmp.path)
            assertTrue("合併後的字表應成功載入", !table.isEmpty)
            assertEquals("嘸蝦米", table.cinName)
            assertEquals("重複字應去重且保留 tab1→tab2→tab3 順序", listOf("對", "寸", "对", "吋"), table.lookup("a"))
            assertEquals(listOf("明", "盟"), table.lookup("ab"))
            assertEquals(listOf("測"), table.lookup("abc"))
            assertEquals(listOf("試"), table.lookup("abcd"))
            assertEquals(listOf("標"), table.lookup("a,.'"))
            assertEquals(listOf("新"), table.lookup("xyz"))
        } finally {
            cinTmp.delete()
        }
    }

    /// 測試同時匯入 liu-uni.tab / liu-uni2.tab / liu-uni3.tab / liu-uni4.tab 時，
    /// 各輸入模式（,,T / ,,S / ,,TS / ,,J）皆以該模式對應字表的候選順序為主：
    /// - 繁體 ,,T：qd → 1.替 2.体，tua → 1.杈 2.树，bnu → 1.體（不受 liu-uni3.tab 台式簡體影響）
    /// - 簡體 ,,S：qd → 1.体 2.替，tua → 1.树 2.杈
    /// - 台式簡體 ,,TS：bnu → 1.体，tqb → 1.树
    /// - 日文 ,,J：a → 1.対
    @Test
    fun modeSpecificTabOrderingForSimplifiedAndTraditional() {
        val tabT = buildSyntheticTab(listOf(
            "a" to "對",
            "bnu" to "體",
            "tqb" to "樹",
            "qd" to "替",
            "qd" to "体",
            "tua" to "杈",
            "tua" to "树",
        ))
        val tabS = buildSyntheticTab(listOf(
            "a" to "夺",
            "qd" to "体",
            "qd" to "替",
            "tua" to "树",
            "tua" to "杈",
        ))
        val tabTS = buildSyntheticTab(listOf(
            "a" to "对",
            "bnu" to "体",
            "qd" to "体",
            "qd" to "替",
            "tqb" to "树",
            "tua" to "树",
        ))
        val tabJ = buildSyntheticTab(listOf(
            "a" to "対",
            "ko" to "こ",
        ))

        val shared = File(AppEnv.sharedDir)
        val cinFile = File(AppEnv.cinPath)
        val binFiles = listOf("liu.bin", "liu-s.bin", "liu-ts.bin", "liu-j.bin").map { File(shared, it) }
        try {
            val count = CINCompiler.importSources(
                listOf(
                    "liu-uni.tab" to tabT,
                    "liu-uni2.tab" to tabS,
                    "liu-uni3.tab" to tabTS,
                    "liu-uni4.tab" to tabJ,
                ),
                shared.path,
                cinFile.path,
            )
            assertTrue("應成功匯入並編譯字碼", count > 0)

            val table = CINTable()
            table.reload()
            assertTrue("應載入簡體專屬表 liu-s.bin", table.hasModeTable(InputEngine.InputMode.S))
            assertTrue("應載入台式簡體專屬表 liu-ts.bin", table.hasModeTable(InputEngine.InputMode.TS))
            assertTrue("應載入日文專屬表 liu-j.bin", table.hasModeTable(InputEngine.InputMode.J))

            // 1. 繁體模式（預設 ,,T）：以 liu-uni.tab 為主，且 bnu 唯一候選仍為「體」
            assertEquals(listOf("替", "体"), table.lookup("qd", InputEngine.InputMode.T))
            assertEquals(listOf("杈", "树"), table.lookup("tua", InputEngine.InputMode.T))
            assertEquals(listOf("體"), table.lookup("bnu", InputEngine.InputMode.T))

            // 2. 簡體模式（,,S）：以 liu-uni2.tab 為主，qd 首選「体」、tua 首選「树」
            val ranker = CandidateRanker()
            val sQd = ranker.rank(
                table.lookup("qd", InputEngine.InputMode.S),
                "qd", InputEngine.InputMode.S, table, PinnedOrder(),
            )
            val sTua = ranker.rank(
                table.lookup("tua", InputEngine.InputMode.S),
                "tua", InputEngine.InputMode.S, table, PinnedOrder(),
            )
            assertEquals("簡體模式 qd 第一候選應為「体」", listOf("体", "替"), sQd)
            assertEquals("簡體模式 tua 第一候選應為「树」", listOf("树", "杈"), sTua)

            // 3. 台式簡體模式（,,TS）：以 liu-uni3.tab 為主
            val tsBnu = ranker.rank(
                table.lookup("bnu", InputEngine.InputMode.TS),
                "bnu", InputEngine.InputMode.TS, table, PinnedOrder(),
            )
            assertEquals(listOf("体"), tsBnu)

            // 4. 日文模式（,,J）：以 liu-uni4.tab 為主
            assertEquals("対", table.lookup("a", InputEngine.InputMode.J).first())
        } finally {
            cinFile.delete()
            binFiles.forEach { it.delete() }
        }
    }
}
