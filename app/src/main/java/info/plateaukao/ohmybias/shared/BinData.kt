package info.plateaukao.ohmybias.shared

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/// 對應 iOS 的 Data + u16/u32 helper — mmap 二進位檔的 little-endian 讀取。
/// .bin 索引常是 6-byte stride，offset 不保證對齊 — ByteBuffer 的 getShort/getInt
/// 本身容許非對齊存取，行為與 iOS 的 loadUnaligned 相同。越界一律回 0（同 iOS guard）。
class BinData private constructor(private val buf: ByteBuffer) {
    val count: Int = buf.limit()

    companion object {
        private val isWindowsHost: Boolean =
            System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

        /// mmap 檔案（對應 Data(mappedIfSafe)）；失敗回 null。
        /// Windows 本機跑 JUnit 測試時 NTFS 的 CreateFileMapping 會鎖死檔案導致測試無法覆寫或刪除 .bin，
        /// 故在 Windows host 改走整檔讀入；Android 實機（Linux）維持零複製 mmap。
        fun mapped(path: String): BinData? = try {
            if (isWindowsHost) {
                of(File(path))
            } else {
                RandomAccessFile(path, "r").use { raf ->
                    val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    map.order(ByteOrder.LITTLE_ENDIAN)
                    BinData(map)
                }
            }
        } catch (e: Exception) {
            null
        }

        /// 整檔讀入（對應 Data(contentsOf:)）
        fun of(bytes: ByteArray): BinData {
            val b = ByteBuffer.wrap(bytes)
            b.order(ByteOrder.LITTLE_ENDIAN)
            return BinData(b)
        }

        fun of(file: File): BinData? = try { of(file.readBytes()) } catch (e: Exception) { null }
    }

    fun u8(off: Int): Int {
        if (off < 0 || off >= count) return 0
        return buf.get(off).toInt() and 0xFF
    }

    fun u16(off: Int): Int {
        if (off < 0 || off + 2 > count) return 0
        return buf.getShort(off).toInt() and 0xFFFF
    }

    fun u32(off: Int): Long {
        if (off < 0 || off + 4 > count) return 0
        return buf.getInt(off).toLong() and 0xFFFFFFFFL
    }

    /// 取 [start, start+len) 位元組（越界回空）
    fun bytes(start: Int, len: Int): ByteArray {
        if (start < 0 || len < 0 || start + len > count) return ByteArray(0)
        val r = ByteArray(len)
        for (i in 0 until len) r[i] = buf.get(start + i)
        return r
    }

    fun asciiString(start: Int, len: Int): String = String(bytes(start, len), Charsets.US_ASCII)
    fun utf8String(start: Int, len: Int): String = String(bytes(start, len), Charsets.UTF_8)

    /// 自 start 讀 unitCount 個 UTF-16LE code unit 成字串（越界回空）
    fun utf16String(start: Int, unitCount: Int): String {
        if (start < 0 || unitCount < 0 || start + 2 * unitCount > count) return ""
        val chars = CharArray(unitCount) { buf.getChar(start + 2 * it) }
        return String(chars)
    }

    /// 自 start 讀 unitCount 個 UTF-16LE code unit，依 code point 切成一字一字串
    /// （surrogate pair 合成一字；越界或落單的 surrogate 略過）
    fun utf16Chars(start: Int, unitCount: Int): List<String> {
        if (start < 0 || unitCount < 0 || start + 2 * unitCount > count) return emptyList()
        val r = ArrayList<String>(unitCount)
        var i = 0
        while (i < unitCount) {
            val hi = buf.getChar(start + 2 * i)
            if (Character.isHighSurrogate(hi) && i + 1 < unitCount) {
                val lo = buf.getChar(start + 2 * (i + 1))
                if (Character.isLowSurrogate(lo)) { r.add(String(charArrayOf(hi, lo))); i += 2; continue }
            }
            if (!Character.isSurrogate(hi)) r.add(hi.toString())
            i++
        }
        return r
    }
}
