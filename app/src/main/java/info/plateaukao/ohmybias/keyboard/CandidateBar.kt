package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.SkinSettings

/// 候選字列：左側 composing 碼、右側水平捲動候選字／聯想詞。
/// 空閒時顯示目前輸入法模式＋sweetlime 工具列；聯想詞（與開了選項時的組字候選）
/// 覆蓋在工具列上，右側留一顆鍵；否則組字候選整條佔滿、工具列讓位。
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context) : FrameLayout(context) {

    companion object {
        const val BAR_HEIGHT_DP = 46f
    }

    var onSelect: ((Int) -> Unit)? = null
    /// 工具列按鈕動作（路由至 OhMyBiasImeService.handleKey）
    var onToolbarKey: ((KeyAction) -> Unit)? = null
    /// ✕ 關閉聯想列（不輸出任何字、回到工具列）
    var onDismissSuggestions: (() -> Unit)? = null
    /// 點組字碼標籤 — 把打的字母原樣上屏（要英文單字不要候選時）
    var onCommitComposing: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val composingLabel = TextView(context)
    private val scrollView = HorizontalScrollView(context)
    private val stack = LinearLayout(context)
    private val toolbarStack = LinearLayout(context)
    /// 右緣溢出指示 › — 候選/聯想超出可視範圍且還能往右捲時顯示，捲到底自動消失
    private val overflowHint = TextView(context)
    private var languageButton: TextView? = null

    /// 語言鍵顯示目前輸入法：嘸蝦米 →「中」、英文 →「英」
    fun setEnglishMode(isEnglish: Boolean) {
        languageButton?.text = if (isEnglish) "英" else "中"
    }

    /// 上次顯示內容 — 相同就完全不動（IME 每個按鍵/每次切輸入框都會重設候選列）
    private var lastCandidates: List<String> = emptyList()
    private var lastSuggestions = false
    /// stack 目前作用中的子視圖數（多餘的隱藏備用，取代每鍵擊 removeAllViews + new TextView）
    private var activeStackViews = 0

    init {
        setBackgroundColor(KeyboardTheme.toolbarBackground)

        // 工具列先加、組字標籤與候選捲動區後加 —— FrameLayout 後加者疊在上層，也先收到觸控。
        // 聯想列（與開了選項時的組字候選）是「覆蓋」在工具列上而不是取代它，
        // 蓋不到的工具列鍵要照樣點得到。
        toolbarStack.orientation = LinearLayout.HORIZONTAL
        addView(toolbarStack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(8f); rightMargin = dp(8f)
        })
        // 照工具列序列（設定頁自訂 > cskin toolbarButtons）建構；做不到的按鈕 ID（含 0 佔位符）留空格
        for (id in SkinSettings.shared.toolbarButtons) {
            val item = ToolbarItems.item(id)
            if (item == null) {
                toolbarStack.addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                continue
            }
            if (item.iconRes != 0) {
                val iv = ImageView(context)
                iv.setImageResource(item.iconRes)
                iv.imageTintList = android.content.res.ColorStateList.valueOf(KeyboardTheme.toolbarColor)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                // 只內縮上下 12dp → 圖示畫 22dp 框（46dp 列高 - 上下各 12dp）。Material
                // Symbols 24dp 框內建 2-3dp 留白，22dp 框的光學高度約 18dp，與旁邊 19dp
                // 文字鍵的字面高度（米 ≈ 17.5dp）齊平。
                // 水平不可內縮 — FIT_CENTER 取寬高較小者，而格寬是「螢幕寬 ÷ 按鍵數」：
                // 411dp 螢幕配 10 顆鍵只有 41dp，再各縮 11dp 就剩 19dp 框（光學 15dp），
                // 圖示會比文字鍵小一號；360dp 的窄螢幕更只剩 14dp。水平留 0 讓高度成為
                // 唯一限制，圖示自己置中，觸控範圍仍是整格。
                val ip = dp(12f)
                iv.setPadding(0, ip, 0, ip)
                iv.contentDescription = item.label
                iv.isClickable = true
                iv.setOnClickListener { onToolbarKey?.invoke(item.action) }
                toolbarStack.addView(iv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                continue
            }
            val b = TextView(context)
            b.text = item.text
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (item.text.length > 1 && item.text != "123") 19f else 19f)
            b.setTextColor(KeyboardTheme.toolbarColor)
            b.gravity = Gravity.CENTER
            b.contentDescription = item.label
            b.isClickable = true
            b.setOnClickListener { onToolbarKey?.invoke(item.action) }
            toolbarStack.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            if (item.isLanguage) {
                languageButton = b
                // 長按米/英 → 系統輸入法選單（隱藏 🌐 鍵後仍可換輸入法）
                b.setOnLongClickListener { onToolbarKey?.invoke(KeyAction.ShowImePicker); true }
            }
        }
        composingLabel.typeface = Typeface.MONOSPACE
        composingLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        composingLabel.setTextColor(KeyboardTheme.textSub)
        composingLabel.gravity = Gravity.CENTER_VERTICAL
        // 覆蓋工具列時標籤正好壓在第一顆鍵（米/英）上 — 滿高、不透明，整顆遮掉
        composingLabel.setBackgroundColor(KeyboardTheme.toolbarBackground)
        // 組字碼本身可點 — 有候選但要英文單字時，點了原樣上屏
        composingLabel.isClickable = true
        composingLabel.setOnClickListener { onCommitComposing?.invoke() }
        addView(composingLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
            leftMargin = dp(10f)
        })

        scrollView.isHorizontalScrollBarEnabled = false
        // 覆蓋工具列時得遮住底下的圖示（否則字縫間會透出圖示）— 與整條列同色
        scrollView.setBackgroundColor(KeyboardTheme.toolbarBackground)
        stack.orientation = LinearLayout.HORIZONTAL
        stack.gravity = Gravity.CENTER_VERTICAL
        scrollView.addView(stack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(64f)
        })

        overflowHint.text = "\u203a"
        overflowHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
        overflowHint.setTextColor(KeyboardTheme.textSub)
        overflowHint.gravity = Gravity.CENTER
        // 蓋在被截斷的候選字上，要有底色才不會與字重疊難讀（同列底色）
        overflowHint.setBackgroundColor(KeyboardTheme.toolbarBackground)
        overflowHint.setPadding(dp(3f), 0, dp(3f), 0)
        overflowHint.visibility = View.GONE
        // 不設 clickable — 觸控穿透到底下的捲動區，照樣可以從 › 上起手滑動
        addView(overflowHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END
        })
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateOverflowHint() }
        scrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverflowHint() }

        // 預建候選格：聯想列最多 ✕ + 10 個詞 = 11 格。第一次送字時才建 11 個 TextView
        // 實測（HNR320T）要 6.6ms，整個送字路徑 12ms；之後重用只要 0.5ms。
        // 移到建構時做，電子紙上第一次出聯想才不會多一次明顯的停頓。
        repeat(11) { obtainStackView(it).visibility = View.GONE }

        updateToolbarVisibility()
    }

    /// 空閒（無組字、無候選）顯示工具列；**組字候選**預設整條佔滿，工具列讓位。
    /// **聯想詞**則是覆蓋在工具列上：蓋不到的工具列鍵仍看得見、點得到（點下去會順手收掉
    /// 聯想，由 service 處理），而且右側固定留一顆鍵的寬度 —— 聯想再長也吃不掉最後那顆，
    /// 「收折鍵盤 ∨」這種隨時要點得到的鍵才不會被聯想列鎖住。
    /// 開了「組字候選時保留工具列」偏好，組字候選也走同一套覆蓋（組字標籤壓住第一顆鍵）。
    private fun updateToolbarVisibility() {
        val idle = composingLabel.text.isNullOrEmpty() && activeStackViews == 0
        val overlay = !idle && (Prefs.keepToolbarWithCandidates || (lastSuggestions && activeStackViews > 0))
        toolbarStack.visibility = if (idle || overlay) View.VISIBLE else View.GONE
        scrollView.visibility = if (idle) View.GONE else View.VISIBLE
        applyOverlayGeometry(overlay)
        updateOverflowHint()
    }

    /// › 指示的顯示條件與位置：貼齊捲動區右緣（覆蓋模式時避開右側保留的那顆工具列鍵）
    private fun updateOverflowHint() {
        val show = scrollView.visibility == View.VISIBLE && scrollView.canScrollHorizontally(1)
        if (show) {
            val right = (scrollView.layoutParams as? LayoutParams)?.rightMargin ?: 0
            val lp = overflowHint.layoutParams as LayoutParams
            if (lp.rightMargin != right) { lp.rightMargin = right; overflowHint.layoutParams = lp }
        }
        overflowHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    /// 覆蓋模式：捲動區寬度改 WRAP_CONTENT（右邊空出來的地方讓觸控落到工具列），
    /// 並保留右側一顆鍵的寬度當上限；有組字碼時標籤撐到至少一顆鍵寬，把底下的米/英整顆遮住
    /// （標籤左緣 10dp、工具列左緣 8dp，差 2dp）
    private fun applyOverlayGeometry(overlay: Boolean) {
        val minW = if (overlay && !composingLabel.text.isNullOrEmpty()) (toolbarSlotWidth() - dp(2f)).coerceAtLeast(0) else 0
        if (composingLabel.minWidth != minW) composingLabel.minWidth = minW
        applyComposingMargin()
        val lp = scrollView.layoutParams as? LayoutParams ?: return
        val w = if (overlay) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
        val right = if (overlay) toolbarSlotWidth() else 0
        if (lp.width != w || lp.rightMargin != right) {
            lp.width = w
            lp.rightMargin = right
            scrollView.layoutParams = lp
        }
    }

    /// 一顆工具列鍵的寬度（等權重平分整條列）— 尚未 layout 時先用 48dp 近似，
    /// onSizeChanged 拿到真實寬度後再修正
    private fun toolbarSlotWidth(): Int {
        val n = toolbarStack.childCount
        if (n == 0) return 0
        val usable = width - dp(8f) * 2
        return if (usable > 0) usable / n else dp(48f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (toolbarStack.visibility == View.VISIBLE && scrollView.visibility == View.VISIBLE) {
            applyOverlayGeometry(overlay = true)
        }
    }

    /// 對應 iOS 約束：候選捲動區起點 = composing 標籤右緣 + 8dp（標籤變長時跟著推移）
    private fun applyComposingMargin() {
        composingLabel.measure(
            View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED,
        )
        val m = dp(10f) + composingLabel.measuredWidth + dp(8f)
        (scrollView.layoutParams as? LayoutParams)?.let { lp ->
            if (lp.leftMargin != m) {
                lp.leftMargin = m
                scrollView.layoutParams = lp
            }
        }
    }

    fun setComposing(text: String) {
        if (composingLabel.text?.toString() == text) return  // 未變就不量測/不重排
        composingLabel.text = text
        updateToolbarVisibility()  // 內含 applyComposingMargin
    }

    /// 從 stack 取第 index 個 TextView 重用，不夠才建（取代每鍵擊 removeAllViews + new）
    private fun obtainStackView(index: Int): TextView {
        while (stack.childCount <= index) {
            val v = TextView(context)
            v.gravity = Gravity.CENTER
            v.isClickable = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(4f)
            stack.addView(v, lp)
        }
        val v = stack.getChildAt(index) as TextView
        v.visibility = View.VISIBLE
        return v
    }

    fun setCandidates(candidates: List<String>, suggestions: Boolean) {
        // 內容未變就完全不動 — refreshIdleBar 常以空列表重複呼叫
        if (suggestions == lastSuggestions && candidates == lastCandidates) return
        lastCandidates = ArrayList(candidates)
        lastSuggestions = suggestions

        scrollView.scrollTo(0, 0)
        var slot = 0

        // 聯想列開頭放 ✕ — 不需要聯想時一鍵關閉（不影響已輸出文字）
        if (suggestions && candidates.isNotEmpty()) {
            val x = obtainStackView(slot); slot += 1
            x.text = "✕"
            x.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            x.setTextColor(KeyboardTheme.textSub)
            x.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            x.background = null
            x.contentDescription = "清除聯想"
            x.setOnClickListener { onDismissSuggestions?.invoke() }
        }

        // 候選 1–2 個時不顯示數字前綴（與 macOS 版一致的精簡顯示）
        val showIndex = candidates.size > 2 && !suggestions
        for ((i, c) in candidates.withIndex()) {
            val b = obtainStackView(slot); slot += 1
            b.text = if (showIndex && i < 9) "${i + 1} $c" else c
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            b.setTextColor(
                if (suggestions) 0xFF2F7CF6.toInt()
                else KeyboardTheme.candidateText
            )
            b.setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            b.contentDescription = null
            if (i == 0 && !suggestions && candidates.size > 2) {
                b.setTextColor(KeyboardTheme.candidateSelectedText)
                val bg = GradientDrawable()
                bg.setColor(KeyboardTheme.candidateSelectedBackground)
                bg.cornerRadius = dp(6f).toFloat()
                bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.border)
                b.background = bg
            } else {
                b.background = null
            }
            val idx = i
            b.setOnClickListener { onSelect?.invoke(idx) }
        }

        activeStackViews = slot
        for (j in slot until stack.childCount) stack.getChildAt(j).visibility = View.GONE
        updateToolbarVisibility()
    }
}
