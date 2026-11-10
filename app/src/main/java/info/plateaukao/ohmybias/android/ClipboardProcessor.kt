package info.plateaukao.ohmybias.android

import android.content.ClipboardManager
import android.content.Context
import android.icu.text.Transliterator
import android.os.Build
import info.plateaukao.ohmybias.keyboard.ClipboardHistory
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.ClipboardBridge
import org.json.JSONObject
import java.io.File

/// 剪貼簿 + 簡繁轉換 — 掛上 shared/ClipboardBridge。
/// 簡繁轉換用 ICU Transliterator（Hans-Hant），對應 iOS 的 StringTransform("Hans-Hant")。
/// android.icu 要 API 29；Android 9（API 28）改用隨 app 附的 s2t.json／t2s.json
/// 逐字對照（即 CINTable 用的同一份表）。
object ClipboardProcessor {

    private val useIcu = Build.VERSION.SDK_INT >= 29

    /// API 28 fallback 用 — 首次轉換時才讀（ICU 可用時完全不碰）
    private val charMaps = HashMap<String, Map<String, String>>()

    fun install(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ClipboardBridge.plainText = {
            try {
                cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            } catch (e: Exception) {
                null
            }
        }
        try {
            cm.addPrimaryClipChangedListener {
                val text = ClipboardBridge.plainText()
                if (!text.isNullOrBlank()) {
                    ClipboardHistory.onSystemClipChanged(text)
                }
            }
        } catch (_: Exception) {}
        ClipboardBridge.toTraditional = { text -> transliterate("Hans-Hant", "s2t", text) }
        ClipboardBridge.toSimplified = { text -> transliterate("Hant-Hans", "t2s", text) }
    }

    private fun transliterate(icuId: String, mapName: String, text: String): String = try {
        if (useIcu) Transliterator.getInstance(icuId).transliterate(text)
        else mapChars(charMap(mapName), text)
    } catch (e: Exception) {
        text
    }

    /// 逐 code point 查表；查不到就原樣保留（非漢字、以及表上沒有的字）
    private fun mapChars(map: Map<String, String>, text: String): String {
        if (map.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val ch = text.substring(i, i + n)
            sb.append(map[ch] ?: ch)
            i += n
        }
        return sb.toString()
    }

    private fun charMap(name: String): Map<String, String> = charMaps.getOrPut(name) {
        try {
            val f = File(AppEnv.sharedDir, "$name.json")
            if (!f.exists()) return@getOrPut emptyMap()
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val r = HashMap<String, String>()
            for (key in json.keys()) r[key] = json.getString(key)
            r
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
