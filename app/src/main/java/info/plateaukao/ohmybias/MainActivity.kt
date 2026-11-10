package info.plateaukao.ohmybias

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.CINCompiler
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.SkinSettings
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/// 鍵盤外觀編輯器網站（plateaukao/ohmybias-skin — 匯出 .cskin 後回本頁匯入）
private const val SKIN_DESIGNER_URL = "https://plateaukao.github.io/ohmybias-skin/"

/// 設定頁 — 對應 iOS ContentView：啟用鍵盤、匯入 liu.cin、皮膚、偏好 toggle、
/// 自訂詞編輯、指令速查；外加「測試輸入」欄位供模擬器驗證。
class MainActivity : Activity() {

    private val pickCinRequest = 1001
    private val pickSkinRequest = 1002

    private lateinit var tableStatus: TextView
    private lateinit var importMessage: TextView
    private lateinit var skinStatus: TextView
    private lateinit var skinMessage: TextView
    private lateinit var toolbarStatus: TextView

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OhMyBias 米"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(8f), dp(16f), dp(24f))

        // ── 啟用鍵盤 ──
        root.addView(sectionTitle("啟用鍵盤"))
        root.addView(footnote("設定 ▸ 系統 ▸ 語言與輸入法 ▸ 螢幕鍵盤 ▸ 啟用「OhMyBias 米」"))
        root.addView(buttonFlow(
            "打開鍵盤設定" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            "切換輸入法" to {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            },
        ))

        // ── 字表 ──
        root.addView(sectionTitle("字表"))
        tableStatus = footnote("")
        root.addView(tableStatus)
        root.addView(footnote("支援 .cin 或官方 Windows 嘸蝦米 liu-uni*.tab（可一次多選 liu-uni ~ liu-uni4.tab 自動合併去重）"))
        root.addView(buttonFlow(
            "匯入 liu.cin / .tab" to {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(intent, pickCinRequest)
            },
        ))
        importMessage = statusFootnote()
        root.addView(importMessage)

        // ── 主題 ──
        root.addView(sectionTitle("主題"))
        skinStatus = footnote("")
        root.addView(skinStatus)
        root.addView(buttonFlow(
            "鍵盤外觀編輯器" to {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SKIN_DESIGNER_URL)))
                } catch (e: Exception) {
                    skinMessage.text = "找不到瀏覽器 — 請自行開啟 $SKIN_DESIGNER_URL"
                }
            },
            "匯入 .cskin" to {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(intent, pickSkinRequest)
            },
            "還原內建" to {
                File(SkinSettings.settingsPath).delete()
                SkinSettings.shared.reload()
                skinMessage.text = "已還原內建主題 — 重開鍵盤生效"
                refreshSkinStatus()
            },
        ))
        skinMessage = statusFootnote()
        root.addView(skinMessage)
        toolbarStatus = footnote("")
        toolbarStatus.setPadding(0, dp(10f), 0, dp(2f))
        root.addView(toolbarStatus)
        root.addView(buttonFlow(
            "自訂工具列" to { startActivity(Intent(this, ToolbarSettingsActivity::class.java)) },
        ))

        // ── 聯想 ──
        root.addView(sectionTitle("聯想"))
        root.addView(toggle("聯想詞（萌典詞組）", Prefs.suggestEnabled) { Prefs.suggestEnabled = it })
        root.addView(toggle("接實體鍵盤時仍顯示聯想", Prefs.suggestWithHardKeyboard) { Prefs.suggestWithHardKeyboard = it })
        root.addView(footnote("實體鍵盤可盲打，預設關閉聯想；與軟鍵盤分開，開啟則照常顯示"))
        root.addView(buttonFlow("常用語設定" to { startActivity(Intent(this, UserPhrasesActivity::class.java)) }))

        // ── 輸入 ──
        root.addView(sectionTitle("輸入"))
        root.addView(toggle("唯一候選自動送出", Prefs.autoCommit) { Prefs.autoCommit = it })
        root.addView(toggle("滿碼頂字上屏", Prefs.overflowAutoCommit) { Prefs.overflowAutoCommit = it })
        root.addView(footnote("滿碼後續打自動送出首選。開啟時 weekly 這類前四碼恰為字根的英文字無法直通"))
        root.addView(toggle("相鄰鍵模糊比對", Prefs.fuzzyMatch) { Prefs.fuzzyMatch = it })
        root.addView(toggle("送字後顯示字根提示", Prefs.showCodeHint) { Prefs.showCodeHint = it })
        root.addView(toggle("成對標點自動補右半", Prefs.punctuationPairing) { Prefs.punctuationPairing = it })
        root.addView(toggle("按鍵觸覺回饋", Prefs.hapticFeedback) { Prefs.hapticFeedback = it })

        // 震動強度滑桿：0 = 系統預設（KEYBOARD_TAP）；1–100 自訂效果，放開滑桿試震一下
        val hapLabel = footnote("")
        fun updateHapLabel() {
            hapLabel.text = if (Prefs.hapticStrength == 0)
                "震動強度：系統預設（往右調自訂強度，即時生效）"
            else
                "震動強度：${Prefs.hapticStrength}%（0 = 系統預設，即時生效）"
        }
        updateHapLabel()
        hapLabel.setPadding(0, dp(2f), 0, dp(2f))
        root.addView(hapLabel)
        val hapSeek = SeekBar(this)
        hapSeek.max = 100
        hapSeek.progress = Prefs.hapticStrength.coerceIn(0, 100)
        hapSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.hapticStrength = progress
                updateHapLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 放開時用目前強度試震，邊調邊感受
                if (Prefs.hapticStrength > 0) {
                    val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
                        (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                    else
                        @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
                    vib.vibrate(info.plateaukao.ohmybias.android.customHapticEffect(Prefs.hapticStrength))
                }
            }
        })
        root.addView(hapSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(toggle("米模式字母鍵顯示大寫", Prefs.uppercaseLettersInChinese) { Prefs.uppercaseLettersInChinese = it })
        root.addView(toggle("組字候選時保留工具列", Prefs.keepToolbarWithCandidates) { Prefs.keepToolbarWithCandidates = it })
        root.addView(footnote("候選列覆蓋在工具列上（同聯想列），右側固定留一顆鍵可點"))
        root.addView(toggle("同音字含罕見讀音", Prefs.homophoneMultiReading) { Prefs.homophoneMultiReading = it })

        // 鍵盤高度滑桿（大螢幕手機可調大；拖曳時下方測試輸入框的鍵盤即時跟著變）
        val heightLabel = footnote("")
        fun heightPct() = (Prefs.keyboardHeightScale * 100).toInt()
        fun updateHeightLabel() { heightLabel.text = "鍵盤高度：${heightPct()}%（85–140，即時生效）" }
        updateHeightLabel()
        heightLabel.setPadding(0, dp(10f), 0, dp(2f))
        root.addView(heightLabel)
        val heightSeek = SeekBar(this)
        heightSeek.max = 55  // 85% + progress → 85%..140%
        heightSeek.progress = (heightPct() - 85).coerceIn(0, 55)
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.keyboardHeightScale = (85 + progress) / 100f
                updateHeightLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(heightSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 按鍵間距滑桿（調小 = 鍵面更大更緊湊；0% 鍵與鍵完全貼合）
        val gapLabel = footnote("")
        fun gapPct() = (Prefs.keySpacingScale * 100).toInt()
        fun updateGapLabel() { gapLabel.text = "按鍵間距：${gapPct()}%（0–150，100 = 預設，即時生效）" }
        updateGapLabel()
        gapLabel.setPadding(0, dp(10f), 0, dp(2f))
        root.addView(gapLabel)
        val gapSeek = SeekBar(this)
        gapSeek.max = 150
        gapSeek.progress = gapPct().coerceIn(0, 150)
        gapSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.keySpacingScale = progress / 100f
                updateGapLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(gapSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(footnote("鍵盤上下留白、排距與鍵距一起縮放；調小則鍵面變大更好按"))

        // ── 實體鍵盤 ──
        root.addView(sectionTitle("實體鍵盤"))
        root.addView(footnote("接上藍牙／USB 鍵盤時的畫面（切換即時生效）"))
        root.addView(dropdownChoice(
            Prefs.hardKeyboardMode,
            listOf(
                Prefs.HW_MODE_KEYPAD to "照常顯示軟體鍵盤",
                Prefs.HW_MODE_FLOATING to "只在游標旁浮出組字／候選氣泡（隱藏按鍵區）",
                Prefs.HW_MODE_BAR to "螢幕底部固定一條候選列（隱藏按鍵區）",
            ),
        ) { Prefs.hardKeyboardMode = it })
        root.addView(collapsible(
            "實體鍵盤按鍵說明",
            footnote(
                """
                字母組字、數字鍵選字、空白送字、Enter 原樣上屏、Esc 清組字；
                單按 Shift 切換中英；Shift+字母 直接輸出小寫英文；Shift+Space 全形空白；
                Shift+8（*）萬用字元；CapsLock 亮著時英文直通。
                """.trimIndent()
            ),
        ))

        // ── 測試輸入 ──
        root.addView(sectionTitle("測試輸入"))
        val testInput = object : EditText(this) {
            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.selectAll && !hasSelection() && !text.isNullOrEmpty()) {
                    val args = Bundle().apply {
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                    }
                    if (performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
                        return true
                    }
                }
                return super.onTextContextMenuItem(id)
            }
        }
        testInput.hint = "在此測試鍵盤輸入"
        testInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        root.addView(testInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 使用說明 ──（收合，點開才看；原本鍵盤內 ,,H 的完整說明搬來這裡）
        root.addView(collapsibleSection("使用說明", footnote(
            """
            ▎基本輸入
            輸入字根碼後按空白鍵送出；V/R/S/F 快速選第 2/3/4/5 個候選字；
            多候選時數字鍵 1–9 選字。

            ▎空白鍵手勢
            左右拖曳：移動游標
            上滑：中↔英快速切換
            右上滑：注音查碼　左上滑：同音字查詢

            ▎鍵盤切換
            [123] 切到數字符號頁；[符] 切到數字頁（嘸蝦米第三行）；
            [米/英] 從數字符號頁回字母頁。

            ▎尺寸調整
            上方滑桿可調鍵盤高度（85–140%）與按鍵間距（0–150%），拖曳即時生效。
            """.trimIndent()
        )))

        // ── 指令速查 ──（收合）
        val cheatBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cheatBox.addView(footnote("在輸入框打 ,, 開頭的指令（不必送出）"))
        val cheat = footnote(
            """
            ,,T 繁體  ,,S 簡體  ,,J 日文
            ,,SP 速成  ,,SL 慢打
            ,,TS 繁→簡  ,,ST 簡→繁
            ,,ZH 注音查碼  ,,TO 同音字
            ,,PYS 拼音(簡)  ,,PYT 拼音(繁)
            ,,SG 聯想開關  ,,C 目前模式
            ,,PIN 固定排序  ,,UNPINx 解除
            ,,RL 重載字表
            ,,V 貼上純文字  ,,VT 簡→繁  ,,VS 繁→簡
            """.trimIndent()
        )
        cheat.typeface = Typeface.MONOSPACE
        cheatBox.addView(cheat)
        root.addView(collapsibleSection("指令速查", cheatBox))

        // ── 版本 ──（© 取代 @，最底部）
        root.addView(versionFootnote())

        // 開關列彼此拉開間距 — 一連串開關貼太近容易誤觸旁邊那顆；
        // 每顆上下各留 8dp（相鄰兩顆間隔 16dp），並把觸控高度加大到 52dp
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Switch) {
                child.minimumHeight = dp(52f)
                (child.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = dp(8f); it.bottomMargin = dp(8f)
                }
            }
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        // targetSdk 36 強制 edge-to-edge（Android 15+ 系統列透明、內容延伸到底下）—
        // 以 insets 當 padding 讓內容避開狀態列/導覽列；API 29 無此強制、維持原樣
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)

        refreshTableStatus()
        refreshSkinStatus()
        refreshToolbarStatus()

        // 由檔案管理員／瀏覽器點 .cskin 進來（VIEW intent）— 詢問後套用
        handleViewIntent(intent)
    }

    /// singleTop：設定頁已在最上層時再點 .cskin，intent 從這裡進來
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /// 從自訂工具列頁回來時更新狀態列
    override fun onResume() {
        super.onResume()
        if (::toolbarStatus.isInitialized) refreshToolbarStatus()
    }

    // MARK: - UI helpers（Android 設定頁風格：accent 分類標題＋ripple 可點列，
    // 取代原本滿版 Material Button — 直向 LinearLayout 預設子視圖 MATCH_PARENT，
    // Button 又自帶大 minHeight/內距，才會整顆撐滿螢幕又佔高）

    /// 解析主題屬性色（colorAccent、textColorSecondary 這類可能是資源參照）
    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) resources.getColor(tv.resourceId, theme) else tv.data
    }

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text
        // 分類標題明顯大於內文（footnote 13sp）— 一眼看得出分節
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.setTextColor(themeColor(android.R.attr.colorAccent))
        t.setPadding(0, dp(22f), 0, dp(6f))
        return t
    }

    /// 動態狀態列 — 沒內容時整列隱藏，不留空隙
    private fun statusFootnote(): TextView = footnote("").apply {
        visibility = android.view.View.GONE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                visibility = if (s.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun footnote(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.setTextColor(themeColor(android.R.attr.textColorSecondary))
        t.setPadding(0, dp(2f), 0, dp(2f))
        return t
    }

    /// 緊湊 outlined 按鈕 — 透明底、細框圓角＋ripple（Material outlined 樣式），
    /// 並壓掉 Button 預設的大 minWidth/minHeight 與按壓浮起動畫
    private fun compactButton(title: String, onClick: () -> Unit): Button {
        val accent = themeColor(android.R.attr.colorAccent)
        val radius = dp(8f).toFloat()
        val outline = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(1f), (accent and 0xFFFFFF) or (0x99 shl 24))  // 邊框 = accent 60%
        }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = radius
        }
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent and 0xFFFFFF) or (0x33 shl 24)),
            outline, mask,
        )
        val b = Button(this)
        b.text = title
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        b.setTextColor(accent)
        b.background = ripple
        b.stateListAnimator = null
        b.minWidth = 0; b.minimumWidth = 0
        b.minHeight = 0; b.minimumHeight = dp(40f)
        b.setPadding(dp(14f), 0, dp(14f), 0)
        b.setOnClickListener { onClick() }
        return b
    }

    /// 一列多顆按鈕、放不下自動換行 — 取代一顆一列的滿版大按鈕
    private fun buttonFlow(vararg items: Pair<String, () -> Unit>): ViewGroup {
        val flow = FlowLayout(this, dp(8f))
        for ((title, onClick) in items) flow.addView(compactButton(title, onClick))
        return flow
    }

    /// 簡易流式排版（同 CollectionPanelView 的做法）
    private class FlowLayout(context: android.content.Context, private val spacing: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    MeasureSpec.UNSPECIFIED,
                )
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += rowH + spacing; rowH = 0 }
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
            setMeasuredDimension(w, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += rowH + spacing; rowH = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
        }
    }

    /// 下拉選單（值 to 標籤）— 收合時只顯示目前選項，點開才列出全部
    private fun dropdownChoice(initial: String, options: List<Pair<String, String>>, onChange: (String) -> Unit): Spinner {
        val sp = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        sp.setSelection(options.indexOfFirst { it.first == initial }.coerceAtLeast(0))
        // 首次 layout 時系統會補派一次目前選項的 onItemSelected — 忽略，只有真正變更才寫
        var primed = false
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!primed) { primed = true; return }
                options.getOrNull(position)?.let { onChange(it.first) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return sp
    }

    /// 收合區塊：可點的標題列（附 ▸/▾ 指示），內容預設收起、點標題展開／收合。
    /// 標題用 sectionTitle 的 accent 樣式，讓收合節與其他分類標題視覺一致。
    private fun collapsibleSection(title: String, content: android.view.View): ViewGroup {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val header = sectionTitle("▸ $title")
        header.isClickable = true
        content.visibility = android.view.View.GONE
        header.setOnClickListener {
            val show = content.visibility != android.view.View.VISIBLE
            content.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            header.text = (if (show) "▾ " else "▸ ") + title
        }
        box.addView(header)
        box.addView(content)
        return box
    }

    /// 收合區塊（無 accent 標題版）：給實體鍵盤說明這種附屬在既有分類下的小段落用，
    /// 標題是次要色的可點列，不另起一個分類標題
    private fun collapsible(title: String, content: android.view.View): ViewGroup {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val header = footnote("▸ $title")
        header.setTextColor(themeColor(android.R.attr.colorAccent))
        header.isClickable = true
        header.minHeight = dp(36f)
        header.gravity = Gravity.CENTER_VERTICAL
        content.visibility = android.view.View.GONE
        header.setOnClickListener {
            val show = content.visibility != android.view.View.VISIBLE
            content.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            header.text = (if (show) "▾ " else "▸ ") + title
        }
        box.addView(header)
        box.addView(content)
        return box
    }

    /// 版本列（最底部）：vX.Y.Z © Optimized by Benson，置中、次要色
    private fun versionFootnote(): TextView {
        val name = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { null } ?: ""
        val t = footnote("v$name © Optimized by Benson")
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(28f), 0, dp(8f))
        return t
    }

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val s = Switch(this)
        s.text = text
        s.isChecked = initial
        s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        s.minHeight = dp(48f)
        s.gravity = Gravity.CENTER_VERTICAL
        s.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        return s
    }

    // MARK: - 匯入

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            pickCinRequest -> {
                val uris = extractUris(data)
                if (uris.isNotEmpty()) handleCinImport(uris)
            }
            pickSkinRequest -> {
                val uri = data.data ?: extractUris(data).firstOrNull() ?: return
                handleSkinImport(uri)
            }
        }
    }

    private fun extractUris(data: Intent): List<Uri> {
        val list = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { list.add(it) }
            }
        }
        if (list.isEmpty()) {
            data.data?.let { list.add(it) }
        }
        return list
    }

    private fun queryDisplayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrEmpty()) return name
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /// liu.cin / liu-uni*.tab 動輒數 MB，來源又可能是雲端硬碟（下載時間不定）—— 複製＋編譯全放背景執行緒，
    /// 免得主執行緒卡到 ANR。
    private fun handleCinImport(uris: List<Uri>) {
        importMessage.text = "匯入中…"
        Thread({
            val message = importCin(uris)
            runOnUiThread {
                importMessage.text = message
                refreshTableStatus()
            }
        }, "ohmybias-import").start()
    }

    /// 全程寫暫存檔、成功才 rename 就位：
    /// - 支援單檔或多檔（.cin 與官方 Windows 嘸蝦米 liu-uni*.tab），自動辨識繁/簡/台式簡/日文表並產出對應模式字表；
    /// - 中途失敗（來源讀到一半斷線、不是有效 .cin / .tab）不會動到現有字表；
    /// - rename 不影響鍵盤已 mmap 的舊 liu.bin —— 舊 inode 活到它重載為止，
    ///   不會讀到寫一半的內容，檔案變短也不會 SIGBUS。
    private fun importCin(uris: List<Uri>): String {
        try {
            val sortedUris = uris.mapIndexed { idx, uri ->
                val name = queryDisplayName(uri)
                val stem = name.substringBeforeLast('.').lowercase()
                Triple(stem, idx, uri to name)
            }.sortedWith(compareBy<Triple<String, Int, Pair<Uri, String>>> { it.first }.thenBy { it.second })
                .map { it.third }

            val namedSources = ArrayList<Pair<String, ByteArray>>(sortedUris.size)
            for ((uri, name) in sortedUris) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return "讀取失敗"
                namedSources.add(name to bytes)
            }
            val count = CINCompiler.importSources(namedSources, AppEnv.sharedDir, AppEnv.cinPath)
            if (count == 0) {
                return "編譯失敗 — 請確認是有效的 .cin 或 liu-uni*.tab 檔"
            }
            if (count < 0) {
                return "寫入失敗 — 儲存空間可能不足"
            }
            CINTable.bumpGeneration()  // 鍵盤同 process — 下次進輸入框自動重載
            // 字表到手就回嘸蝦米 — 匯入前打不出中文而切去的英文模式不該延續到有字表之後
            // （onStartInput 每次都重讀 lastEnglishMode，下次進輸入框即生效）
            Prefs.resetToChineseMode()
            return if (sortedUris.size > 1) {
                "已合併 ${sortedUris.size} 個檔案並編譯 $count 個字碼（各輸入模式排序已分別建立）"
            } else {
                "已編譯 $count 個字碼"
            }
        } catch (e: Exception) {
            return "匯入失敗：${e.message}"
        }
    }

    /// .cskin = zip，取其中 jsonnet/settings.json 的配置層；非 zip 或缺檔回 null
    private fun readSkinSettingsJson(uri: Uri): ByteArray? {
        var settingsJson: ByteArray? = null
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var fallback: ByteArray? = null
                while (entry != null) {
                    if (entry.name.endsWith("jsonnet/settings.json")) {
                        settingsJson = zip.readBytes(); break
                    }
                    if (entry.name.endsWith("settings.json") && fallback == null) {
                        fallback = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
                if (settingsJson == null) settingsJson = fallback
            }
        }
        return settingsJson
    }

    private fun applySkinJson(json: ByteArray) {
        File(SkinSettings.settingsPath).writeBytes(json)
        SkinSettings.shared.reload()
        skinMessage.text = if (SkinSettings.shared.isImported)
            "已套用「${SkinSettings.shared.skinName}」— 重開鍵盤生效"
        else "settings.json 格式無法解析"
        refreshSkinStatus()
    }

    /// SAF 選檔匯入：直接套用（使用者已在選檔時表達意圖）
    private fun handleSkinImport(uri: Uri) {
        try {
            val json = readSkinSettingsJson(uri) ?: run {
                skinMessage.text = "找不到 settings.json — 請確認是有效的 .cskin 檔"; return
            }
            applySkinJson(json)
        } catch (e: Exception) {
            skinMessage.text = "匯入失敗：${e.message}"
        }
    }

    /// 檔案管理員／瀏覽器點 .cskin 開啟本 app（manifest VIEW intent-filter）—
    /// 非使用者主動選檔，先顯示主題名稱確認再套用
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val json = try { readSkinSettingsJson(uri) } catch (e: Exception) { null }
        if (json == null) {
            skinMessage.text = "找不到 settings.json — 請確認是有效的 .cskin 檔"
            return
        }
        val name = try {
            JSONObject(String(json, Charsets.UTF_8))
                .optJSONObject("skinInfo")?.optString("name")
        } catch (e: Exception) { null }
        val displayName = name?.takeIf { it.isNotEmpty() } ?: "未命名主題"
        AlertDialog.Builder(this)
            .setTitle("套用主題")
            .setMessage("要套用主題「$displayName」嗎？\n（會取代目前的主題，重開鍵盤生效）")
            .setPositiveButton("套用") { _, _ -> applySkinJson(json) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshTableStatus() {
        val table = CINTable()
        table.reload()
        tableStatus.text = if (table.isEmpty) {
            "尚未匯入字表 — 鍵盤無法輸出中文"
        } else {
            val name = table.cinName.ifEmpty { "字表" }
            "已載入：$name（最長碼 ${table.maxCodeLength}）"
        }
    }

    private fun refreshSkinStatus() {
        SkinSettings.shared.reload()
        skinStatus.text = "目前主題：${SkinSettings.shared.skinName}"
    }

    private fun refreshToolbarStatus() {
        val custom = Prefs.toolbarButtons
        toolbarStatus.text = if (custom == null) "工具列：跟隨主題（${SkinSettings.shared.skinToolbarButtons.size} 顆）"
                             else "工具列：自訂（${custom.size} 顆）"
    }
}
