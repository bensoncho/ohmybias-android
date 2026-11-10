package info.plateaukao.ohmybias.shared

import java.io.ByteArrayOutputStream
import java.io.File

/// .cin 文字檔／官方 .tab 二進位檔 → CINM 二進位（供 mmap 載入）。格式與 iOS 版完全相同，檔案可跨平台共用。
///
/// ⚠️  重要：.cin / .tab 檔有版權（如行易的嘸蝦米）。本編譯器只在使用者匯入時 on-device 執行，
///    絕不預編或隨附 .bin — 它們是版權素材的衍生物。
object CINCompiler {

    /// CINM 格式版本（header offset 88）。0 = 未標版本的舊檔（val index 位移只有 u16，
    /// 大字表尾端是錯字）；1 = u24 位移。舊檔的這個欄位本來就是 0，故可據此辨識並重編。
    const val FORMAT_VERSION = 1

    /// 官方 Windows 嘸蝦米 liu-uni*.tab 的 32 個字根字元表（index 0 為空白代表無此碼位）
    private const val TAB_ROOT_KEYS = " abcdefghijklmnopqrstuvwxyz,.'[]"

    /// 判斷 byte array 是否為官方 Windows 嘸蝦米 liu-uni*.tab 二進位格式
    fun isTabBytes(bytes: ByteArray): Boolean {
        if (bytes.size <= 2050) return false
        val i1 = getU16(bytes, 0)
        // 官方 liu-uni*.tab 的索引表有 1025 個 uint16（bytes 0..2049），故 i1 = 2050 (0x0802)
        if (i1 < 2048 || i1 > 2064) return false
        val s1 = getU16(bytes, 2)
        val words = getU16(bytes, 4)
        val s2 = getU16(bytes, 6)
        if (words <= 0 || s1 <= 0 || s2 <= 0) return false
        val i4 = i1 + s1 + s2 + s2
        return i4 > i1 && i4 + words * 3 <= bytes.size
    }

    /// 解析官方 Windows 嘸蝦米 liu-uni*.tab 二進位檔，回傳依原本優先序排列的 (code, char) 清單。
    /// 若格式不符則回傳空清單。
    fun parseTab(bytes: ByteArray): List<Pair<String, String>> {
        if (!isTabBytes(bytes)) return emptyList()
        val i1 = getU16(bytes, 0)
        val words = getU16(bytes, 4)
        val i2 = i1 + getU16(bytes, 2)
        val i3 = i2 + getU16(bytes, 6)
        val i4 = i3 + getU16(bytes, 6)
        val result = ArrayList<Pair<String, String>>(words)

        val maxBucket = minOf(1024, (i1 - 2) / 2)
        for (i in 32 until maxBucket) {
            val start = getU16(bytes, i * 2)
            val end = getU16(bytes, i * 2 + 2)
            if (start >= end) continue
            val k1 = TAB_ROOT_KEYS[i / 32]
            val k2 = TAB_ROOT_KEYS[i % 32]
            for (ci in start until end) {
                val p = i4 + ci * 3
                val hiByteIdx = i1 + (ci * 2) / 8
                if (p + 2 >= bytes.size || hiByteIdx >= bytes.size) break
                val bit24 = ((bytes[p].toInt() and 0xFF) shl 16) or
                    ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                    (bytes[p + 2].toInt() and 0xFF)
                val hi = ((bytes[hiByteIdx].toInt() and 0xFF) ushr (6 - (ci % 4) * 2)) and 0x03
                val lo = bit24 and 0x3FFF
                val cp = (hi shl 14) or lo
                if (cp <= 0 || cp in 0xD800..0xDFFF || cp == 0xFFFE || cp == 0xFFFF) continue
                val k3 = TAB_ROOT_KEYS[(bit24 ushr 19) and 0x1F]
                val k4 = TAB_ROOT_KEYS[(bit24 ushr 14) and 0x1F]
                val code = buildString(4) {
                    append(k1)
                    if (k2 != ' ') append(k2)
                    if (k3 != ' ') append(k3)
                    if (k4 != ' ') append(k4)
                }
                val ch = String(Character.toChars(cp))
                result.add(code to ch)
            }
        }
        return result
    }

    /// 官方 Windows 嘸蝦米四種 .tab 字表類型
    enum class TabKind {
        T,   // liu-uni.tab  (,,T 繁體模式，首碼 "a" 首字為 "對")
        S,   // liu-uni2.tab (,,C 大陸簡體模式，首碼 "a" 首字為 "夺")
        TS,  // liu-uni3.tab (,,CT 台式簡體模式，首碼 "a" 首字為 "对")
        J,   // liu-uni4.tab (,,J 日文模式，首碼 "a" 首字為 "対")
    }

    /// 根據解析出的 (code, char) 內容（首碼 "a" 的第一候選字）與檔名自動辨識 .tab 類型
    fun detectTabKind(pairs: List<Pair<String, String>>, fileNameHint: String = ""): TabKind {
        val firstA = pairs.firstOrNull { it.first == "a" }?.second
        when (firstA) {
            "對" -> return TabKind.T
            "夺" -> return TabKind.S
            "对" -> return TabKind.TS
            "対" -> return TabKind.J
        }
        val lower = fileNameHint.substringBeforeLast('.').lowercase()
        return when {
            lower.endsWith("2") -> TabKind.S
            lower.endsWith("3") -> TabKind.TS
            lower.endsWith("4") -> TabKind.J
            else -> TabKind.T
        }
    }

    /// 將一或多個具名 .cin / .tab 檔案匯入至 sharedDir：
    /// - 產生主表 liu.cin 與 liu.bin（以繁體表 liu-uni.tab 排序為主）
    /// - 若包含簡體表（liu-uni2.tab）、台式簡體表（liu-uni3.tab）、日文表（liu-uni4.tab），
    ///   同時分別編譯出專屬排序的 liu-s.bin、liu-ts.bin、liu-j.bin，供各輸入模式直接以該模式的官方排序查詢。
    /// 回傳主表編譯出的字碼數，失敗回 0。
    fun importSources(
        namedSources: List<Pair<String, ByteArray>>,
        sharedDir: String,
        dstCinPath: String,
    ): Int {
        if (namedSources.isEmpty()) return 0
        val tabsByKind = HashMap<TabKind, MutableList<Pair<String, String>>>()
        val cinPairs = ArrayList<Pair<String, String>>()
        var selkeys = "0123456789"
        var cname = ""

        for ((name, raw) in namedSources) {
            if (isTabBytes(raw)) {
                val pairs = parseTab(raw)
                if (pairs.isEmpty()) continue
                if (cname.isEmpty()) cname = "嘸蝦米"
                val kind = detectTabKind(pairs, name)
                tabsByKind.getOrPut(kind) { ArrayList(pairs.size) }.addAll(pairs)
            } else {
                val content = try { String(raw, Charsets.UTF_8) } catch (e: Exception) { continue }
                var inChardef = false
                for (line in content.lineSequence()) {
                    val t = line.trim()
                    if (t.startsWith("%selkey ")) { selkeys = t.substring(8).trim(); continue }
                    if (t.startsWith("%cname ")) {
                        val parsedName = t.substring(7).trim()
                        if (cname.isEmpty() && parsedName.isNotEmpty()) cname = parsedName
                        continue
                    }
                    if (t == "%chardef begin") { inChardef = true; continue }
                    if (t == "%chardef end") { inChardef = false; continue }
                    if (!inChardef) continue
                    val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
                    if (parts.size != 2) continue
                    val code = parts[0].lowercase()
                    val ch = parts[1].trim()
                    if (code.isNotEmpty() && ch.isNotEmpty()) cinPairs.add(code to ch)
                }
            }
        }

        if (tabsByKind.isEmpty() && cinPairs.isEmpty()) return 0
        if (cname.isEmpty()) cname = "嘸蝦米"

        val tPairs = tabsByKind[TabKind.T] ?: emptyList()
        val sPairs = tabsByKind[TabKind.S] ?: emptyList()
        val tsPairs = tabsByKind[TabKind.TS] ?: emptyList()
        val jPairs = tabsByKind[TabKind.J] ?: emptyList()

        // 1. 主表（繁中模式 T）：以 liu-uni.tab 優先；僅對 liu-uni.tab 未收錄的獨有字碼補齊其他表
        //    （避免把 liu-uni2.tab 大陸異義簡碼如 qd=替/狱、wr=刘、ow=响 混入繁體既有字碼）
        val mainList = ArrayList<Pair<String, String>>()
        if (tPairs.isNotEmpty()) {
            val tCodes = tPairs.mapTo(HashSet(tPairs.size)) { it.first }
            mainList.addAll(tPairs)
            for (p in sPairs) if (p.first !in tCodes) mainList.add(p)
            for (p in jPairs) if (p.first !in tCodes) mainList.add(p)
            if (sPairs.isEmpty()) {
                for (p in tsPairs) if (p.first !in tCodes) mainList.add(p)
            }
        } else {
            mainList.addAll(tsPairs)
            mainList.addAll(sPairs)
            mainList.addAll(jPairs)
        }
        mainList.addAll(cinPairs)
        if (mainList.isEmpty()) return 0

        val cinFile = File(dstCinPath)
        val binFile = File(sharedDir, "liu.bin")
        val cinTmp = File("$dstCinPath.tmp")
        val binTmp = File("${binFile.path}.tmp")

        val sBinFile = File(sharedDir, "liu-s.bin")
        val tsBinFile = File(sharedDir, "liu-ts.bin")
        val jBinFile = File(sharedDir, "liu-j.bin")
        val sBinTmp = File("${sBinFile.path}.tmp")
        val tsBinTmp = File("${tsBinFile.path}.tmp")
        val jBinTmp = File("${jBinFile.path}.tmp")

        try {
            if (!writePairsToCin(mainList, cinTmp.path, cname, selkeys)) return 0
            val count = compilePairs(mainList, binTmp.path, cname, selkeys)
            if (count <= 0) return 0

            // 2. 簡中模式（,,S）專屬字表：
            //    官方 liu-uni3.tab（30,945 筆、22,778 碼）與 liu-uni.tab 擁有完全相同的 22,778 個字碼，
            //    同時包含標準嘸蝦米兩碼簡碼（qd=体、wr=测、ow=选、a=对、td=替、wd=响）與簡體字形拆碼（tua=树、pte=体、srw=选、opno=响）；
            //    而 liu-uni2.tab（15,112 筆）將兩碼簡碼改為大陸簡碼（qd=替、wr=刘、ow=响、a=夺、td=动、wd=潜）。
            //    故 ,,S 以 liu-uni3.tab（TS）為第一優先，再接 liu-uni2.tab（S）補齊大陸特有拆碼。
            val hasSimpTab = tsPairs.isNotEmpty() || sPairs.isNotEmpty()
            if (hasSimpTab) {
                val simpList = ArrayList<Pair<String, String>>(tsPairs.size + sPairs.size + tPairs.size)
                simpList.addAll(tsPairs)
                simpList.addAll(sPairs)
                if (tsPairs.isEmpty()) simpList.addAll(tPairs)
                simpList.addAll(cinPairs)
                if (compilePairs(simpList, sBinTmp.path, cname, selkeys) <= 0) return 0
            }

            // 3. 繁→簡／台式簡體模式（,,TS）專屬字表：以 liu-uni3.tab（TS）為第一優先
            val hasTsTab = tsPairs.isNotEmpty()
            if (hasTsTab) {
                val tsList = ArrayList<Pair<String, String>>(tsPairs.size + sPairs.size)
                tsList.addAll(tsPairs)
                tsList.addAll(sPairs)
                tsList.addAll(cinPairs)
                if (compilePairs(tsList, tsBinTmp.path, cname, selkeys) <= 0) return 0
            }

            // 4. 日文模式（,,J）專屬字表：以 liu-uni4.tab（J）為第一優先
            val hasJpTab = jPairs.isNotEmpty()
            if (hasJpTab) {
                val jpList = ArrayList<Pair<String, String>>(jPairs.size + tPairs.size)
                jpList.addAll(jPairs)
                jpList.addAll(tPairs)
                jpList.addAll(cinPairs)
                if (compilePairs(jpList, jBinTmp.path, cname, selkeys) <= 0) return 0
            }

            if (!replaceFile(cinTmp, cinFile) || !replaceFile(binTmp, binFile)) return 0
            if (hasSimpTab) replaceFile(sBinTmp, sBinFile) else sBinFile.delete()
            if (hasTsTab) replaceFile(tsBinTmp, tsBinFile) else tsBinFile.delete()
            if (hasJpTab) replaceFile(jBinTmp, jBinFile) else jBinFile.delete()
            return count
        } finally {
            cinTmp.delete(); binTmp.delete()
            sBinTmp.delete(); tsBinTmp.delete(); jBinTmp.delete()
        }
    }

    private fun replaceFile(srcTmp: File, dst: File): Boolean {
        if (srcTmp.renameTo(dst)) return true
        return try {
            srcTmp.copyTo(dst, overwrite = true)
            srcTmp.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    /// 將一或多個 .cin / .tab 檔案內容合併、去除重複的 (code, char) 後，輸出為標準 .cin 檔。
    /// 保留先讀入檔案的候選字優先順序（例如 liu-uni.tab 優先於 liu-uni2/3/4.tab）。
    /// 成功寫出且至少有一筆有效字碼時回傳 true。
    fun mergeToCin(sources: List<ByteArray>, dstCin: String): Boolean {
        if (sources.isEmpty()) return false
        val allPairs = ArrayList<Pair<String, String>>()
        var selkeys = "0123456789"
        var cname = ""

        for (raw in sources) {
            if (isTabBytes(raw)) {
                val pairs = parseTab(raw)
                if (pairs.isEmpty()) continue
                if (cname.isEmpty()) cname = "嘸蝦米"
                allPairs.addAll(pairs)
            } else {
                val content = try { String(raw, Charsets.UTF_8) } catch (e: Exception) { continue }
                var inChardef = false
                for (line in content.lineSequence()) {
                    val t = line.trim()
                    if (t.startsWith("%selkey ")) { selkeys = t.substring(8).trim(); continue }
                    if (t.startsWith("%cname ")) {
                        val parsedName = t.substring(7).trim()
                        if (cname.isEmpty() && parsedName.isNotEmpty()) cname = parsedName
                        continue
                    }
                    if (t == "%chardef begin") { inChardef = true; continue }
                    if (t == "%chardef end") { inChardef = false; continue }
                    if (!inChardef) continue
                    val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
                    if (parts.size != 2) continue
                    allPairs.add(parts[0].lowercase() to parts[1].trim())
                }
            }
        }
        if (cname.isEmpty()) cname = "嘸蝦米"
        return writePairsToCin(allPairs, dstCin, cname, selkeys)
    }

    private fun writePairsToCin(
        pairs: List<Pair<String, String>>,
        dstCin: String,
        cname: String,
        selkeys: String,
    ): Boolean {
        data class Entry(val code: String, val chars: MutableList<String>)
        val entries = mutableListOf<Entry>()
        val codeMap = HashMap<String, Int>()
        for ((code, ch) in pairs) {
            if (code.isEmpty() || ch.isEmpty()) continue
            val idx = codeMap[code]
            if (idx != null) {
                val chars = entries[idx].chars
                if (ch !in chars) chars.add(ch)
            } else {
                codeMap[code] = entries.size
                entries.add(Entry(code, mutableListOf(ch)))
            }
        }
        if (entries.isEmpty()) return false
        return try {
            File(dstCin).bufferedWriter(Charsets.UTF_8).use { out ->
                out.write("%cname $cname\n")
                out.write("%selkey $selkeys\n")
                out.write("%chardef begin\n")
                for (e in entries) {
                    for (ch in e.chars) {
                        out.write(e.code)
                        out.write("\t")
                        out.write(ch)
                        out.write("\n")
                    }
                }
                out.write("%chardef end\n")
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /// 編譯 srcPath 的 .cin（或單一 .tab）→ 寫出 bin 至 dstPath。回傳 entry 數，失敗回 0。
    fun compile(src: String, dst: String): Int {
        val raw = try { File(src).readBytes() } catch (e: Exception) { return 0 }
        var selkeys = "0123456789"
        var cname = ""
        val pairs = ArrayList<Pair<String, String>>()

        if (isTabBytes(raw)) {
            cname = "嘸蝦米"
            pairs.addAll(parseTab(raw))
        } else {
            val content = try { String(raw, Charsets.UTF_8) } catch (e: Exception) { return 0 }
            var inChardef = false
            for (line in content.lineSequence()) {
                val t = line.trim()
                if (t.startsWith("%selkey ")) { selkeys = t.substring(8).trim(); continue }
                if (t.startsWith("%cname ")) { cname = t.substring(7).trim(); continue }
                if (t == "%chardef begin") { inChardef = true; continue }
                if (t == "%chardef end") { inChardef = false; continue }
                if (!inChardef) continue
                val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
                if (parts.size != 2) continue
                pairs.add(parts[0].lowercase() to parts[1].trim())
            }
        }
        return compilePairs(pairs, dst, cname, selkeys)
    }

    /// 將 (code, char) 清單去重後直接編譯為 CINM 二進位檔。回傳 entry 數，失敗回 0。
    fun compilePairs(
        pairs: List<Pair<String, String>>,
        dst: String,
        cname: String = "嘸蝦米",
        selkeys: String = "0123456789",
    ): Int {
        data class Entry(val code: String, val chars: MutableList<String>)
        val entries = mutableListOf<Entry>()
        val codeMap = HashMap<String, Int>()
        for ((code, ch) in pairs) {
            if (code.isEmpty() || ch.isEmpty()) continue
            val idx = codeMap[code]
            if (idx != null) {
                val chars = entries[idx].chars
                if (ch !in chars) chars.add(ch)
            } else {
                codeMap[code] = entries.size
                entries.add(Entry(code, mutableListOf(ch)))
            }
        }

        // 依碼排序（ASCII 序）
        entries.sortBy { it.code }
        if (entries.isEmpty()) return 0

        // strings 區
        val stringsBuf = ByteArrayOutputStream()
        val codeEntries = ArrayList<Pair<Int, Int>>(entries.size)  // (off, len)
        for (e in entries) {
            val b = e.code.toByteArray(Charsets.US_ASCII)
            codeEntries.add(stringsBuf.size() to b.size)
            stringsBuf.write(b)
        }

        // chars 區（UTF-32LE code points；cnt = scalar 數）
        val charsBuf = ByteArrayOutputStream()
        val valEntries = ArrayList<Pair<Int, Int>>(entries.size)  // (off, cnt)
        for (e in entries) {
            val off = charsBuf.size() / 4
            var cnt = 0
            for (ch in e.chars) {
                var i = 0
                while (i < ch.length) {
                    val cp = ch.codePointAt(i)
                    writeU32(charsBuf, cp.toLong())
                    cnt += 1
                    i += Character.charCount(cp)
                }
            }
            valEntries.add(off to cnt)
        }

        // Header（128 bytes）
        val headerSize = 128
        val header = ByteArray(headerSize)
        header[0] = 0x43; header[1] = 0x49; header[2] = 0x4E; header[3] = 0x4D  // "CINM"
        putU32(header, 4, entries.size.toLong())
        val skData = selkeys.toByteArray(Charsets.US_ASCII)
        val skLen = minOf(skData.size, 10)
        header[8] = skLen.toByte()
        System.arraycopy(skData, 0, header, 9, skLen)
        val cnData = cname.toByteArray(Charsets.UTF_8).let { if (it.size > 64) it.copyOf(64) else it }
        putU16(header, 20, cnData.size)
        System.arraycopy(cnData, 0, header, 22, cnData.size)

        // Code index（6-byte stride：u32 off + u16 len）
        val codeIdx = ByteArrayOutputStream()
        for ((off, len) in codeEntries) {
            writeU32(codeIdx, off.toLong())
            writeU16(codeIdx, len)
        }

        // Val index（4-byte stride：u24 off（低 16 位 + 第 4 byte 為高 8 位）+ u8 cnt）
        //
        // 第 4 byte 原本恆為 0（保留），offset 只有 u16 — 但真正的嘸蝦米字表 chars 區
        // 遠超過 65,535 個 code point，超出的 entry 全被寫成 0xFFFF，讀出來是別的字。
        // 改把保留 byte 當高位元組（上限 16,777,215）：舊檔該 byte 為 0 → 讀值不變，
        // 新舊讀取器對「原本就存得下」的檔案完全一致。
        val valIdx = ByteArrayOutputStream()
        for ((off, cnt) in valEntries) {
            if (off > 0xFFFFFF) return 0  // 超出格式上限 — 寧可失敗，不要靜靜編出錯字表
            writeU16(valIdx, off and 0xFFFF)
            valIdx.write(minOf(cnt, 255))
            valIdx.write((off shr 16) and 0xFF)
        }

        // 區段位移
        val codesOff = headerSize
        val valsOff = codesOff + codeIdx.size()
        val stringsOff = valsOff + valIdx.size()
        val charsOff = stringsOff + stringsBuf.size()
        putU32(header, 88, FORMAT_VERSION.toLong())
        putU32(header, 96, codesOff.toLong())
        putU32(header, 100, valsOff.toLong())
        putU32(header, 104, stringsOff.toLong())
        putU32(header, 108, charsOff.toLong())

        return try {
            File(dst).outputStream().use { out ->
                out.write(header)
                codeIdx.writeTo(out)
                valIdx.writeTo(out)
                stringsBuf.writeTo(out)
                charsBuf.writeTo(out)
            }
            entries.size
        } catch (e: Exception) {
            0
        }
    }

    private fun getU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
