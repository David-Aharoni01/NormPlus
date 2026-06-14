package com.norm2hacked.protocol.util

import java.text.Bidi

/**
 * Byte-faithful port of the original NORM app's `cn.appscomm.util.BidiUtil`.
 *
 * The watch firmware does **no** bidirectional reordering of its own: it draws the
 * title/content bytes strictly left-to-right in the order received. So RTL scripts
 * (Hebrew, Arabic) arrive reversed unless we pre-transform the string into **visual
 * order** before sending. The original companion app does exactly this, calling
 * [formatRtlString] on every notification title/content before framing the packet.
 *
 * The transform has two stages, matching the smali:
 *   1. [reshape] — Arabic contextual glyph shaping (isolated/initial/medial/final
 *      presentation forms, U+FE80+). A no-op for non-Arabic text, including Hebrew.
 *   2. The Unicode Bidi algorithm via [java.text.Bidi]: split into runs, reorder them
 *      visually, and reverse each right-to-left (odd-level) run.
 *
 * `java.text.Bidi` is available on both the plain JVM and Android (API 30+), so this
 * stays in the pure-JVM `:protocol` module with no Android dependency.
 */
object BidiUtil {

    private const val ARABIC_START = 0x600
    private const val ARABIC_END = 0x6FF

    /**
     * Faithful port of `BidiUtil.formatRtlString`. Returns [s] transformed into visual
     * order (Arabic-reshaped, then bidi-reordered with RTL runs reversed). Purely
     * left-to-right text is returned unchanged (after reshaping). Any failure during the
     * bidi stage falls back to the reshaped string — never throws.
     */
    fun formatRtlString(s: String): String {
        if (s.isEmpty()) return s

        var text = reshape(s)
        try {
            val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            if (bidi.isLeftToRight) return text

            val runCount = bidi.runCount
            val levels = ByteArray(runCount)
            val runs = arrayOfNulls<Any>(runCount)
            for (i in 0 until runCount) {
                levels[i] = bidi.getRunLevel(i).toByte()
                runs[i] = i
            }

            Bidi.reorderVisually(levels, 0, runs, 0, runCount)

            val out = StringBuilder()
            for (i in 0 until runCount) {
                val idx = runs[i] as Int
                val run = text.substring(bidi.getRunStart(idx), bidi.getRunLimit(idx))
                if (levels[idx].toInt() and 0x1 != 0) {
                    out.append(StringBuilder(run).reverse().toString())
                } else {
                    out.append(run)
                }
            }
            text = out.toString()
        } catch (_: Exception) {
            // Mirror the original: on any bidi failure, fall back to the reshaped string.
        }
        return text
    }

    /**
     * Port of `BidiUtil.reshape`: replace each Arabic character with its context-sensitive
     * presentation form based on whether the neighbouring characters connect to it.
     * Non-Arabic characters pass through unchanged.
     */
    private fun reshape(s: String): String {
        val chars = s.toCharArray()
        val out = CharArray(chars.size)
        for (i in chars.indices) {
            val c = chars[i]
            if (isArabic(c)) {
                val prev = if (i > 0) chars[i - 1] else '\u0000'
                val next = if (i < chars.size - 1) chars[i + 1] else '\u0000'
                out[i] = getArabicForm(c, canConnectNext(prev), canConnectPrev(next))
            } else {
                out[i] = c
            }
        }
        return String(out)
    }

    private fun isArabic(c: Char): Boolean = c.code in ARABIC_START..ARABIC_END

    /**
     * Port of `BidiUtil.canConnectNext`: whether [c] can join the following character.
     * Non-Arabic characters and the right-joining-only letters (alef, waw, etc.) cannot.
     */
    private fun canConnectNext(c: Char): Boolean {
        if (!isArabic(c)) return false
        return when (c.code) {
            0x627, 0x648, 0x671 -> false       // alef, waw, alef-wasla
            in 0x621..0x625 -> false           // hamza, alef variants
            in 0x62F..0x632 -> false           // dal, thal, reh, zain
            else -> true
        }
    }

    /**
     * Port of `BidiUtil.canConnectPrev`: whether [c] can join the preceding character.
     * True for any Arabic letter except the standalone hamza (0x621).
     */
    private fun canConnectPrev(c: Char): Boolean = isArabic(c) && c.code != 0x621

    /**
     * Port of `BidiUtil.getArabicForm`. Each table row is
     * `[baseChar, isolated, final, initial, medial]`. [connPrev] is whether the previous
     * char connects forward to this one, [connNext] whether the next char connects back.
     *   - connPrev && connNext → medial
     *   - connPrev            → final
     *   - connNext            → initial
     *   - neither             → isolated
     * Characters not in the table are returned unchanged.
     */
    private fun getArabicForm(c: Char, connPrev: Boolean, connNext: Boolean): Char {
        for (row in ARABIC_FORMS) {
            if (row[0] == c.code) {
                val form = when {
                    connPrev && connNext -> row[4]
                    connPrev -> row[2]
                    connNext -> row[3]
                    else -> row[1]
                }
                return form.toChar()
            }
        }
        return c
    }

    // [base, isolated, final, initial, medial] — transcribed verbatim from the smali table.
    private val ARABIC_FORMS = arrayOf(
        intArrayOf(0x622, 0xFE81, 0xFE82, 0xFE81, 0xFE82),
        intArrayOf(0x623, 0xFE83, 0xFE84, 0xFE83, 0xFE84),
        intArrayOf(0x624, 0xFE85, 0xFE86, 0xFE85, 0xFE86),
        intArrayOf(0x625, 0xFE87, 0xFE88, 0xFE87, 0xFE88),
        intArrayOf(0x626, 0xFE89, 0xFE8A, 0xFE8B, 0xFE8C),
        intArrayOf(0x627, 0xFE8D, 0xFE8E, 0xFE8D, 0xFE8E),
        intArrayOf(0x628, 0xFE8F, 0xFE90, 0xFE91, 0xFE92),
        intArrayOf(0x629, 0xFE93, 0xFE94, 0xFE93, 0xFE94),
        intArrayOf(0x62A, 0xFE95, 0xFE96, 0xFE97, 0xFE98),
        intArrayOf(0x62B, 0xFE99, 0xFE9A, 0xFE9B, 0xFE9C),
        intArrayOf(0x62C, 0xFE9D, 0xFE9E, 0xFE9F, 0xFEA0),
        intArrayOf(0x62D, 0xFEA1, 0xFEA2, 0xFEA3, 0xFEA4),
        intArrayOf(0x62E, 0xFEA5, 0xFEA6, 0xFEA7, 0xFEA8),
        intArrayOf(0x62F, 0xFEA9, 0xFEAA, 0xFEA9, 0xFEAA),
        intArrayOf(0x630, 0xFEAB, 0xFEAC, 0xFEAB, 0xFEAC),
        intArrayOf(0x631, 0xFEAD, 0xFEAE, 0xFEAD, 0xFEAE),
        intArrayOf(0x632, 0xFEAF, 0xFEB0, 0xFEAF, 0xFEB0),
        intArrayOf(0x633, 0xFEB1, 0xFEB2, 0xFEB3, 0xFEB4),
        intArrayOf(0x634, 0xFEB5, 0xFEB6, 0xFEB7, 0xFEB8),
        intArrayOf(0x635, 0xFEB9, 0xFEBA, 0xFEBB, 0xFEBC),
        intArrayOf(0x636, 0xFEBD, 0xFEBE, 0xFEBF, 0xFEC0),
        intArrayOf(0x637, 0xFEC1, 0xFEC2, 0xFEC3, 0xFEC4),
        intArrayOf(0x638, 0xFEC5, 0xFEC6, 0xFEC7, 0xFEC8),
        intArrayOf(0x639, 0xFEC9, 0xFECA, 0xFECB, 0xFECC),
        intArrayOf(0x63A, 0xFECD, 0xFECE, 0xFECF, 0xFED0),
        intArrayOf(0x641, 0xFED1, 0xFED2, 0xFED3, 0xFED4),
        intArrayOf(0x642, 0xFED5, 0xFED6, 0xFED7, 0xFED8),
        intArrayOf(0x643, 0xFED9, 0xFEDA, 0xFEDB, 0xFEDC),
        intArrayOf(0x644, 0xFEDD, 0xFEDE, 0xFEDF, 0xFEE0),
        intArrayOf(0x645, 0xFEE1, 0xFEE2, 0xFEE3, 0xFEE4),
        intArrayOf(0x646, 0xFEE5, 0xFEE6, 0xFEE7, 0xFEE8),
        intArrayOf(0x647, 0xFEE9, 0xFEEA, 0xFEEB, 0xFEEC),
        intArrayOf(0x648, 0xFEEE, 0xFEEF, 0xFEEE, 0xFEEF),
        intArrayOf(0x649, 0xFEEF, 0xFEF0, 0xFEEF, 0xFEF0),
        intArrayOf(0x64A, 0xFEF1, 0xFEF2, 0xFEF3, 0xFEF4),
    )
}
