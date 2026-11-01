package info.plateaukao.ohmybias.keyboard

import info.plateaukao.ohmybias.R
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.SkinSettings

/// 工具列按鈕定義表 — CandidateBar 建列與設定頁「自訂工具列」共用同一份。
/// 按鈕 ID 定義同 sweetlime SkinSettings.TB_*；iOS 版因 extension API 缺失略過的編輯類動作，
/// Android 依原始定義實作。做不到的 ID（0 佔位符、6 剪貼本、18-25/31 Hamster 專屬）
/// 回 null → 空白佔位。32 起是本家自訂 ID（同步定義於鍵盤外觀編輯器 ohmybias-skin
/// `data.js` TOOLBAR_ITEMS）。
/// 圖示一律 Material Symbols Outlined 24px（Apache-2.0），tint 跟 toolbarColor；
/// 語意靠字才講得清的保留文字：米/英（要顯示目前模式）、簡、顏、ㄅ。
object ToolbarItems {

    class Item(
        val id: Int,
        val text: String,
        val label: String,
        val action: KeyAction,
        val isLanguage: Boolean = false,
        /// 非 0 = 改用圖示（ImageView，tint 跟著 toolbarColor）而非文字字樣
        val iconRes: Int = 0,
    )

    /// 空白佔位 ID（cskin 用來留空格）
    const val PLACEHOLDER = 0

    /// 工具列固定格數（cskin toolbarButtons 為 10 格；不足補 0 佔位）
    const val SLOT_COUNT = 10

    /// 設定頁可選的按鈕（順序同鍵盤外觀編輯器 ohmybias-skin 的 TOOLBAR_ITEMS）。
    /// 29/30 是 9/7 的同義備用 ID，不入選單；皮膚帶進來的仍照常顯示與運作。
    val selectable: List<Int> = listOf(PLACEHOLDER, 1, 2, 3, 35, 4, 5, 7, 8, 9, 34, 10, 11, 12, 13, 14, 15, 16, 17, 26, 27, 32, 33)

    fun item(id: Int): Item? = when (id) {
        1 -> Item(id, "設", "設定", KeyAction.OpenSettings, iconRes = R.drawable.ic_tb_settings)
        2 -> Item(id, "∨", "收折鍵盤", KeyAction.DismissKeyboard, iconRes = R.drawable.ic_tb_keyboard_hide)
        3 -> Item(id, "中", "中英切換", KeyAction.ToggleLanguage, isLanguage = true)
        4 -> Item(id, "簡", "簡繁切換", KeyAction.ToggleSimpTrad)
        5 -> Item(id, "♥︎", "常用語", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.PHRASES), iconRes = R.drawable.ic_tb_favorite)
        7 -> Item(id, "符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL), iconRes = R.drawable.ic_tb_emoji_symbols)
        8 -> Item(id, "☺︎", "Emoji", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.EMOJI), iconRes = R.drawable.ic_tb_mood)
        9 -> {
            val page = if (SkinSettings.shared.keyboardLayout == "row") KeyboardView.PageKind.NUMBERS
                       else KeyboardView.PageKind.NUMERIC9
            Item(id, "123", "數字鍵盤", KeyAction.ToggleToolbarPage(page), iconRes = R.drawable.ic_tb_dialpad)
        }
        10 -> Item(id, "全", "全選", KeyAction.SelectAll, iconRes = R.drawable.ic_tb_select_all)
        11 -> Item(id, "複", "複製", KeyAction.Copy, iconRes = R.drawable.ic_tb_content_copy)
        12 -> Item(id, "剪", "剪下", KeyAction.Cut, iconRes = R.drawable.ic_tb_content_cut)
        13 -> Item(id, "貼", "貼上", KeyAction.PasteClipboard, iconRes = R.drawable.ic_tb_content_paste)
        14 -> Item(id, "↶", "復原", KeyAction.Undo, iconRes = R.drawable.ic_tb_undo)
        15 -> Item(id, "↷", "重做", KeyAction.Redo, iconRes = R.drawable.ic_tb_redo)
        16 -> Item(id, "←", "游標左移", KeyAction.CursorLeft, iconRes = R.drawable.ic_tb_chevron_left)
        17 -> Item(id, "→", "游標右移", KeyAction.CursorRight, iconRes = R.drawable.ic_tb_chevron_right)
        26 -> Item(id, "顏", "顏文字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.KAOMOJIS))
        27 -> Item(id, "ㄅ", "注音查碼", KeyAction.EnterZhuyin)
        29 -> Item(id, "123", "九宮格數字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.NUMERIC9), iconRes = R.drawable.ic_tb_dialpad)
        30 -> Item(id, "符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL), iconRes = R.drawable.ic_tb_emoji_symbols)
        // 32 = 本家自訂（Hamster 用到 31 為止）：語音輸入 — 切到系統語音輸入法，iOS 版無此能力
        32 -> Item(id, "", "語音輸入", KeyAction.VoiceInput, iconRes = R.drawable.ic_tb_mic)
        // 33 = 浮動鍵盤開關：浮動中顯示「停回底部」圖示，否則「浮出」圖示（切換時整組 view 重建）
        33 -> Item(id, "", "浮動鍵盤", KeyAction.ToggleFloatingKeyboard,
            iconRes = if (Prefs.floatingKeyboard) R.drawable.ic_tb_dock_to_bottom else R.drawable.ic_tb_pip)
        // 34 = 符號鍵盤（直接切換至 #+= 符號頁，再按一次切回原頁）
        34 -> Item(id, "#+=", "符號鍵盤", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOLS), iconRes = R.drawable.ic_tb_data_object)
        // 35 = 切換輸入法（地球圖示，切換至下一個系統輸入法或開啟選單）
        35 -> Item(id, "🌐", "切換輸入法", KeyAction.Globe, iconRes = R.drawable.ic_tb_globe)
        else -> null
    }

    /// 設定頁用的顯示名稱（含佔位符與不可實作的 ID）
    fun label(id: Int): String = when (id) {
        PLACEHOLDER -> "空白佔位"
        else -> item(id)?.label ?: "（不支援 #$id）"
    }
}
