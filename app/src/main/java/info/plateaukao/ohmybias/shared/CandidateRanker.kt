package info.plateaukao.ohmybias.shared

/// 候選排序：固定排序前置、模式過濾、領域語境、模糊比對。
class CandidateRanker(
    private val wikiCorpus: WikiCorpus = WikiCorpus.shared,
    private val prefs: IMEPreferences = DefaultPreferences,
) {

    // MARK: - 領域語境追蹤（MoE-style）

    /// 本次 session 的領域命中計數：domain_key → 累積權重
    private val domainHits = HashMap<String, Int>()
    private val domainCache = HashMap<String, Boolean>()  // char → 是否領域相關

    /// 每次送字後更新領域語境
    fun updateDomainContext(text: String) {
        if (prefs.suggestStrategy != "domain") return
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            if (domainsFor(s)) domainHits["_all"] = (domainHits["_all"] ?: 0) + 1
            i += Character.charCount(cp)
        }
    }

    /// 候選的領域加分（0.0 ~ 1.0）
    fun domainBoost(forChar: String): Double {
        if (domainHits.isEmpty()) return 0.0
        if (!domainsFor(forChar)) return 0.0
        val total = domainHits.values.sum()
        if (total <= 0) return 0.0
        return (domainHits["_all"] ?: 0).toDouble() / total
    }

    fun resetDomainContext() = domainHits.clear()

    private fun domainsFor(char: String): Boolean {
        domainCache[char]?.let { return it }
        val hit = wikiCorpus.suggestDomainTerms(char, 1).isNotEmpty()
        domainCache[char] = hit
        return hit
    }

    // MARK: - 模式過濾 + 排序

    /// 依目前輸入模式排序與過濾候選。
    fun rank(
        raw: List<String>, code: String,
        mode: InputEngine.InputMode, cinTable: CINTable, pinnedOrder: PinnedOrder,
    ): List<String> {
        var candidates = pinnedOrder.apply(raw, code)

        when (mode) {
            InputEngine.InputMode.SP -> {
                val tbl = cinTable.shortestCodesTable
                candidates = candidates.filter { tbl[it]?.contains(code) == true }
            }
            InputEngine.InputMode.SL -> {
                val tbl = cinTable.longestCodesTable
                candidates = candidates.filter { tbl[it]?.contains(code) == true }
            }
            InputEngine.InputMode.TS -> {
                val seen = HashSet<String>()
                candidates = candidates.mapNotNull { ch ->
                    val s = cinTable.convert(ch, cinTable.t2s)
                    if (seen.add(s)) s else null
                }
            }
            InputEngine.InputMode.ST -> {
                val seen = HashSet<String>()
                candidates = candidates.mapNotNull { ch ->
                    val t = cinTable.convert(ch, cinTable.s2t)
                    if (seen.add(t)) t else null
                }
            }
            InputEngine.InputMode.S -> {
                if (!cinTable.hasModeTable(InputEngine.InputMode.S)) {
                    // 未載入獨立簡體表（liu-s.bin）時，主字表以繁體為首（如 qd: 體、wr: 測、ow: 選、tua: 樹），
                    // 直接將候選字經 t2s 轉為簡體並去重，保留主字表原本的第一順位（體→体、測→测、選→选、樹→树），
                    // 避免舊版 filter 直接把第一順位繁體字刪掉而讓次順位異義字（替、刘、响、杈）頂上來
                    val t2s = cinTable.t2s
                    val firstRaw = candidates.firstOrNull()
                    val firstWasTrad = firstRaw != null && t2s[firstRaw] != null && t2s[firstRaw] != firstRaw
                    val seen = HashSet<String>()
                    candidates = candidates.mapNotNull { ch ->
                        val s = cinTable.convert(ch, t2s)
                        if (seen.add(s)) s else null
                    }
                    // 若原碼首字並非繁體字（例如單一 .cin 把繁簡共用字「替」排在簡體專用字「体」前面），則將簡體專用字提前
                    if (!firstWasTrad && candidates.size > 1) {
                        val s2t = cinTable.s2t
                        if (s2t.isNotEmpty()) {
                            val simpOnly = ArrayList<String>(candidates.size)
                            val shared = ArrayList<String>(candidates.size)
                            for (ch in candidates) {
                                val t = s2t[ch]
                                if (t != null && t != ch) simpOnly.add(ch) else shared.add(ch)
                            }
                            if (simpOnly.isNotEmpty() && shared.isNotEmpty()) {
                                candidates = simpOnly + shared
                            }
                        }
                    }
                } else if (!cinTable.hasModeTable(InputEngine.InputMode.TS)) {
                    // 僅匯入 liu-uni2.tab（無 liu-uni3.tab）時，尾端可能帶主表繁體 fallback，轉簡去重
                    val seen = HashSet<String>()
                    candidates = candidates.mapNotNull { ch ->
                        val s = cinTable.convert(ch, cinTable.t2s)
                        if (seen.add(s)) s else null
                    }
                }
            }
            InputEngine.InputMode.T, InputEngine.InputMode.J -> {}
        }

        // 地區用詞：對側地區詞降到最後
        if (candidates.size > 1) {
            val demoted = candidates.filter { wikiCorpus.isRegionDemoted(it) }
            if (demoted.isNotEmpty()) {
                candidates = candidates.filter { !wikiCorpus.isRegionDemoted(it) } + demoted
            }
        }

        return candidates
    }

    // MARK: - 相鄰鍵模糊比對

    companion object {
        private val adjacentKeys: Map<Char, List<Char>> = run {
            val rows = listOf(
                "qwertyuiop".toList(),
                "asdfghjkl".toList(),
                "zxcvbnm".toList(),
            )
            val map = HashMap<Char, List<Char>>()
            for ((r, row) in rows.withIndex()) {
                for ((c, ch) in row.withIndex()) {
                    val adj = ArrayList<Char>()
                    for (dc in listOf(-1, 1)) {
                        val nc = c + dc
                        if (nc in row.indices) adj.add(row[nc])
                    }
                    for (dr in listOf(-1, 1)) {
                        val nr = r + dr
                        if (nr !in rows.indices) continue
                        val offsets = if (dr == -1) listOf(c - 1, c)
                                      else if (r == 0) listOf(c, c + 1) else listOf(c - 1, c)
                        for (nc in offsets) {
                            if (nc in rows[nr].indices) adj.add(rows[nr][nc])
                        }
                    }
                    map[ch] = adj
                }
            }
            map
        }
    }

    fun fuzzyLookup(
        code: String,
        cinTable: CINTable,
        mode: InputEngine.InputMode = InputEngine.InputMode.T,
    ): List<String> {
        val seen = HashSet<String>()
        val results = ArrayList<String>()
        val chars = code.toCharArray()
        for (i in chars.indices) {
            val neighbors = adjacentKeys[chars[i]] ?: continue
            for (neighbor in neighbors) {
                val variant = chars.copyOf()
                variant[i] = neighbor
                for (ch in cinTable.lookup(String(variant), mode)) {
                    if (seen.add(ch)) results.add(ch)
                }
            }
        }
        return results.take(20)
    }
}
