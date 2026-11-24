package com.umitm0d.tvizle

/** Güncelleme akışının saf (Android'siz) yardımcıları — birim testlenebilsin diye ayrıldı. */
object UpdateUtil {
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** Küçük harf, 64 hex karakter mi? */
    fun isValidSha256(s: String): Boolean = SHA256.matches(s)

    /** "v107" -> 107; sayı yoksa -1. */
    fun codeFromTag(tag: String): Int = Regex("\\d+").find(tag)?.value?.toIntOrNull() ?: -1

    /** "5.6" -> 506, "5.10" -> 510 (version.txt: MAJOR.MINOR, minor 1-2 hane). Geçersizse -1. */
    fun versionCode(name: String): Int {
        val m = Regex("^(\\d+)\\.(\\d{1,2})$").matchEntire(name.trim()) ?: return -1
        return m.groupValues[1].toInt() * 100 + m.groupValues[2].toInt()
    }
}
