package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File
import java.util.UUID

/// 統一 CIN 字表 — mmap 二進位（CINM，經 CINCompiler）＋ .cin 文字 fallback。
/// 二進位路徑：liu.bin 以 mmap 載入（零複製）；文字路徑：解析 .cin 進 overlay dict。
///
/// 執行緒安全：字表狀態全收在 [Snap] 裡，載入端在區域變數上組好一整份才發佈
/// （@Volatile 單次指派），讀取端一開始就把 snap 抓成區域變數、之後只讀那一份。
/// 舊版是「reload() 就地把 binData/entryCount/overlay 清成空再重填」，而背景預熱執行緒
/// 同時在走同一批欄位 —— 讀到 binData 還在、entryCount 已歸零（或位移是新表的、buffer
/// 是舊表的）就會拿到錯字甚至 IndexOutOfBounds，overlay 邊填邊走則是
/// ConcurrentModificationException。（macOS 版走同一條路徑會寫壞 malloc freelist 而
/// SIGTRAP，表現為切輸入法靜默失敗。）
class CINTable {

    companion object {
        /// 字表世代 —— 設定頁匯入新 liu.cin 後 +1。鍵盤與設定頁同 process，
        /// service 據此在下次進輸入框時重載（同 SkinSettings.generation 的做法）。
        @Volatile
        var generation: Int = 0
            private set

        fun bumpGeneration() {
            generation += 1
        }
    }

    // MARK: - 不可變快照

    /// 單一 CINM 二進位字表（支援主字表 liu.bin 以及各模式專屬字表 liu-s.bin / liu-ts.bin / liu-j.bin）
    private class BinTable {
        var binData: BinData? = null
        var entryCount = 0
        var codesOff = 0
        var valsOff = 0
        var stringsOff = 0
        var charsOff = 0
        var binFormatVersion = 0
        var selKeys: List<Char> = "1234567890".toList()
        var cinName: String = ""

        fun readCode(d: BinData, i: Int): String {
            val off = d.u32(codesOff + i * 6).toInt()
            val len = d.u16(codesOff + i * 6 + 4)
            val start = stringsOff + off
            if (start < 0 || start + len > d.count) return ""
            return d.asciiString(start, len)
        }

        fun readChars(d: BinData, i: Int): List<String> {
            val entryOff = valsOff + i * 4
            if (entryOff < 0 || entryOff + 4 > d.count) return emptyList()
            // offset 是 u24：低 16 位在前兩 byte、高 8 位在第 4 byte（見 CINCompiler）
            val vOff = d.u16(entryOff) or (d.u8(entryOff + 3) shl 16)
            val vCnt = d.u8(entryOff + 2)
            if (vCnt <= 0) return emptyList()
            val firstOff = charsOff + vOff * 4
            val lastOff = charsOff + (vOff + vCnt - 1) * 4 + 4
            if (firstOff < charsOff || lastOff > d.count) return emptyList()
            val r = ArrayList<String>(vCnt)
            for (j in 0 until vCnt) {
                val cp = d.u32(charsOff + (vOff + j) * 4).toInt()
                if (Character.isValidCodePoint(cp)) r.add(String(Character.toChars(cp)))
            }
            return r
        }

        fun binSearch(code: String): Int {
            val d = binData ?: return -1
            if (entryCount <= 0) return -1
            val target = code.toByteArray(Charsets.UTF_8)
            var lo = 0; var hi = entryCount - 1
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val cmp = compareCode(d, mid, target)
                when {
                    cmp == 0 -> return mid
                    cmp < 0 -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            return -1
        }

        fun lowerBound(prefix: String): Int {
            val d = binData ?: return 0
            if (entryCount <= 0) return 0
            val target = prefix.toByteArray(Charsets.UTF_8)
            var lo = 0; var hi = entryCount
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (comparePrefixCode(d, mid, target) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        private fun compareCode(d: BinData, i: Int, target: ByteArray): Int {
            val idxOff = codesOff + i * 6
            if (idxOff < 0 || idxOff + 6 > d.count) return -1
            val off = stringsOff + d.u32(idxOff).toInt()
            val len = d.u16(idxOff + 4)
            if (off < 0 || off + len > d.count) return -1
            for (j in 0 until len) {
                if (j >= target.size) return 1
                val a = d.u8(off + j)
                val b = target[j].toInt() and 0xFF
                if (a != b) return a - b
            }
            if (len < target.size) return -1
            return 0
        }

        private fun comparePrefixCode(d: BinData, i: Int, prefix: ByteArray): Int {
            val idxOff = codesOff + i * 6
            if (idxOff < 0 || idxOff + 6 > d.count) return -1
            val off = stringsOff + d.u32(idxOff).toInt()
            val len = d.u16(idxOff + 4)
            if (off < 0 || off + len > d.count) return -1
            val n = minOf(len, prefix.size)
            for (j in 0 until n) {
                val a = d.u8(off + j)
                val b = prefix[j].toInt() and 0xFF
                if (a != b) return a - b
            }
            if (len < prefix.size) return -1
            return 0
        }

        fun codeHasPrefix(d: BinData, i: Int, prefix: ByteArray): Boolean {
            val idxOff = codesOff + i * 6
            if (idxOff < 0 || idxOff + 6 > d.count) return false
            val off = stringsOff + d.u32(idxOff).toInt()
            val len = d.u16(idxOff + 4)
            if (off < 0 || off + len > d.count) return false
            if (len < prefix.size) return false
            for (j in prefix.indices) {
                if (d.u8(off + j) != (prefix[j].toInt() and 0xFF)) return false
            }
            return true
        }

        fun lookup(c: String): List<String> {
            val d = binData ?: return emptyList()
            val idx = binSearch(c)
            return if (idx >= 0) readChars(d, idx) else emptyList()
        }

        fun hasPrefix(p: String): Boolean {
            val d = binData ?: return false
            val i = lowerBound(p)
            return i < entryCount && codeHasPrefix(d, i, p.toByteArray(Charsets.UTF_8))
        }

        fun addValidNextKeys(p: String, out: MutableSet<Char>) {
            val d = binData ?: return
            val pBytes = p.toByteArray(Charsets.UTF_8)
            val pLen = pBytes.size
            val start = lowerBound(p)
            for (i in start until entryCount) {
                if (!codeHasPrefix(d, i, pBytes)) break
                val codeLen = d.u16(codesOff + i * 6 + 4)
                if (codeLen > pLen) {
                    val off = stringsOff + d.u32(codesOff + i * 6).toInt()
                    if (off + pLen >= d.count) continue
                    out.add(d.u8(off + pLen).toInt().toChar())
                }
            }
        }

        fun collectWildcard(fix: String, re: Regex, seen: MutableSet<String>, out: MutableList<String>) {
            val d = binData ?: return
            val start = if (fix.isEmpty()) 0 else lowerBound(fix)
            val fixBytes = fix.toByteArray(Charsets.UTF_8)
            for (i in start until entryCount) {
                if (fix.isNotEmpty() && !codeHasPrefix(d, i, fixBytes)) break
                val code = readCode(d, i)
                if (re.matches(code)) {
                    for (c in readChars(d, i)) if (seen.add(c)) out.add(c)
                }
            }
        }
    }

    /// 一份完整的字表。只有載入期間（還沒發佈、只有一條執行緒看得到）才寫欄位，
    /// 發佈給 [snap] 之後一律只讀 —— 所以下面的查詢方法在任何執行緒上都安全。
    private class Snap {
        // mmap 二進位：主字表（liu.bin，對應繁體 ,,T）與各模式專屬字表
        var mainBin = BinTable()
        var simpBin: BinTable? = null   // liu-s.bin（,,S 大陸簡體 liu-uni2.tab 優先）
        var tsBin: BinTable? = null     // liu-ts.bin（,,TS 台式簡體 liu-uni3.tab 優先）
        var jpBin: BinTable? = null     // liu-j.bin（,,J 日文 liu-uni4.tab 優先）

        // 文字 fallback + overlay（extras — 小型 dict）
        var overlay: MutableMap<String, MutableList<String>> = HashMap()

        /// 常用語自訂組字碼（UserPhrases）— 與字表分開存：查詢時排在既有候選之後，
        /// 不參與字頻排序，使用者設了跟字表撞碼的捷徑也不會把原本的字擠掉
        var shortcuts: Map<String, List<String>> = emptyMap()

        var t2s: Map<String, String> = emptyMap()
        var s2t: Map<String, String> = emptyMap()
        var selKeys: List<Char>
            get() = mainBin.selKeys
            set(value) { mainBin.selKeys = value }
        var cinName: String
            get() = mainBin.cinName
            set(value) { mainBin.cinName = value }
        var maxCodeLength: Int = 4

        val entryCount: Int get() = mainBin.entryCount
        val binFormatVersion: Int get() = mainBin.binFormatVersion
        val isEmpty: Boolean get() = mainBin.entryCount == 0 && overlay.isEmpty()

        fun binFor(mode: InputEngine.InputMode): BinTable = when (mode) {
            InputEngine.InputMode.S -> simpBin?.takeIf { it.entryCount > 0 } ?: mainBin
            InputEngine.InputMode.TS -> tsBin?.takeIf { it.entryCount > 0 } ?: mainBin
            InputEngine.InputMode.J -> jpBin?.takeIf { it.entryCount > 0 } ?: mainBin
            else -> mainBin
        }

        fun hasModeTable(mode: InputEngine.InputMode): Boolean = when (mode) {
            InputEngine.InputMode.S -> (simpBin?.entryCount ?: 0) > 0
            InputEngine.InputMode.TS -> (tsBin?.entryCount ?: 0) > 0
            InputEngine.InputMode.J -> (jpBin?.entryCount ?: 0) > 0
            else -> mainBin.entryCount > 0
        }

        // MARK: 反查建表

        fun buildReverseTable(): Map<String, List<String>> {
            val r = HashMap<String, MutableList<String>>()
            val seenPerChar = HashMap<String, MutableSet<String>>()
            fun addFromBin(bt: BinTable?) {
                val d = bt?.binData ?: return
                for (i in 0 until bt.entryCount) {
                    val code = bt.readCode(d, i)
                    for (ch in bt.readChars(d, i)) {
                        if (seenPerChar.getOrPut(ch) { HashSet() }.add(code)) {
                            r.getOrPut(ch) { mutableListOf() }.add(code)
                        }
                    }
                }
            }
            addFromBin(mainBin)
            addFromBin(simpBin)
            addFromBin(tsBin)
            addFromBin(jpBin)
            for ((code, chars) in overlay) {
                for (c in chars) {
                    if (seenPerChar.getOrPut(c) { HashSet() }.add(code)) {
                        r.getOrPut(c) { mutableListOf() }.add(code)
                    }
                }
            }
            return r
        }

        // MARK: 查詢

        fun lookup(code: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): List<String> {
            val c = code.lowercase()
            val targetBin = binFor(mode)
            var result: List<String> = targetBin.lookup(c)
            if (result.isEmpty() && targetBin !== mainBin) {
                result = mainBin.lookup(c)
            }
            overlay[c]?.let { extra -> result = extra + result }
            return result
        }

        /// 常用語捷徑 — 不含字表本身的候選（見 [shortcuts]）
        fun shortcutLookup(code: String): List<String> = shortcuts[code.lowercase()] ?: emptyList()

        fun hasPrefix(prefix: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): Boolean {
            val p = prefix.lowercase()
            val targetBin = binFor(mode)
            if (targetBin.hasPrefix(p)) return true
            if (targetBin !== mainBin && mainBin.hasPrefix(p)) return true
            return overlay.keys.any { it.startsWith(p) } || shortcuts.keys.any { it.startsWith(p) }
        }

        fun validNextKeys(after: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): Set<Char> {
            val p = after.lowercase()
            val result = HashSet<Char>()
            val targetBin = binFor(mode)
            targetBin.addValidNextKeys(p, result)
            if (targetBin !== mainBin) mainBin.addValidNextKeys(p, result)
            for (key in overlay.keys) {
                if (key.startsWith(p) && key.length > p.length) result.add(key[p.length])
            }
            for (key in shortcuts.keys) {
                if (key.startsWith(p) && key.length > p.length) result.add(key[p.length])
            }
            return result
        }

        fun wildcardLookup(pattern: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): List<String> {
            val pat = pattern.lowercase()
            if (!pat.contains("*")) return lookup(pat, mode)
            // 逐字元 escape、* → .+（對應 iOS 的 escapedPattern + replacingOccurrences）
            val sb = StringBuilder("^")
            for (ch in pat) {
                if (ch == '*') sb.append(".+")
                else if (ch.isLetterOrDigit()) sb.append(ch)
                else sb.append(Regex.escape(ch.toString()))
            }
            sb.append("$")
            val re = try { Regex(sb.toString()) } catch (e: Exception) { return emptyList() }
            val fix = pat.takeWhile { it != '*' }
            val results = ArrayList<String>()
            val seen = HashSet<String>()
            val targetBin = binFor(mode)
            targetBin.collectWildcard(fix, re, seen, results)
            if (targetBin !== mainBin) mainBin.collectWildcard(fix, re, seen, results)
            for ((code, chars) in overlay) {
                if (fix.isNotEmpty() && !code.startsWith(fix)) continue
                if (re.matches(code)) {
                    for (c in chars) if (seen.add(c)) results.add(c)
                }
            }
            return results
        }
    }

    @Volatile private var snap = Snap()

    // MARK: - 反查快取（lazy；可由背景執行緒預熱）
    //
    // 建表要走遍整張表，成本高 —— 只用 buildLock 序列化建表，發佈快照的 reload()
    // 完全不碰這把鎖，主執行緒不會卡在背景預熱後面（IME 主執行緒卡住＝ANR）。
    // 快取以「當時那份 Snap 的參考」認親：字表換掉就整組作廢，慢一步算完的舊結果
    // 也不會被寫上去（否則會一直給舊表的字根提示）。
    private val buildLock = Any()

    @Volatile private var cacheOwner: Snap? = null
    @Volatile private var _reverseTable: Map<String, List<String>>? = null
    @Volatile private var _shortestCodes: Map<String, Set<String>>? = null
    @Volatile private var _longestCodes: Map<String, Set<String>>? = null

    private val reverseTable: Map<String, List<String>>
        get() {
            val s = snap
            if (cacheOwner === s) _reverseTable?.let { return it }
            if (!MemoryBudget.canAfford(MemoryBudget.reverseTable)) return emptyMap()
            synchronized(buildLock) {
                val cur = snap
                if (cacheOwner !== cur) resetCaches(cur)
                _reverseTable?.let { return it }
                val r = cur.buildReverseTable()
                if (snap === cur) _reverseTable = r
                return r
            }
        }

    /// 整表反查建立成本高（走遍所有 entry）— service 啟動後在背景執行緒呼叫，
    /// 免得第一次用到（查碼提示/注音同音字/,,SC）的那個按鍵卡住
    fun warmUpReverseCache() {
        reverseTable
    }

    val shortestCodesTable: Map<String, Set<String>>
        get() {
            if (cacheOwner === snap) _shortestCodes?.let { return it }
            val rev = reverseTable
            synchronized(buildLock) {
                val cur = snap
                if (cacheOwner !== cur) resetCaches(cur)
                _shortestCodes?.let { return it }
                val r = HashMap<String, Set<String>>()
                for ((ch, codes) in rev) {
                    val m = codes.minOfOrNull { it.length } ?: 0
                    r[ch] = codes.filter { it.length == m }.toSet()
                }
                if (snap === cur) _shortestCodes = r
                return r
            }
        }

    val longestCodesTable: Map<String, Set<String>>
        get() {
            if (cacheOwner === snap) _longestCodes?.let { return it }
            val rev = reverseTable
            synchronized(buildLock) {
                val cur = snap
                if (cacheOwner !== cur) resetCaches(cur)
                _longestCodes?.let { return it }
                val r = HashMap<String, Set<String>>()
                for ((ch, codes) in rev) {
                    val m = codes.maxOfOrNull { it.length } ?: 0
                    r[ch] = codes.filter { it.length == m }.toSet()
                }
                if (snap === cur) _longestCodes = r
                return r
            }
        }

    private fun resetCaches(owner: Snap) {
        _reverseTable = null
        _shortestCodes = null
        _longestCodes = null
        cacheOwner = owner
    }

    val t2s: Map<String, String> get() = snap.t2s
    val s2t: Map<String, String> get() = snap.s2t
    val selKeys: List<Char> get() = snap.selKeys
    val cinName: String get() = snap.cinName
    val isEmpty: Boolean get() = snap.isEmpty
    val maxCodeLength: Int get() = snap.maxCodeLength

    fun releaseOptionalCaches() {
        resetCaches(snap)
    }

    /// 整份原子替換 —— 讀取端要嘛看到舊的完整快照、要嘛看到新的完整快照，沒有中間態。
    private fun publish(s: Snap) {
        snap = s
        resetCaches(s)
    }

    // MARK: - 載入

    fun reload() {
        val s = Snap()

        // 1. sharedDir 的 mmap 二進位
        val userBin = AppEnv.sharedDir + "/liu.bin"
        if (File(userBin).exists()) loadBin(userBin, s.mainBin)
        // 1b. 舊版編譯器產出的 .bin（格式版本 0）位移只有 u16，大字表尾端讀出來是錯字。
        //     使用者不該為了拿到修正而重新匯入一次 — 手上還有 liu.cin 就直接重編。
        if (s.entryCount > 0 && s.binFormatVersion < CINCompiler.FORMAT_VERSION &&
            File(AppEnv.cinPath).exists()
        ) {
            // 編到暫存檔、成功才換掉 — 重編失敗就沿用舊檔（字是舊的錯法，但還能打）
            val tmp = File("$userBin.tmp")
            val dst = File(userBin)
            val replaced = CINCompiler.compile(AppEnv.cinPath, tmp.path) > 0 &&
                (tmp.renameTo(dst) || runCatching { tmp.copyTo(dst, overwrite = true); true }.getOrDefault(false))
            if (replaced) {
                s.mainBin = BinTable()
                loadBin(userBin, s.mainBin)
            }
            tmp.delete()
        }
        // 2. 無 .bin → 嘗試現場編譯 .cin → .bin
        if (s.entryCount == 0) {
            val cinPath = AppEnv.cinPath
            if (File(cinPath).exists()) {
                CINCompiler.compile(cinPath, userBin)
                if (File(userBin).exists()) loadBin(userBin, s.mainBin)
                // 編譯失敗 → 文字 fallback
                if (s.entryCount == 0) parseCINIntoOverlay(cinPath, s)
            }
        }
        // 2b. 各輸入模式專屬 .bin（匯入 liu-uni2/3/4.tab 時產出）
        val tsBinFile = File(AppEnv.sharedDir, "liu-ts.bin")
        if (tsBinFile.exists()) {
            val bt = BinTable()
            loadBin(tsBinFile.path, bt)
            if (bt.entryCount > 0) s.tsBin = bt
        }
        val sBinFile = File(AppEnv.sharedDir, "liu-s.bin")
        if (sBinFile.exists()) {
            val bt = BinTable()
            loadBin(sBinFile.path, bt)
            if (bt.entryCount > 0) s.simpBin = bt
        }
        // 自動修正舊版 liu-s.bin（將 liu-uni2 排在 liu-uni3 前面導致 a=夺、qd=替、wr=刘、ow=响）：
        // 若 liu-ts.bin（以 liu-uni3 優先）已存在，直接升級沿用，免重新匯入即可生效
        val curSimp = s.simpBin
        val curTs = s.tsBin
        if (curSimp != null && curTs != null &&
            curSimp.lookup("a").firstOrNull() == "夺" &&
            curTs.lookup("a").firstOrNull() == "对"
        ) {
            s.simpBin = curTs
        }
        val jBinFile = File(AppEnv.sharedDir, "liu-j.bin")
        if (jBinFile.exists()) {
            val bt = BinTable()
            loadBin(jBinFile.path, bt)
            if (bt.entryCount > 0) s.jpBin = bt
        }
        // 3. Extras
        loadExtras(s)
        // 4. 簡繁對照表
        loadCharMaps(s)
        // 5. 常用語自訂組字碼
        s.shortcuts = UserPhrases.shared.shortcuts
        // 6. maxCodeLength
        recomputeMaxCodeLength(s)

        publish(s)
    }

    /// 從 .cin 文字檔載入（先編到暫存 .bin）— 測試與現場使用。
    fun load(cinPath: String) {
        val s = Snap()
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "."
        val tmp = "$tmpDir/cin_${UUID.randomUUID()}.bin"
        CINCompiler.compile(cinPath, tmp)
        val f = File(tmp)
        if (f.exists()) {
            BinData.of(f)?.let { parseBinData(it, s.mainBin) }
            f.delete()
        }
        if (s.entryCount == 0) parseCINIntoOverlay(cinPath, s)
        s.shortcuts = UserPhrases.shared.shortcuts
        recomputeMaxCodeLength(s)
        publish(s)
    }

    private fun recomputeMaxCodeLength(s: Snap) {
        var maxLen = 4
        fun scanBin(bt: BinTable?) {
            val d = bt?.binData ?: return
            for (i in 0 until bt.entryCount) {
                val len = d.u16(bt.codesOff + i * 6 + 4)
                if (len > maxLen) maxLen = len
            }
        }
        scanBin(s.mainBin)
        scanBin(s.simpBin)
        scanBin(s.tsBin)
        scanBin(s.jpBin)
        for (k in s.overlay.keys) if (k.length > maxLen) maxLen = k.length
        for (k in s.shortcuts.keys) if (k.length > maxLen) maxLen = k.length
        s.maxCodeLength = maxLen
    }

    // MARK: - 二進位載入

    private fun loadBin(path: String, bt: BinTable) {
        val d = BinData.mapped(path) ?: return
        parseBinData(d, bt)
    }

    private fun parseBinData(d: BinData, bt: BinTable) {
        if (d.count < 128 || d.u8(0) != 0x43 || d.u8(1) != 0x49 || d.u8(2) != 0x4E || d.u8(3) != 0x4D) return
        bt.entryCount = d.u32(4).toInt()
        val skLen = d.u8(8)
        if (skLen in 1..20 && 9 + skLen <= d.count) {
            bt.selKeys = (0 until skLen).map { d.u8(9 + it).toInt().toChar() }
        }
        val cnLen = d.u16(20)
        if (cnLen > 0 && 22 + cnLen <= d.count) bt.cinName = d.utf8String(22, cnLen)
        bt.binFormatVersion = d.u32(88).toInt()
        bt.codesOff = d.u32(96).toInt()
        bt.valsOff = d.u32(100).toInt()
        bt.stringsOff = d.u32(104).toInt()
        bt.charsOff = d.u32(108).toInt()
        if (bt.codesOff < 128 || bt.codesOff >= bt.valsOff || bt.valsOff >= bt.stringsOff ||
            bt.stringsOff >= bt.charsOff || bt.charsOff > d.count
        ) {
            bt.entryCount = 0; return
        }
        bt.binData = d
    }

    // MARK: - 文字 CIN 解析（fallback + overlay）

    private fun parseCINIntoOverlay(path: String, s: Snap) {
        val f = File(path)
        // 檔案大小限制
        if (!f.exists()) return
        if (f.length() > 100_000_000L) {
            return
        }
        val content = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return }
        var inChardef = false
        var lineCount = 0
        val maxLines = 500_000
        for (line in content.lineSequence()) {
            lineCount += 1
            if (lineCount > maxLines) break
            val t = line.trim()
            if (t.startsWith("%selkey ")) {
                val keys = t.substring(8).trim()
                if (keys.isNotEmpty()) s.selKeys = keys.toList()
                continue
            }
            if (t.startsWith("%cname ")) { s.cinName = t.substring(7).trim(); continue }
            if (t == "%chardef begin") { inChardef = true; continue }
            if (t == "%chardef end") { inChardef = false; continue }
            if (!inChardef) continue
            val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
            if (parts.size != 2) continue
            val code = parts[0].lowercase()
            val value = parts[1].trim()
            s.overlay.getOrPut(code) { mutableListOf() }.add(value)
        }
    }

    // MARK: - Extras + 簡繁對照

    private fun loadExtras(s: Snap) {
        val dir = File(AppEnv.tablesDir)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.name.endsWith(".txt")) continue
            val content = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { continue }
            for (line in content.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val parts = t.split("\t", limit = 2)
                if (parts.size != 2) continue
                s.overlay.getOrPut(parts[0].lowercase()) { mutableListOf() }.add(parts[1])
            }
        }
    }

    private fun loadCharMaps(s: Snap) {
        s.t2s = loadCharMap("t2s") ?: emptyMap()
        s.s2t = loadCharMap("s2t") ?: emptyMap()
    }

    private fun loadCharMap(name: String): Map<String, String>? {
        val f = File(AppEnv.sharedDir, "$name.json")
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val r = HashMap<String, String>()
            for (key in json.keys()) r[key] = json.getString(key)
            r
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - 查詢（公開 API）

    fun hasModeTable(mode: InputEngine.InputMode): Boolean = snap.hasModeTable(mode)

    fun lookup(code: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): List<String> =
        snap.lookup(code, mode)

    /// 常用語自訂組字碼的詞（不含字表候選）。撞碼時引擎把這些排在字表候選之後。
    fun shortcutLookup(code: String): List<String> = snap.shortcutLookup(code)

    fun hasPrefix(prefix: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): Boolean =
        snap.hasPrefix(prefix, mode)

    fun validNextKeys(after: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): Set<Char> =
        snap.validNextKeys(after, mode)

    fun wildcardLookup(pattern: String, mode: InputEngine.InputMode = InputEngine.InputMode.T): List<String> =
        snap.wildcardLookup(pattern, mode)

    fun reverseLookup(char: String): List<String> = reverseTable[char] ?: emptyList()

    fun convert(char: String, map: Map<String, String>): String = map[char] ?: char
}
