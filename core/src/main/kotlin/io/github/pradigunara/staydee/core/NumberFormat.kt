package io.github.pradigunara.staydee.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Port of `fmtnum`: `-1.0` -> `"-1"`, `0.25` -> `"0.25"`.
 *
 * Integral values print as integers; everything else prints like Python's
 * `repr(float)` (shortest round-trip digits; scientific form only below 1e-4,
 * e.g. `1e-05`), never like `Double.toString` (`1.0E-4`).
 */
fun fmtnum(value: Double): String =
    if (value.isFinite() && value == Math.rint(value)) BigDecimal(value).toBigInteger().toString()
    else pyFloatRepr(value)

/**
 * Python `parse_int10`: an optionally signed run of ASCII digits, surrounding
 * whitespace allowed. Throws [NumberFormatException] otherwise (or beyond [Int]).
 */
fun parseInt10(text: String): Int {
    val big = parseBigInt10(text)
    if (big.bitLength() > 31) throw NumberFormatException("out of Int range: $text")
    return big.toInt()
}

internal fun parseBigInt10(text: String): java.math.BigInteger {
    val s = text.trim()
    val digits = if (s.startsWith('+') || s.startsWith('-')) s.substring(1) else s
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) throw NumberFormatException("not an integer: '$text'")
    return java.math.BigInteger(s.removePrefix("+"), 10)
}

/** Python `repr(float)`. */
internal fun pyFloatRepr(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"

    val exact = BigDecimal(Math.abs(value))
    // shortest digit string that round-trips; BigDecimal rounding is exact
    var rounded = exact
    for (n in 1..17) {
        rounded = exact.round(MathContext(n, RoundingMode.HALF_EVEN))
        if (rounded.toDouble() == Math.abs(value)) break
    }
    val digits = rounded.unscaledValue().toString().trimEnd('0').ifEmpty { "0" }
    // value = 0.<digits> * 10^decpt
    val decpt = rounded.precision() - rounded.scale()

    val body = if (decpt <= -4 || decpt > 16) {
        val exp = decpt - 1
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val expAbs = Math.abs(exp).toString().padStart(2, '0')
        mantissa + "e" + (if (exp < 0) "-" else "+") + expAbs
    } else if (decpt <= 0) {
        "0." + "0".repeat(-decpt) + digits
    } else if (decpt >= digits.length) {
        digits + "0".repeat(decpt - digits.length) + ".0"
    } else {
        digits.substring(0, decpt) + "." + digits.substring(decpt)
    }
    return if (value < 0) "-$body" else body
}
