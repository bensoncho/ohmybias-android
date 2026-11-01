package info.plateaukao.ohmybias.android

import android.content.Context
import android.content.SharedPreferences
import info.plateaukao.ohmybias.shared.DefaultPreferences
import info.plateaukao.ohmybias.shared.IMEPreferences
import info.plateaukao.ohmybias.shared.SkinSettings

/// 偏好設定 — SharedPreferences（單一 APK，IME 與設定頁直接共用；對應 iOS App Group UserDefaults）。
object Prefs : IMEPreferences {
    private lateinit var sp: SharedPreferences

    fun install(context: Context) {
        sp = context.getSharedPreferences("OhMyBiasPrefs", Context.MODE_PRIVATE)
        DefaultPreferences.backing = this
        DefaultPreferences.setSuggestEnabled = { suggestEnabled = it }
        SkinSettings.toolbarOverride = { toolbarButtons }
    }

    /// 監看偏好變更 — IME 與設定頁同 process，設定頁一改鍵盤就能即時反應
    /// （SharedPreferences 只保 weak reference，呼叫端須自行持有 listener）
    fun addListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun removeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /// 唯一候選且無法再延伸時自動送出
    override var autoCommit: Boolean
        get() = sp.getBoolean("autoCommit", false)
        set(v) = sp.edit().putBoolean("autoCommit", v).apply()

    /// 頂字上屏：滿碼且有候選時，下一鍵自動送出首選、開始下一字。
    /// 預設關 — 開啟時像 weekly 這種前四碼是有效字根的英文字無法直通
    override var overflowAutoCommit: Boolean
        get() = sp.getBoolean("overflowAutoCommit", false)
        set(v) = sp.edit().putBoolean("overflowAutoCommit", v).apply()

    /// 送字後顯示字根碼提示
    override var showCodeHint: Boolean
        get() = sp.getBoolean("showCodeHint", false)
        set(v) = sp.edit().putBoolean("showCodeHint", v).apply()

    /// 聯想總開關
    override var suggestEnabled: Boolean
        get() = sp.getBoolean("suggestEnabled", true)
        set(v) = sp.edit().putBoolean("suggestEnabled", v).apply()

    /// 接實體鍵盤時是否仍顯示聯想（與軟鍵盤分開）— 實體鍵盤可盲打，通常不需聯想，
    /// 故預設關；此開關只在有實體鍵盤時生效，且仍受聯想總開關 suggestEnabled 節制
    var suggestWithHardKeyboard: Boolean
        get() = sp.getBoolean("suggestWithHardKeyboard", false)
        set(v) = sp.edit().putBoolean("suggestWithHardKeyboard", v).apply()

    /// 無候選時嘗試相鄰鍵模糊比對
    override var fuzzyMatch: Boolean
        get() = sp.getBoolean("fuzzyMatch", true)
        set(v) = sp.edit().putBoolean("fuzzyMatch", v).apply()

    /// 聯想策略（極簡版僅萌典詞組，維持介面相容）
    override var suggestStrategy: String
        get() = sp.getString("suggestStrategy", "general") ?: "general"
        set(v) = sp.edit().putString("suggestStrategy", v).apply()

    /// 詞級語料 — 極簡版只有萌典
    override var wordCorpus: String
        get() = sp.getString("wordCorpus", "moedict") ?: "moedict"
        set(v) = sp.edit().putString("wordCorpus", v).apply()

    /// 字級聯想（bigram/trigram）— 極簡版無字級語料，保留介面
    override var charSuggest: Boolean
        get() = sp.getBoolean("charSuggest", true)
        set(v) = sp.edit().putBoolean("charSuggest", v).apply()

    /// 地區用詞：tw / cn（極簡版無地區語料，保留介面）
    override var regionVariant: String
        get() = sp.getString("regionVariant", "tw") ?: "tw"
        set(v) = sp.edit().putString("regionVariant", v).apply()

    /// 標點配對：打「自動補」
    override var punctuationPairing: Boolean
        get() = sp.getBoolean("punctuationPairing", true)
        set(v) = sp.edit().putBoolean("punctuationPairing", v).apply()

    /// 同音字查詢包含多音字的罕見讀音
    override var homophoneMultiReading: Boolean
        get() = sp.getBoolean("homophoneMultiReading", false)
        set(v) = sp.edit().putBoolean("homophoneMultiReading", v).apply()

    /// 上次使用的語言模式（true = 英文）— 鍵盤啟動時還原。預設嘸蝦米（米）；
    /// app 首次啟動與匯入 liu.cin 時歸零（見 resetToChineseMode）
    var lastEnglishMode: Boolean
        get() = sp.getBoolean("lastEnglishMode", false)
        set(v) = sp.edit().putBoolean("lastEnglishMode", v).apply()

    /// 語言模式歸零成嘸蝦米。還沒字表時使用者常切到英文（中文打不出來），
    /// 字表一匯入就該回中文，不該把那個權宜狀態帶著走；首次啟動也清 —
    /// SharedPreferences 會跟著自動備份還原，別讓舊裝置的英文狀態變成新裝置的預設（同 iOS）
    fun resetToChineseMode() { lastEnglishMode = false }

    /// 首次啟動整理是否已做過
    var firstLaunchDone: Boolean
        get() = sp.getBoolean("firstLaunchDone", false)
        set(v) = sp.edit().putBoolean("firstLaunchDone", v).apply()

    /// 中文（米）模式字母鍵以大寫顯示 — 嘸蝦米字根表慣用大寫，與實體鍵帽一致；
    /// 只影響鍵面標籤，送出的組字碼不變。英文模式仍依 shift 決定大小寫（同 iOS 鍵名）
    var uppercaseLettersInChinese: Boolean
        get() = sp.getBoolean("uppercaseLettersInChinese", false)
        set(v) = sp.edit().putBoolean("uppercaseLettersInChinese", v).apply()

    /// 按鍵觸覺回饋
    var hapticFeedback: Boolean
        get() = sp.getBoolean("hapticFeedback", true)
        set(v) = sp.edit().putBoolean("hapticFeedback", v).apply()

    /// 震動強度（0–100）：0 = 系統預設（KEYBOARD_TAP，跟隨系統觸覺強度）；
    /// 1–100 = 自訂 VibrationEffect（issue #4 反映預設偏弱無法調強）
    var hapticStrength: Int
        get() = sp.getInt("hapticStrength", 0)
        set(v) = sp.edit().putInt("hapticStrength", v.coerceIn(0, 100)).apply()

    /// 組字候選時保留工具列：候選改為覆蓋在工具列上（同聯想列），右側留一顆鍵可點。
    /// 預設關 — 候選整條佔滿
    var keepToolbarWithCandidates: Boolean
        get() = sp.getBoolean("keepToolbarWithCandidates", false)
        set(v) = sp.edit().putBoolean("keepToolbarWithCandidates", v).apply()

    /// 鍵盤高度縮放（0.85–1.40；1.0 = 預設 224dp/180dp）— 大螢幕手機可調大
    var keyboardHeightScale: Float
        get() = sp.getFloat("keyboardHeightScale", 1.0f)
        set(v) = sp.edit().putFloat("keyboardHeightScale", v.coerceIn(0.85f, 1.4f)).apply()

    /// 按鍵間距縮放（0.0–1.5；1.0 = 預設 上下 6dp／左右 3dp／排距＝鍵距 5dp）—
    /// 調小讓鍵面更大更好按，0 = 鍵與鍵完全貼合
    var keySpacingScale: Float
        get() = sp.getFloat("keySpacingScale", 1.0f)
        set(v) = sp.edit().putFloat("keySpacingScale", v.coerceIn(0f, 1.5f)).apply()

    /// 實體鍵盤接上時的畫面：keypad = 照常顯示軟體鍵盤；floating = 只在游標旁浮出
    /// 組字／候選氣泡；bar = 螢幕底部固定一條候選列（含工具列），不佔鍵盤高度。
    /// 三種模式實體按鍵都經引擎組字；本偏好優先於系統「實體鍵盤時顯示虛擬鍵盤」開關
    var hardKeyboardMode: String
        get() = sp.getString("hardKeyboardMode", HW_MODE_KEYPAD) ?: HW_MODE_KEYPAD
        set(v) = sp.edit().putString("hardKeyboardMode", v).apply()

    /// 設定頁自訂的工具列按鈕序列（ID 同 cskin toolbarButtons）；null = 跟皮膚。
    /// 存成逗號分隔字串；setter 同時遞增皮膚世代，鍵盤下次顯示（或同 process 立即）重建工具列
    var toolbarButtons: List<Int>?
        get() = sp.getString("toolbarButtons", null)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
        set(v) {
            if (v.isNullOrEmpty()) sp.edit().remove("toolbarButtons").apply()
            else sp.edit().putString("toolbarButtons", v.joinToString(",")).apply()
            SkinSettings.shared.invalidate()
        }

    /// 浮動鍵盤：鍵盤浮在畫面上（可拖曳、四角縮放），app 內容不被推上去；
    /// 工具列 ID 33 切換，跨輸入框／app 保持直到再按一次
    var floatingKeyboard: Boolean
        get() = sp.getBoolean("floatingKeyboard", false)
        set(v) = sp.edit().putBoolean("floatingKeyboard", v).apply()

    /// 浮動鍵盤上次的位置與大小（dp；-1 = 尚未拖過，用預設）— 拖曳／縮放放手時寫入
    var floatingRectDp: FloatArray?
        get() {
            val s = sp.getString("floatingRectDp", null) ?: return null
            val v = s.split(',').mapNotNull { it.toFloatOrNull() }
            return if (v.size == 4) v.toFloatArray() else null
        }
        set(v) {
            if (v == null) sp.edit().remove("floatingRectDp").apply()
            else sp.edit().putString("floatingRectDp", v.joinToString(",")).apply()
        }

    const val HW_MODE_KEYPAD = "keypad"
    const val HW_MODE_FLOATING = "floating"
    const val HW_MODE_BAR = "bar"

    /// 詞庫開關 — 極簡版僅萌典詞組，預設開啟
    override fun domainEnabled(key: String): Boolean =
        if (sp.contains(key)) sp.getBoolean(key, false) else key == "domain_phrases"

    override fun domainPriority(key: String): Int = sp.getInt(key + "_pri", 0)
}

/// 自訂強度 → 震動效果：振幅與時長都隨強度放大（8–32ms、振幅 1–255）。
/// 有振幅控制的馬達兩者都吃；沒有的（on/off 馬達）只吃時長，仍可感受強弱。
fun customHapticEffect(strength: Int): android.os.VibrationEffect {
    val s = strength.coerceIn(1, 100)
    val amplitude = (255 * s / 100).coerceIn(1, 255)
    val duration = (8 + s * 24 / 100).toLong()  // 8..32ms
    return android.os.VibrationEffect.createOneShot(duration, amplitude)
}
