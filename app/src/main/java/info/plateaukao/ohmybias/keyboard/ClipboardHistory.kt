package info.plateaukao.ohmybias.keyboard

import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.ClipboardBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/// 多筆剪貼簿紀錄與「尚未貼上」狀態管理：
/// 1. 記錄多筆複製過的文字（上限 36 筆，支援釘選與單筆/批次刪除，持久化於 clipboard_history.json）
/// 2. 追蹤最新一筆複製文字是否尚未被「貼上」（unpastedText）：
///    有新複製文字且尚未貼上時，候選列可彈出快速貼上膠囊；一旦使用者點擊貼上即消除，不再重複彈出。
object ClipboardHistory {
    private const val MAX_ITEMS = 36
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ohmybias-clipboard") }

    data class Entry(
        val text: String,
        val pinned: Boolean = false,
    )

    enum class Category(val label: String) {
        ALL("全部"),
        ADDRESS("地址"),
        PHONE("電話"),
        WEB("網站"),
        EMAIL("電子郵件"),
    }

    private val entries = mutableListOf<Entry>()
    private var lastSeenClipText: String? = null
    private var unpastedClipText: String? = null
    private var loaded = false

    /// 狀態變更回呼（由 IME service 掛載以即時刷新候選列或剪貼簿面板）
    var onChanged: (() -> Unit)? = null

    private fun file() = File(AppEnv.sharedDir, "clipboard_history.json")

    @Synchronized
    fun all(): List<Entry> {
        loadIfNeeded()
        // 已釘選排前面或維持最新在前（最新在前最符合圖1直覺）
        return entries.toList()
    }

    @Synchronized
    fun filtered(category: Category, pinnedOnly: Boolean = false): List<Entry> {
        loadIfNeeded()
        val base = if (pinnedOnly) entries.filter { it.pinned } else entries.toList()
        return when (category) {
            Category.ALL -> base
            Category.ADDRESS -> base.filter { isAddress(it.text) }
            Category.PHONE -> base.filter { isPhone(it.text) }
            Category.WEB -> base.filter { isUrl(it.text) }
            Category.EMAIL -> base.filter { isEmail(it.text) }
        }
    }

    /// 目前尚未貼上的第一筆文字（若無或已貼上過則回 null）
    @Synchronized
    fun unpastedText(): String? {
        loadIfNeeded()
        return unpastedClipText?.takeIf { it.isNotBlank() }
    }

    /// 系統剪貼簿變更事件（OnPrimaryClipChangedListener 觸發）：視為全新複製，啟用未貼上提示
    fun onSystemClipChanged(rawText: String?) {
        val text = rawText?.takeIf { it.isNotBlank() } ?: return
        var changed = false
        synchronized(this) {
            loadIfNeeded()
            insertOrMoveToTop(text)
            lastSeenClipText = text
            unpastedClipText = text
            saveAsync()
            changed = true
        }
        if (changed) onChanged?.invoke()
    }

    /// 每次進入輸入框時與系統剪貼簿同步（補捉背景複製但未收到 listener 的情況）
    fun syncFromSystem() {
        val current = ClipboardBridge.plainText()?.takeIf { it.isNotBlank() } ?: return
        var changed = false
        synchronized(this) {
            loadIfNeeded()
            if (current != lastSeenClipText) {
                insertOrMoveToTop(current)
                lastSeenClipText = current
                unpastedClipText = current
                saveAsync()
                changed = true
            } else if (entries.none { it.text == current }) {
                insertOrMoveToTop(current)
                saveAsync()
                changed = true
            }
        }
        if (changed) onChanged?.invoke()
    }

    /// 使用者已執行「貼上」（或主動關閉提示）→ 不再顯示快速貼上小窗
    fun markPasted() {
        var changed = false
        synchronized(this) {
            loadIfNeeded()
            if (unpastedClipText != null) {
                unpastedClipText = null
                saveAsync()
                changed = true
            }
        }
        if (changed) onChanged?.invoke()
    }

    /// 切換某筆紀錄的釘選狀態
    fun togglePin(text: String) {
        synchronized(this) {
            loadIfNeeded()
            val idx = entries.indexOfFirst { it.text == text }
            if (idx < 0) return
            val cur = entries[idx]
            entries[idx] = cur.copy(pinned = !cur.pinned)
            saveAsync()
        }
        onChanged?.invoke()
    }

    /// 刪除單筆紀錄
    fun remove(text: String) {
        synchronized(this) {
            loadIfNeeded()
            entries.removeAll { it.text == text }
            if (unpastedClipText == text) unpastedClipText = null
            saveAsync()
        }
        onChanged?.invoke()
    }

    /// 清除所有未釘選紀錄
    fun clearUnpinned() {
        synchronized(this) {
            loadIfNeeded()
            val pinnedTexts = entries.filter { it.pinned }.map { it.text }.toSet()
            entries.removeAll { !it.pinned }
            if (unpastedClipText != null && unpastedClipText !in pinnedTexts) {
                unpastedClipText = null
            }
            saveAsync()
        }
        onChanged?.invoke()
    }

    private fun insertOrMoveToTop(text: String) {
        val existing = entries.firstOrNull { it.text == text }
        val pinned = existing?.pinned ?: false
        entries.removeAll { it.text == text }
        entries.add(0, Entry(text = text, pinned = pinned))
        while (entries.size > MAX_ITEMS) {
            val rmIdx = entries.indexOfLast { !it.pinned }
            if (rmIdx >= 0) entries.removeAt(rmIdx) else break
        }
    }

    private fun saveAsync() {
        val root = JSONObject()
        root.put("lastSeen", lastSeenClipText ?: JSONObject.NULL)
        root.put("unpasted", unpastedClipText ?: JSONObject.NULL)
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("text", e.text)
            obj.put("pinned", e.pinned)
            arr.put(obj)
        }
        root.put("items", arr)
        val snapshot = root.toString()
        executor.execute {
            try { file().writeText(snapshot, Charsets.UTF_8) } catch (_: Exception) {}
        }
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            lastSeenClipText = root.optString("lastSeen", "").takeIf { it.isNotEmpty() && !root.isNull("lastSeen") }
            unpastedClipText = root.optString("unpasted", "").takeIf { it.isNotEmpty() && !root.isNull("unpasted") }
            val arr = root.optJSONArray("items") ?: return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val t = obj.optString("text", "")
                if (t.isNotBlank() && entries.size < MAX_ITEMS) {
                    entries.add(Entry(text = t, pinned = obj.optBoolean("pinned", false)))
                }
            }
        } catch (_: Exception) {}
    }

    // MARK: - 分類規則（全部 / 地址 / 電話 / 網站 / 電子郵件）

    private val urlRegex = Regex("""(?i)(https?://|www\.)\S+""")
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val phoneRegex = Regex("""(?<!\d)(\+?\d{1,3}[\s\-]?)?(0\d{1,3}[\s\-]?\d{3,4}[\s\-]?\d{3,4}|\d{4}[\s\-]?\d{3}[\s\-]?\d{3})(?!\d)""")
    private val addressHintRegex = Regex("""(縣|市|區|鄉|鎮|路|街|段|巷|弄|號|樓|大道)""")

    private fun isUrl(s: String): Boolean = urlRegex.containsMatchIn(s)
    private fun isEmail(s: String): Boolean = emailRegex.containsMatchIn(s)
    private fun isPhone(s: String): Boolean = !isUrl(s) && phoneRegex.containsMatchIn(s)
    private fun isAddress(s: String): Boolean = !isUrl(s) && addressHintRegex.containsMatchIn(s)
}
