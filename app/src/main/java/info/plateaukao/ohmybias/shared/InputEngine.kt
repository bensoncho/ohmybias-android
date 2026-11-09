package info.plateaukao.ohmybias.shared

/// 平台無關輸入引擎（自 iOS Shared/InputEngine.swift 移植）。
/// 鍵盤 service 呼叫這裡的方法；引擎經 delegate 回呼。
interface InputEngineDelegate {
    fun engineDidUpdateComposing(text: String)
    fun engineDidUpdateCandidates(candidates: List<String>)
    fun engineDidCommit(text: String)
    fun engineDidCommitPair(left: String, right: String)
    fun engineDidClearComposing()
    fun engineDidShowToast(text: String)
    fun engineDidShowCodeHint(text: String, duration: Double)
    fun engineDidDeleteBack()
    fun engineDidSuggest(suggestions: List<String>)
    fun engineDidPasteText(text: String)
}

/// 取尾端 n 個 code point 的子字串
private fun String.cpTakeLast(n: Int): String {
    val total = codePointCount(0, length)
    if (total <= n) return this
    return substring(offsetByCodePoints(0, total - n))
}

private fun String.cpCount(): Int = codePointCount(0, length)

class InputEngine(
    cinTable: CINTable? = null,
    pinnedOrder: PinnedOrder? = null,
    private val zhuyinLookup: ZhuyinLookup = ZhuyinLookup.shared,
    private val suggestionEngine: SuggestionEngine = SuggestionEngine.shared,
    wikiCorpus: WikiCorpus = WikiCorpus.shared,
    private val prefs: IMEPreferences = DefaultPreferences,
) {
    var delegate: InputEngineDelegate? = null

    val cinTable: CINTable = cinTable ?: CINTable()
    val pinnedOrder: PinnedOrder = pinnedOrder ?: PinnedOrder.shared
    private val ranker = CandidateRanker(wikiCorpus, prefs)
    private val lock = Any()

    private inline fun <T> sync(body: () -> T): T = synchronized(lock) { body() }

    // MARK: - State

    private var _composing = ""
    private var _currentCandidates: List<String> = emptyList()
    private var _isWildcard = false
    private var _isEnglishMode = false
    private var _lastCommitted = ""
    val lastCommittedText: String get() = _lastCommitted
    private var _prevCommitted = ""
    private var _recentCommitted = ""
    private var _eatNextSpace = false

    // 長按 undo 快照
    private var _snapComposing = ""
    private var _snapCandidates: List<String> = emptyList()
    private var _snapIsWildcard = false

    // 同音字
    private var _isSameSoundMode = false
    private var _sameSoundBase = ""

    // 注音查碼
    private var _isZhuyinMode = false
    private var _zhuyinBuffer = ""
    private var _zyInitial = ""; private var _zyMedial = ""; private var _zyFinal = ""

    // 拼音查碼
    private var _isPinyinMode = false
    private var _pinyinSimplified = true
    private var _pinyinBuffer = ""

    // Pin 模式：使用者選字固定排序
    private var _isPinMode = false
    private var _pinCode = ""
    private var _pinPicked: MutableList<String> = mutableListOf()

    // ,, 指令
    private var _commaCommandBuffer = ""
    private var _isInCommaCommand = false

    // 輸入模式
    enum class InputMode(val raw: String) {
        T("t"), S("s"), SP("sp"), SL("sl"), TS("ts"), ST("st"), J("j");
    }
    private var _inputMode = InputMode.T

    companion object {
        val modeLabels: Map<InputMode, String> = mapOf(
            InputMode.T to "繁中", InputMode.S to "簡中", InputMode.SP to "速", InputMode.SL to "慢",
            InputMode.TS to "繁→簡", InputMode.ST to "簡→繁", InputMode.J to "日",
        )

        private val modeMap: Map<String, InputMode> = mapOf(
            "t" to InputMode.T, "s" to InputMode.S, "sp" to InputMode.SP, "sl" to InputMode.SL,
            "ts" to InputMode.TS, "st" to InputMode.ST, "j" to InputMode.J,
        )

        private val zyInitials: Set<String> = setOf(
            "ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ",
            "ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ",
        )
        private val zyMedials: Set<String> = setOf("ㄧ", "ㄨ", "ㄩ")
        private val zyFinals: Set<String> = setOf(
            "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ",
        )

        private val punctuationPairs: Map<String, String> = mapOf(
            "「" to "」", "（" to "）", "『" to "』", "【" to "】", "《" to "》", "〈" to "〉",
        )

        private val sentenceEnders: Set<String> = setOf("。", "！", "？", ".", "!", "?", "\n", "；", ";")
    }

    // MARK: - Thread-safe public accessors

    val composing: String get() = sync { _composing }
    val currentCandidates: List<String> get() = sync { _currentCandidates }
    val isEnglishMode: Boolean get() = sync { _isEnglishMode }
    val isZhuyinMode: Boolean get() = sync { _isZhuyinMode }
    val isPinyinMode: Boolean get() = sync { _isPinyinMode }
    val isSameSoundMode: Boolean get() = sync { _isSameSoundMode }
    val isInSpecialMode: Boolean get() = sync { _isZhuyinMode || _isPinyinMode || _isSameSoundMode }
    val inputMode: InputMode get() = sync { _inputMode }
    val selKeys: List<Char> get() = cinTable.selKeys
    val currentModeLabel: String get() = sync { if (_isEnglishMode) "A" else (modeLabels[_inputMode] ?: "繁中") }
    val currentModeName: String get() = sync { currentModeNameImpl }

    fun clearCandidates() = sync { _currentCandidates = emptyList() }
    fun setCandidates(c: List<String>) = sync { _currentCandidates = c }

    /// 成對標點的右半，沒有配對則回 null。符號鍵（符號頁/符號面板/上下滑）是直接
    /// 送字、不經組字流程的，仍要補右半 —— 否則同一個「【」用打碼打會配對、
    /// 用符號鍵按就不會，行為不一致。
    fun pairedRight(text: String): String? =
        if (prefs.punctuationPairing && text.cpCount() == 1) punctuationPairs[text] else null

    /// 唯一候選自動送出後，使用者常會習慣性再按一下空白 —— 那一下要吃掉，不多出空格。
    /// 組字為空的空白鍵不會進 handleSpace（service 直接上屏），故由 service 先問這裡。
    fun consumeEatNextSpace(): Boolean = sync {
        if (!_eatNextSpace) return@sync false
        _eatNextSpace = false
        true
    }

    /// 換輸入框／結束輸入時清空整個組字階段狀態。未送出的組字碼、候選與查詢模式旗標
    /// 都不能跨欄位存活 —— 否則在新欄位按的第一個空白會把上一個欄位遺留的候選字送出去，
    /// 中英模式與固定排序不受影響。
    /// 不發 toast／不回呼 delegate：此時鍵盤正在收起，UI 由 service 自己刷新。
    fun resetSession(): Unit = sync {
        _isPinMode = false; _pinCode = ""; _pinPicked = mutableListOf()
        _isInCommaCommand = false; _commaCommandBuffer = ""
        _isZhuyinMode = false; clearZhuyinSlots()
        _isPinyinMode = false; _pinyinBuffer = ""
        _isSameSoundMode = false; _sameSoundBase = ""
        _composing = ""; _currentCandidates = emptyList(); _isWildcard = false
        _eatNextSpace = false
        _snapComposing = ""; _snapCandidates = emptyList(); _snapIsWildcard = false
        // 語境（bigram/trigram/聯想）也屬於上一個欄位
        _lastCommitted = ""; _prevCommitted = ""; _recentCommitted = ""
    }

    private val currentModeNameImpl: String
        get() {
            if (_isZhuyinMode) return "zh"
            if (_isSameSoundMode) return "to"
            return _inputMode.raw
        }

    private val currentModeLabelImpl: String
        get() = if (_isEnglishMode) "A" else (modeLabels[_inputMode] ?: "繁中")

    // MARK: - Init

    fun loadTable() = cinTable.reload()

    // MARK: - Public API（由鍵盤 service 呼叫）

    fun handleLetter(char: String) = sync {
        _snapComposing = _composing; _snapCandidates = _currentCandidates; _snapIsWildcard = _isWildcard
        // 又打了字 → 上一次自動送出後要吃掉的那個空白已經不適用
        _eatNextSpace = false

        // Pin 模式：字母鍵累積要固定的碼
        if (_isPinMode) {
            _pinCode += char; _composing = "PIN:$_pinCode"
            _currentCandidates = cinTable.lookup(_pinCode, _inputMode)
            notifyComposing(); notifyCandidates(); return@sync
        }

        // 同音字模式：直接打碼（無 ' 前綴）
        if (_isSameSoundMode && _composing.isEmpty() && _sameSoundBase.isEmpty()) {
            if (char == ",") {
                // 不攔截逗號 — 交給 ,, 指令系統
            } else if ((char >= "a" && char <= "z") || char == "*") {
                _composing = char
                refreshCandidates()
                notifyComposing(); notifyCandidates(); return@sync
            }
        }

        // ,, 指令：第二個逗號
        if (_composing == "," && char == ",") {
            _isInCommaCommand = true; _commaCommandBuffer = ""; _composing = ",,"
            notifyComposing(); return@sync
        }

        // ,, 指令：收集中
        if (_isInCommaCommand) {
            _commaCommandBuffer += char; _composing = ",,$_commaCommandBuffer"
            notifyComposing(); return@sync
        }

        val newComposing = _composing + char
        val maxLen = cinTable.maxCodeLength

        if (newComposing.length > maxLen) {
            // 頂字上屏（偏好，預設關）：滿碼且有候選時，下一鍵送出首選、開始下一字。
            // 關閉時走英文直通 — 續打不清除，空白鍵原樣送出
            if (prefs.overflowAutoCommit && _currentCandidates.isNotEmpty()) {
                commitText(_currentCandidates[0])
                _composing = char; _isWildcard = false
            } else {
                _composing = newComposing
            }
        } else {
            _composing = newComposing
        }

        refreshCandidates()

        if (prefs.autoCommit &&
            _currentCandidates.size == 1 && _composing.length >= 2 && !canExtendCode(_composing)
        ) {
            commitText(_currentCandidates[0]); _eatNextSpace = true; return@sync
        }

        notifyComposing(); notifyCandidates()
    }

    fun handleSpace(): Unit = sync {
        if (_composing.isEmpty()) return@sync
        // Pin 模式：空白確認固定順序
        if (_isPinMode) {
            if (_pinCode.isNotEmpty() && _pinPicked.isNotEmpty()) {
                pinnedOrder.pin(_pinCode, _pinPicked.toList())
                delegate?.engineDidShowToast("已固定 $_pinCode → ${_pinPicked.joinToString("")}")
            } else if (_pinCode.isNotEmpty() && _pinPicked.isEmpty()) {
                // 尚未選字 — 視為「顯示候選」
                notifyCandidates(); return@sync
            }
            _isPinMode = false; _pinCode = ""; _pinPicked = mutableListOf()
            resetComposing(); _currentCandidates = emptyList(); notifyCandidates()
            delegate?.engineDidClearComposing(); return@sync
        }
        if (_isInCommaCommand) {
            if (_commaCommandBuffer.isEmpty()) {
                _isInCommaCommand = false; resetComposing()
                delegate?.engineDidCommit("　"); return@sync
            }
            dispatchCommaCommand(); return@sync
        }
        if (_currentCandidates.isEmpty()) {
            // 英文直通：無候選時空白鍵把打的字串原樣送出 —
            // 空白鍵本身也要上屏，如同英文模式打字尾隨空格
            val raw = _composing
            resetComposing()
            delegate?.engineDidCommit("$raw ")
            return@sync
        }
        commitText(_currentCandidates[0])
    }

    /// 點候選列左側的組字碼 — 字母原樣上屏（英文直通的手動版：有候選但使用者
    /// 要英文單字時用；不帶尾隨空格）
    fun commitComposingRaw(): Unit = sync {
        if (_composing.isEmpty() || _isPinMode || _isInCommaCommand ||
            _isZhuyinMode || _isPinyinMode || _isSameSoundMode
        ) return@sync
        val raw = _composing
        resetComposing()
        delegate?.engineDidCommit(raw)
    }

    fun handleBackspace(): Unit = sync {
        // Pin 模式：退格移除最後選字、或最後碼、或退出
        if (_isPinMode) {
            if (_pinPicked.isNotEmpty()) {
                _pinPicked.removeAt(_pinPicked.size - 1)
                _composing = "PIN:$_pinCode" + if (_pinPicked.isEmpty()) "" else " → ${_pinPicked.joinToString("")}"
                notifyComposing()
            } else if (_pinCode.isNotEmpty()) {
                _pinCode = _pinCode.dropLast(1)
                if (_pinCode.isEmpty()) {
                    _isPinMode = false; resetComposing()
                    delegate?.engineDidClearComposing()
                } else {
                    _composing = "PIN:$_pinCode"
                    _currentCandidates = cinTable.lookup(_pinCode, _inputMode)
                    notifyComposing(); notifyCandidates()
                }
            } else {
                _isPinMode = false; resetComposing()
                delegate?.engineDidClearComposing()
            }
            return@sync
        }
        if (_isInCommaCommand) {
            if (_commaCommandBuffer.isEmpty()) {
                _isInCommaCommand = false; _composing = ","
                notifyComposing()
            } else {
                _commaCommandBuffer = _commaCommandBuffer.dropLast(1)
                _composing = ",,$_commaCommandBuffer"; notifyComposing()
            }
            return@sync
        }
        if (_isZhuyinMode) {
            if (_currentCandidates.isEmpty() && _zhuyinBuffer.isNotEmpty()) {
                backspaceZhuyin()
                if (_zhuyinBuffer.isEmpty()) delegate?.engineDidClearComposing()
                else delegate?.engineDidUpdateComposing(_zhuyinBuffer)
            } else if (_currentCandidates.isNotEmpty()) {
                _currentCandidates = emptyList(); notifyCandidates()
                delegate?.engineDidUpdateComposing(_zhuyinBuffer)
            }
            return@sync
        }
        if (_composing.isEmpty()) {
            if (_recentCommitted.isNotEmpty()) _recentCommitted = _recentCommitted.dropLast(1)
            if (_currentCandidates.isNotEmpty()) { _currentCandidates = emptyList(); notifyCandidates() }
            delegate?.engineDidDeleteBack()
            return@sync
        }
        _composing = _composing.dropLast(1)
        if (_composing.isEmpty()) resetComposing()
        else {
            _isWildcard = _composing.contains("*")
            refreshCandidates(); notifyComposing(); notifyCandidates()
        }
    }

    fun handleEnter(): Unit = sync {
        if (_isInCommaCommand) { dispatchCommaCommand(); return@sync }
        if (_isSameSoundMode && _sameSoundBase.isNotEmpty()) {
            // 同音字：Enter 收掉同音候選、留在模式中
            _sameSoundBase = ""; _composing = ""; _currentCandidates = emptyList()
            delegate?.engineDidClearComposing(); notifyCandidates()
            return@sync
        }
        if (_composing.isEmpty()) return@sync
        commitText(_composing)
    }

    fun handleEscape(): Unit = sync {
        val wasSpecial = _isZhuyinMode || _isPinyinMode || _isSameSoundMode
        if (_isPinMode) { _isPinMode = false; _pinCode = ""; _pinPicked = mutableListOf() }
        if (_isInCommaCommand) { _isInCommaCommand = false; _commaCommandBuffer = "" }
        if (_isZhuyinMode) { _isZhuyinMode = false; clearZhuyinSlots() }
        if (_isPinyinMode) { _isPinyinMode = false; _pinyinBuffer = "" }
        _isSameSoundMode = false
        _currentCandidates = emptyList(); notifyCandidates()
        resetComposing()
        if (wasSpecial) delegate?.engineDidShowToast(currentModeLabelImpl)
    }

    fun handleWildcard(): Unit = sync {
        if (_composing.isEmpty()) return@sync
        _composing += "*"; _isWildcard = true
        _currentCandidates = cinTable.wildcardLookup(_composing, _inputMode)
        notifyComposing(); notifyCandidates()
    }

    /// 撤銷上一次 handleLetter（長按數字用）
    fun undoLastLetter(): Unit = sync {
        if (_composing != _snapComposing && _snapComposing.length < _composing.length) {
            // 一般情況：只是加了一個字母
            handleBackspaceImpl()
        } else if (_composing.length == 1 && _snapComposing.isEmpty()) {
            // 加了第一個字母
            handleBackspaceImpl()
        } else {
            // autoCommit 或溢位發生 — 還原快照並撤銷送字
            delegate?.engineDidDeleteBack()
            _composing = _snapComposing; _currentCandidates = _snapCandidates; _isWildcard = _snapIsWildcard
            notifyComposing(); notifyCandidates()
        }
    }

    fun selectCandidate(at: Int): Unit = sync {
        if (at >= _currentCandidates.size) return@sync
        // Pin 模式：把候選加進固定清單
        if (_isPinMode && _pinCode.isNotEmpty()) {
            val ch = _currentCandidates[at]
            if (ch !in _pinPicked) {
                _pinPicked.add(ch)
                _composing = "PIN:$_pinCode → ${_pinPicked.joinToString("")}"
                notifyComposing()
            }
            return@sync
        }
        if (_isZhuyinMode) {
            val full = _currentCandidates[at]
            val char = full.substring(0, full.offsetByCodePoints(0, 1))
            val codes = cinTable.reverseLookup(char)
            commitText(char)
            if (codes.isNotEmpty()) delegate?.engineDidShowCodeHint("$char → ${codes.joinToString(" / ")}", 3.0)
            clearZhuyinSlots(); _currentCandidates = emptyList(); notifyCandidates()
            // 送字後自動退出注音
            exitZhuyinModeImpl()
        } else if (_isSameSoundMode && _sameSoundBase.isNotEmpty()) {
            // 同音字第 2 步：使用者選了同音字
            val char = _currentCandidates[at]
            val codes = cinTable.reverseLookup(char)
            delegate?.engineDidCommit(char)
            if (codes.isNotEmpty()) delegate?.engineDidShowCodeHint("$char → ${codes.joinToString(" / ")}", 3.0)
            _sameSoundBase = ""; _composing = ""; _currentCandidates = emptyList()
            delegate?.engineDidClearComposing(); notifyCandidates()
        } else {
            // （composing 為空 = 聯想詞直接送出；與一般選字同路徑）
            commitText(_currentCandidates[at])
        }
    }

    /// VRSF 快選：處理則回 true
    fun handleVRSF(char: String): Boolean = sync {
        val map = listOf("v" to 1, "r" to 2, "s" to 3, "f" to 4)
        for ((letter, idx) in map) {
            if (char == letter && _currentCandidates.size > idx && !cinTable.hasPrefix(_composing + letter, _inputMode)) {
                commitText(_currentCandidates[idx]); return@sync true
            }
        }
        false
    }

    fun selectByDigit(digit: Int): Boolean = sync {
        if (_currentCandidates.isEmpty()) return@sync false
        val keys = cinTable.selKeys
        if (digit >= keys.size) return@sync false
        if (digit >= _currentCandidates.size) return@sync false
        selectCandidateImpl(digit)
        true
    }

    /// 與 iOS 版同步：不顯示切換 toast — 工具列米/英鍵與第三排 英/⇧ 鍵已反映狀態
    fun toggleEnglishMode(): Unit = sync {
        _isEnglishMode = !_isEnglishMode
        resetComposing()
    }

    /// 直接設定中英模式（啟動時還原上次狀態用；不顯示 toast）
    fun setEnglishMode(on: Boolean): Unit = sync {
        if (_isEnglishMode == on) return@sync
        _isEnglishMode = on
        resetComposing()
    }

    fun exitZhuyinMode(): Unit = sync { exitZhuyinModeImpl() }

    private fun exitZhuyinModeImpl() {
        if (!_isZhuyinMode) return
        _isZhuyinMode = false
        clearZhuyinSlots(); _currentCandidates = emptyList(); notifyCandidates()
        delegate?.engineDidShowToast(currentModeLabelImpl)
    }

    // MARK: - 拼音查碼

    fun exitPinyinMode(): Unit = sync {
        _isPinyinMode = false; _pinyinBuffer = ""
        _currentCandidates = emptyList(); notifyCandidates()
        delegate?.engineDidClearComposing()
    }

    fun handlePinyinLetter(ch: String): Unit = sync {
        if (!_isPinyinMode) return@sync
        _pinyinBuffer += ch
        _composing = _pinyinBuffer
        notifyComposing()
    }

    fun handlePinyinTone(tone: Int): Unit = sync { handlePinyinToneImpl(tone) }

    fun handlePinyinSpace(): Unit = sync {
        if (!_isPinyinMode) return@sync
        if (_pinyinBuffer.isNotEmpty()) handlePinyinToneImpl(1)
    }

    fun handlePinyinBackspace(): Unit = sync {
        if (!_isPinyinMode) return@sync
        if (_currentCandidates.isNotEmpty()) {
            _currentCandidates = emptyList(); notifyCandidates()
            _composing = _pinyinBuffer; notifyComposing()
        } else if (_pinyinBuffer.isNotEmpty()) {
            _pinyinBuffer = _pinyinBuffer.dropLast(1)
            _composing = _pinyinBuffer; notifyComposing()
            if (_pinyinBuffer.isEmpty()) delegate?.engineDidClearComposing()
        }
    }

    fun handlePinyinEscape(): Unit = sync {
        if (!_isPinyinMode) return@sync
        if (_pinyinBuffer.isNotEmpty() || _currentCandidates.isNotEmpty()) {
            _pinyinBuffer = ""; _currentCandidates = emptyList(); notifyCandidates()
            delegate?.engineDidClearComposing()
        } else {
            _isPinyinMode = false; _pinyinBuffer = ""
            _currentCandidates = emptyList(); notifyCandidates()
            delegate?.engineDidClearComposing()
            delegate?.engineDidShowToast(currentModeLabelImpl)
        }
    }

    fun selectPinyinCandidate(at: Int): Unit = sync {
        if (!_isPinyinMode || at >= _currentCandidates.size) return@sync
        val entry = _currentCandidates[at]
        val char = entry.substring(0, entry.offsetByCodePoints(0, 1))
        delegate?.engineDidCommit(char)
        val codes = cinTable.reverseLookup(char)
        if (codes.isNotEmpty()) delegate?.engineDidShowToast("$char → ${codes.joinToString(" / ")}")
        _pinyinBuffer = ""; _currentCandidates = emptyList(); notifyCandidates()
        delegate?.engineDidClearComposing()
    }

    // MARK: - 注音

    fun handleZhuyinSymbol(zy: String): Unit = sync {
        when {
            zy in zyInitials -> _zyInitial = zy
            zy in zyMedials -> _zyMedial = zy
            zy in zyFinals -> _zyFinal = zy
        }
        _zhuyinBuffer = _zyInitial + _zyMedial + _zyFinal
        delegate?.engineDidUpdateComposing(_zhuyinBuffer)
    }

    fun handleZhuyinTone(tone: String): Unit = sync {
        if (_zhuyinBuffer.isEmpty()) return@sync
        val zhuyin = if (tone == "˙") "˙$_zhuyinBuffer" else _zhuyinBuffer + tone
        zhuyinLookupImpl(zhuyin)
    }

    fun handleZhuyinSpace(): Unit = sync {
        if (_zhuyinBuffer.isEmpty()) return@sync
        zhuyinLookupImpl(_zhuyinBuffer)  // 一聲
    }

    private fun zhuyinLookupImpl(zhuyin: String) {
        val raw = zhuyinLookup.charsForZhuyin(zhuyin)
        if (raw.isEmpty()) return
        val chars = zhuyinLookup.sortByFreq(raw, _prevCommitted, zhuyin)
        _currentCandidates = chars.map { char ->
            val codes = cinTable.reverseLookup(char)
            if (codes.isEmpty()) char else "$char ${codes.joinToString("/")}"
        }
        delegate?.engineDidUpdateComposing(zhuyin)
        notifyCandidates()
    }

    private fun clearZhuyinSlots() {
        _zyInitial = ""; _zyMedial = ""; _zyFinal = ""; _zhuyinBuffer = ""
    }

    private fun backspaceZhuyin() {
        when {
            _zyFinal.isNotEmpty() -> _zyFinal = ""
            _zyMedial.isNotEmpty() -> _zyMedial = ""
            else -> _zyInitial = ""
        }
        _zhuyinBuffer = _zyInitial + _zyMedial + _zyFinal
    }

    // MARK: - 同音字

    private fun handleSameSound() {
        val results = zhuyinLookup.lookup(_sameSoundBase)
        val first = results.firstOrNull() ?: run { resetComposing(); return }
        _currentCandidates = zhuyinLookup.sortByFreq(first.chars)
        _composing = _sameSoundBase
        delegate?.engineDidUpdateComposing("$_sameSoundBase[${first.zhuyin}]")
        notifyCandidates()
    }

    // MARK: - ,, 指令

    private fun dispatchCommaCommand() {
        val cmd = _commaCommandBuffer.lowercase()
        _isInCommaCommand = false; _commaCommandBuffer = ""
        resetComposing()

        if (cmd == "rl") { cinTable.reload(); UserPhrases.shared.reload(); delegate?.engineDidShowToast("字表已重載"); return }
        if (cmd == "pin") {
            _isZhuyinMode = false; clearZhuyinSlots()
            _isSameSoundMode = false; _sameSoundBase = ""
            _isPinyinMode = false; _pinyinBuffer = ""
            _isPinMode = true; _pinCode = ""; _pinPicked = mutableListOf()
            _composing = "PIN:"; _currentCandidates = emptyList()
            notifyComposing(); notifyCandidates()
            delegate?.engineDidShowToast("固定排序：輸入碼→選字→空白確認")
            return
        }
        if (cmd.startsWith("unpin")) {
            val arg = cmd.substring(5)  // 如 "unpina" → "a"
            when {
                arg.isEmpty() -> delegate?.engineDidShowToast("用法：,,UNPIN + 碼（如 ,,UNPINa）")
                pinnedOrder.chars(arg) != null -> {
                    pinnedOrder.unpin(arg)
                    delegate?.engineDidShowToast("已解除 $arg 的固定排序")
                }
                else -> delegate?.engineDidShowToast("$arg 無固定排序")
            }
            return
        }
        if (cmd == "sg") {
            val on = !DefaultPreferences.suggestEnabled
            DefaultPreferences.setSuggestEnabled(on)
            delegate?.engineDidShowToast(if (on) "聯想 ON" else "聯想 OFF"); return
        }
        // ── 剪貼簿處理 ,,v 系列 ──
        if (cmd == "v" || cmd == "vt" || cmd == "vs") {
            val text = ClipboardBridge.plainText()
            if (text.isNullOrEmpty()) {
                delegate?.engineDidShowToast("剪貼簿為空"); return
            }
            val result = when (cmd) {
                "vt" -> ClipboardBridge.toTraditional(text)
                "vs" -> ClipboardBridge.toSimplified(text)
                else -> text
            }
            delegate?.engineDidPasteText(result); return
        }
        if (cmd == "c") { delegate?.engineDidShowToast(currentModeLabelImpl); return }
        if (cmd == "zh") {
            _isZhuyinMode = !_isZhuyinMode
            if (_isZhuyinMode) {
                _isSameSoundMode = false; _sameSoundBase = ""
                _isPinyinMode = false; _pinyinBuffer = ""
            }
            delegate?.engineDidShowToast(if (_isZhuyinMode) "注" else currentModeLabelImpl)
            if (!_isZhuyinMode) { clearZhuyinSlots(); _currentCandidates = emptyList(); notifyCandidates() }
            return
        }
        if (cmd == "pys" || cmd == "pyt") {
            val entering = !_isPinyinMode || (cmd == "pys") != _pinyinSimplified
            if (entering) {
                _isPinyinMode = true; _pinyinSimplified = (cmd == "pys")
                _isZhuyinMode = false; clearZhuyinSlots()
                _isSameSoundMode = false; _sameSoundBase = ""
                _pinyinBuffer = ""; _currentCandidates = emptyList(); notifyCandidates()
                delegate?.engineDidShowToast(if (cmd == "pys") "拼簡" else "拼繁")
            } else {
                _isPinyinMode = false; _pinyinBuffer = ""
                _currentCandidates = emptyList(); notifyCandidates()
                delegate?.engineDidClearComposing()
                delegate?.engineDidShowToast(currentModeLabelImpl)
            }
            return
        }
        if (cmd == "to") {
            _isSameSoundMode = !_isSameSoundMode
            if (_isSameSoundMode) {
                _isZhuyinMode = false; clearZhuyinSlots()
                _isPinyinMode = false; _pinyinBuffer = ""
                _sameSoundBase = ""; _composing = ""
                delegate?.engineDidClearComposing()
                delegate?.engineDidShowToast("同音字模式：打碼送字後列同音字")
            } else {
                _sameSoundBase = ""; _composing = ""
                delegate?.engineDidClearComposing()
                _currentCandidates = emptyList(); notifyCandidates()
                delegate?.engineDidShowToast(currentModeLabelImpl)
            }
            return
        }
        val mode = modeMap[cmd] ?: run {
            delegate?.engineDidShowToast("未知命令 ,,${cmd.uppercase()}\n完整說明見設定頁"); return
        }
        // 清除所有查詢模式旗標
        _isZhuyinMode = false; clearZhuyinSlots()
        _isSameSoundMode = false; _sameSoundBase = ""
        _isPinyinMode = false; _pinyinBuffer = ""
        _isPinMode = false; _pinCode = ""; _pinPicked = mutableListOf()
        _currentCandidates = emptyList(); notifyCandidates()
        _inputMode = mode
        delegate?.engineDidShowToast(modeLabels[mode] ?: "繁中")
    }

    /// 切換到指定模式（空白鍵滑動循環用）。回傳顯示標籤。
    fun switchToMode(name: String): String = sync {
        if (name == "zh") {
            if (!_isZhuyinMode) { _isZhuyinMode = true; clearZhuyinSlots() }
            _isSameSoundMode = false; _sameSoundBase = ""; _composing = ""
            delegate?.engineDidShowToast("注")
            return@sync "注"
        }
        if (name == "to") {
            if (!_isSameSoundMode) {
                _isSameSoundMode = true; _sameSoundBase = ""; _composing = ""
                delegate?.engineDidClearComposing()
            }
            if (_isZhuyinMode) exitZhuyinModeImpl()
            delegate?.engineDidShowToast("同音字模式")
            return@sync "同"
        }
        // 一般輸入模式
        if (_isZhuyinMode) { _isZhuyinMode = false; clearZhuyinSlots(); _currentCandidates = emptyList(); notifyCandidates() }
        if (_isSameSoundMode) { _isSameSoundMode = false; _sameSoundBase = ""; _composing = "" }
        val mode = modeMap[name] ?: return@sync ""
        _inputMode = mode
        val label = modeLabels[mode] ?: "繁中"
        delegate?.engineDidShowToast(label)
        label
    }

    // MARK: - 內部 impl（於 lock 內呼叫，不再上鎖）

    private fun handleBackspaceImpl() {
        if (_isInCommaCommand) {
            if (_commaCommandBuffer.isEmpty()) {
                _isInCommaCommand = false; _composing = ","
                notifyComposing()
            } else {
                _commaCommandBuffer = _commaCommandBuffer.dropLast(1)
                _composing = ",,$_commaCommandBuffer"; notifyComposing()
            }
            return
        }
        if (_isZhuyinMode) {
            if (_currentCandidates.isEmpty() && _zhuyinBuffer.isNotEmpty()) {
                backspaceZhuyin()
                if (_zhuyinBuffer.isEmpty()) delegate?.engineDidClearComposing()
                else delegate?.engineDidUpdateComposing(_zhuyinBuffer)
            } else if (_currentCandidates.isNotEmpty()) {
                _currentCandidates = emptyList(); notifyCandidates()
                delegate?.engineDidUpdateComposing(_zhuyinBuffer)
            }
            return
        }
        if (_composing.isEmpty()) return
        _composing = _composing.dropLast(1)
        if (_composing.isEmpty()) resetComposing()
        else {
            _isWildcard = _composing.contains("*")
            refreshCandidates(); notifyComposing(); notifyCandidates()
        }
    }

    private fun selectCandidateImpl(at: Int) {
        if (at >= _currentCandidates.size) return
        if (_isZhuyinMode) {
            val full = _currentCandidates[at]
            val char = full.substring(0, full.offsetByCodePoints(0, 1))
            val codes = cinTable.reverseLookup(char)
            commitText(char)
            if (codes.isNotEmpty()) delegate?.engineDidShowToast("$char → ${codes.joinToString(" / ")}")
            clearZhuyinSlots(); _currentCandidates = emptyList(); notifyCandidates()
            exitZhuyinModeImpl()
        } else if (_isSameSoundMode && _sameSoundBase.isNotEmpty()) {
            val char = _currentCandidates[at]
            val codes = cinTable.reverseLookup(char)
            delegate?.engineDidCommit(char)
            if (codes.isNotEmpty()) delegate?.engineDidShowToast("$char → ${codes.joinToString(" / ")}")
            _sameSoundBase = ""; _composing = ""; _currentCandidates = emptyList()
            delegate?.engineDidClearComposing(); notifyCandidates()
        } else {
            commitText(_currentCandidates[at])
        }
    }

    private fun handlePinyinToneImpl(tone: Int) {
        if (!_isPinyinMode || _pinyinBuffer.isEmpty()) return
        val pinyin = "$_pinyinBuffer$tone"
        val chars = zhuyinLookup.charsForPinyin(pinyin)
        if (chars.isEmpty()) return
        val display: List<String>
        if (_pinyinSimplified) {
            val t2s = cinTable.t2s
            val seen = HashSet<String>()
            display = chars.mapNotNull { c ->
                val s = t2s[c] ?: c
                if (seen.add(s)) s else null
            }
        } else {
            display = chars
        }
        _currentCandidates = display.map { c ->
            val codes = cinTable.reverseLookup(c)
            if (codes.isEmpty()) c else "$c ${codes.joinToString("/")}"
        }
        _composing = pinyin; notifyComposing(); notifyCandidates()
    }

    // MARK: - Internal

    private fun canExtendCode(code: String): Boolean = cinTable.validNextKeys(code, _inputMode).isNotEmpty()

    fun validNextKeys(): Set<Char> = sync {
        if (_composing.isEmpty()) return@sync emptySet()
        cinTable.validNextKeys(_composing, _inputMode)
    }

    private fun refreshCandidates() {
        val code = _composing
        if (_inputMode == InputMode.J) {
            val kana = cinTable.lookup("$code,", InputMode.J) + cinTable.lookup("$code.", InputMode.J)
            if (cinTable.hasModeTable(InputMode.J)) {
                val base = if (_isWildcard) cinTable.wildcardLookup(code, InputMode.J)
                           else cinTable.lookup(code, InputMode.J)
                val seen = HashSet<String>()
                val merged = ArrayList<String>(base.size + kana.size)
                for (ch in base) if (seen.add(ch)) merged.add(ch)
                for (ch in kana) if (seen.add(ch)) merged.add(ch)
                _currentCandidates = ranker.rank(merged, code, InputMode.J, cinTable, pinnedOrder)
            } else {
                _currentCandidates = kana
            }
            return
        }
        val raw = if (_isWildcard) cinTable.wildcardLookup(code, _inputMode)
                  else cinTable.lookup(code, _inputMode)
        _currentCandidates = ranker.rank(raw, code, _inputMode, cinTable, pinnedOrder)

        // 常用語自訂組字碼：排在字表候選之後（撞碼時不擠掉原本的字；獨佔碼時就是唯一候選，
        // 「唯一候選自動送出」開著會直接上屏）。不受固定排序影響 — 位置固定可預期。
        if (!_isWildcard) {
            val shortcuts = cinTable.shortcutLookup(code)
            if (shortcuts.isNotEmpty()) {
                _currentCandidates = _currentCandidates + shortcuts.filter { it !in _currentCandidates }
            }
        }

        // 模糊比對：無候選時嘗試相鄰鍵替換
        if (_currentCandidates.isEmpty() && !_isWildcard && code.length >= 2 && prefs.fuzzyMatch) {
            _currentCandidates = ranker.fuzzyLookup(code, cinTable, _inputMode)
        }
    }

    private fun commitText(text: String) {
        // 同音字第 1 步 → 第 2 步
        if (_isSameSoundMode && _sameSoundBase.isEmpty() && text.cpCount() == 1) {
            val results = zhuyinLookup.lookup(text)
            if (results.isNotEmpty()) {
                _sameSoundBase = text
                handleSameSound(); return
            }
        }

        // 標點配對（iOS 預設開；Android 沿用）
        val right = punctuationPairs[text]
        if (prefs.punctuationPairing && text.cpCount() == 1 && right != null) {
            delegate?.engineDidCommitPair(text, right)
        } else {
            delegate?.engineDidCommit(text)
        }
        // 領域語境追蹤
        ranker.updateDomainContext(text)

        _prevCommitted = _lastCommitted
        _lastCommitted = if (text.cpCount() == 1) text else text.cpTakeLast(1)

        // 追蹤最近送出的文字（trigram + NER 用）
        _recentCommitted += text
        if (_recentCommitted.cpCount() > 10) _recentCommitted = _recentCommitted.cpTakeLast(10)
        if (text.isNotEmpty() && text.cpTakeLast(1) in sentenceEnders) _recentCommitted = ""

        _composing = ""; _currentCandidates = emptyList()
        _isWildcard = false
        if (_isSameSoundMode) {
            // 留在同音字模式 — 重置以待下一字
            _sameSoundBase = ""; _composing = ""
            delegate?.engineDidClearComposing()
        } else {
            _sameSoundBase = ""
        }
        notifyCandidates()

        if (text.cpCount() == 1 && prefs.showCodeHint && MemoryBudget.canAfford(MemoryBudget.reverseTable)) {
            val codes = cinTable.reverseLookup(text)
            if (codes.isNotEmpty()) {
                val hint = "$text → ${codes.joinToString(" / ")}"
                val dur = if (_isSameSoundMode || _isZhuyinMode) 3.0 else 1.5
                delegate?.engineDidShowCodeHint(hint, dur)
            }
        }

        // 聯想
        if (prefs.suggestEnabled && !_isSameSoundMode && !_isZhuyinMode) {
            val results = suggestionEngine.suggest(_recentCommitted, text)
            if (results.isNotEmpty()) {
                delegate?.engineDidSuggest(results)
            }
        }
    }

    private fun resetComposing() {
        _composing = ""; _currentCandidates = emptyList(); _isWildcard = false
        _sameSoundBase = ""; _eatNextSpace = false
        _isInCommaCommand = false; _commaCommandBuffer = ""
        clearZhuyinSlots()
        delegate?.engineDidClearComposing()
        notifyCandidates()
    }

    /// 回傳候選的最短字根碼提示；與目前 composing 等長或更長時回 null。
    fun shortestCodeHint(forChar: String): String? = sync {
        val codes = cinTable.shortestCodesTable[forChar] ?: return@sync null
        val best = codes.minByOrNull { it.length } ?: return@sync null
        if (best.length < _composing.length) best else null
    }

    private fun notifyComposing() { delegate?.engineDidUpdateComposing(_composing) }
    private fun notifyCandidates() { delegate?.engineDidUpdateCandidates(_currentCandidates) }
}
