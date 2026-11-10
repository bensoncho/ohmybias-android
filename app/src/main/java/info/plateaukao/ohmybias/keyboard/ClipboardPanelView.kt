package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import info.plateaukao.ohmybias.R

/// 多筆剪貼簿面板（對應圖 1）：
/// - 頂列：返回鍵盤圖示 `⌨ | 剪貼簿`、右側釘選篩選 `📌` 與清除未釘選 `🗑️`
/// - 分類列：`全部`、`地址`、`電話`、`網站`、`電子郵件`
/// - 內容區：三欄卡片格狀排列，點卡片直接貼上；點卡片右下角按鈕（或長按卡片）可釘選或刪除單筆。
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context) : FrameLayout(context) {

    var onPaste: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onClearUnpinned: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val accentBlue = 0xFF4C8DF6.toInt()
    private var currentCategory = ClipboardHistory.Category.ALL
    private var pinnedOnly = false
    /// 目前展開操作選單（釘選/刪除）的卡片文字
    private var expandedActionText: String? = null

    private val pinFilterButton: ImageView
    private val categoryViews = mutableMapOf<ClipboardHistory.Category, TextView>()
    private val gridContainer = LinearLayout(context)
    private val contentScroll = ScrollView(context)

    init {
        setBackgroundColor(KeyboardTheme.panelRightBackground)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 1. 頂部標題列：[⌨] | 剪貼簿 ...... [📌] [🗑️]
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(10f), 0, dp(10f), 0)
        header.setBackgroundColor(KeyboardTheme.toolbarBackground)

        val backBtn = ImageView(context)
        backBtn.setImageResource(R.drawable.ic_tb_keyboard)
        backBtn.imageTintList = ColorStateList.valueOf(KeyboardTheme.toolbarColor)
        backBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        val iconPad = dp(8f)
        backBtn.setPadding(iconPad, iconPad, iconPad, iconPad)
        backBtn.contentDescription = "返回鍵盤"
        backBtn.isClickable = true
        backBtn.setOnClickListener { onBack?.invoke() }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(38f), dp(38f)))

        val divider = View(context)
        divider.setBackgroundColor(0x44888888)
        header.addView(divider, LinearLayout.LayoutParams(dp(1f), dp(18f)).apply {
            leftMargin = dp(6f); rightMargin = dp(10f)
        })

        val title = TextView(context)
        title.text = "剪貼簿"
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        title.setTextColor(KeyboardTheme.toolbarColor)
        title.gravity = Gravity.CENTER_VERTICAL
        title.setOnClickListener { onBack?.invoke() }
        header.addView(title, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        pinFilterButton = ImageView(context)
        pinFilterButton.setImageResource(R.drawable.ic_tb_keep)
        pinFilterButton.scaleType = ImageView.ScaleType.FIT_CENTER
        pinFilterButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        pinFilterButton.contentDescription = "只看已釘選"
        pinFilterButton.isClickable = true
        pinFilterButton.setOnClickListener {
            pinnedOnly = !pinnedOnly
            expandedActionText = null
            updateHeaderAndTabs()
            reloadCards()
        }
        header.addView(pinFilterButton, LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
            rightMargin = dp(4f)
        })

        val clearBtn = ImageView(context)
        clearBtn.setImageResource(R.drawable.ic_tb_delete)
        clearBtn.imageTintList = ColorStateList.valueOf(KeyboardTheme.toolbarColor)
        clearBtn.scaleType = ImageView.ScaleType.FIT_CENTER
        clearBtn.setPadding(iconPad, iconPad, iconPad, iconPad)
        clearBtn.contentDescription = "清除未釘選紀錄"
        clearBtn.isClickable = true
        clearBtn.setOnClickListener {
            ClipboardHistory.clearUnpinned()
            expandedActionText = null
            onClearUnpinned?.invoke()
            reloadCards()
        }
        header.addView(clearBtn, LinearLayout.LayoutParams(dp(38f), dp(38f)))

        root.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40f)))

        // 2. 分類列：全部 / 地址 / 電話 / 網站 / 電子郵件
        val tabScroll = HorizontalScrollView(context)
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(KeyboardTheme.panelRightBackground)
        val tabRow = LinearLayout(context)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.gravity = Gravity.CENTER_VERTICAL
        tabRow.setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        for (cat in ClipboardHistory.Category.entries) {
            val tv = TextView(context)
            tv.text = cat.label
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            tv.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            tv.gravity = Gravity.CENTER
            tv.isClickable = true
            tv.setOnClickListener {
                currentCategory = cat
                expandedActionText = null
                updateHeaderAndTabs()
                reloadCards()
            }
            tabRow.addView(tv, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            categoryViews[cat] = tv
        }
        tabScroll.addView(tabRow, ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        root.addView(tabScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34f)))

        val sep = View(context)
        sep.setBackgroundColor(if (KeyboardTheme.isDark) 0x22FFFFFF else 0x18000000)
        root.addView(sep, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))

        // 3. 3 欄卡片捲動區
        contentScroll.isVerticalScrollBarEnabled = true
        gridContainer.orientation = LinearLayout.VERTICAL
        gridContainer.setPadding(dp(8f), dp(8f), dp(8f), dp(12f))
        contentScroll.addView(
            gridContainer,
            ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        root.addView(contentScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        updateHeaderAndTabs()
        reloadCards()
    }

    private fun updateHeaderAndTabs() {
        pinFilterButton.imageTintList = ColorStateList.valueOf(
            if (pinnedOnly) accentBlue else KeyboardTheme.toolbarColor
        )
        for ((cat, tv) in categoryViews) {
            val selected = cat == currentCategory
            tv.setTextColor(if (selected) accentBlue else KeyboardTheme.panelText)
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    fun reloadCards() {
        gridContainer.removeAllViews()
        val items = ClipboardHistory.filtered(currentCategory, pinnedOnly)
        if (items.isEmpty()) {
            val empty = TextView(context)
            empty.text = if (pinnedOnly) "尚無已釘選的剪貼簿紀錄" else "剪貼簿尚無紀錄"
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            empty.setTextColor(KeyboardTheme.textSub)
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(42f), 0, dp(42f))
            gridContainer.addView(empty, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }

        val cols = 3
        var rowIndex = 0
        while (rowIndex * cols < items.size) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            val rowLp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) rowLp.topMargin = dp(8f)

            for (c in 0 until cols) {
                val idx = rowIndex * cols + c
                val cellLp = LinearLayout.LayoutParams(0, dp(96f), 1f)
                if (c > 0) cellLp.leftMargin = dp(8f)
                if (idx < items.size) {
                    row.addView(buildCard(items[idx]), cellLp)
                } else {
                    row.addView(View(context), cellLp)
                }
            }
            gridContainer.addView(row, rowLp)
            rowIndex += 1
        }
    }

    private fun buildCard(entry: ClipboardHistory.Entry): View {
        val card = FrameLayout(context)
        val bg = GradientDrawable()
        val cardBgColor = if (KeyboardTheme.isDark) 0xFF1E1F22.toInt() else 0xFFF2F3F5.toInt()
        val strokeColor = when {
            entry.pinned -> accentBlue
            KeyboardTheme.isDark -> 0x33FFFFFF
            else -> 0x22000000
        }
        bg.setColor(cardBgColor)
        bg.cornerRadius = dp(12f).toFloat()
        bg.setStroke(dp(1f).coerceAtLeast(1), strokeColor)
        card.background = bg
        card.isClickable = true
        card.setOnClickListener {
            onPaste?.invoke(entry.text)
        }
        card.setOnLongClickListener {
            expandedActionText = if (expandedActionText == entry.text) null else entry.text
            reloadCards()
            true
        }

        val tv = TextView(context)
        tv.text = entry.text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        tv.setTextColor(KeyboardTheme.panelText)
        tv.maxLines = 4
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setPadding(dp(10f), dp(8f), dp(10f), dp(26f))
        card.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 已釘選角標
        if (entry.pinned) {
            val pinBadge = ImageView(context)
            pinBadge.setImageResource(R.drawable.ic_tb_keep)
            pinBadge.imageTintList = ColorStateList.valueOf(accentBlue)
            card.addView(pinBadge, LayoutParams(dp(14f), dp(14f)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(6f); rightMargin = dp(6f)
            })
        }

        // 右下角圓形選單按鈕（同圖 1 卡片右下角的圓形圖示）
        val menuBtn = ImageView(context)
        menuBtn.setImageResource(R.drawable.ic_tb_clipboard_list)
        menuBtn.imageTintList = ColorStateList.valueOf(
            if (KeyboardTheme.isDark) 0xFFCCCCCC.toInt() else 0xFF555555.toInt()
        )
        val circleBg = GradientDrawable()
        circleBg.shape = GradientDrawable.OVAL
        circleBg.setColor(if (KeyboardTheme.isDark) 0xFF33353A.toInt() else 0xFFE0E2E7.toInt())
        menuBtn.background = circleBg
        val mp = dp(5f)
        menuBtn.setPadding(mp, mp, mp, mp)
        menuBtn.isClickable = true
        menuBtn.setOnClickListener {
            expandedActionText = if (expandedActionText == entry.text) null else entry.text
            reloadCards()
        }
        card.addView(menuBtn, LayoutParams(dp(26f), dp(26f)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(6f); bottomMargin = dp(6f)
        })

        // 展開單筆操作覆蓋層（釘選 / 刪除）
        if (expandedActionText == entry.text) {
            val overlay = LinearLayout(context)
            overlay.orientation = LinearLayout.VERTICAL
            overlay.gravity = Gravity.CENTER
            val ovBg = GradientDrawable()
            ovBg.setColor(if (KeyboardTheme.isDark) 0xEE18191C.toInt() else 0xEEF8F9FA.toInt())
            ovBg.cornerRadius = dp(12f).toFloat()
            ovBg.setStroke(dp(1f).coerceAtLeast(1), accentBlue)
            overlay.background = ovBg
            overlay.isClickable = true
            overlay.setOnClickListener {
                expandedActionText = null
                reloadCards()
            }

            val pinBtn = TextView(context)
            pinBtn.text = if (entry.pinned) "取消釘選" else "📌 釘選"
            pinBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            pinBtn.setTextColor(accentBlue)
            pinBtn.gravity = Gravity.CENTER
            pinBtn.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            pinBtn.setOnClickListener {
                ClipboardHistory.togglePin(entry.text)
                expandedActionText = null
                reloadCards()
            }
            overlay.addView(pinBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            val line = View(context)
            line.setBackgroundColor(if (KeyboardTheme.isDark) 0x33FFFFFF else 0x22000000)
            overlay.addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1f)))

            val delBtn = TextView(context)
            delBtn.text = "🗑️ 刪除"
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
            delBtn.setTextColor(if (KeyboardTheme.isDark) 0xFFFF6B6B.toInt() else 0xFFD93025.toInt())
            delBtn.gravity = Gravity.CENTER
            delBtn.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            delBtn.setOnClickListener {
                ClipboardHistory.remove(entry.text)
                expandedActionText = null
                reloadCards()
            }
            overlay.addView(delBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            card.addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        return card
    }
}
