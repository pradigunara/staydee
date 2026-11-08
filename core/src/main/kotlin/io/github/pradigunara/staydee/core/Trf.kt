package io.github.pradigunara.staydee.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

// TRF1 layout (little-endian, as Python struct "<"):
//   header 24 B: "TRF1" | int32 accuracy | int32 shakiness | int32 stepsize | double mincontrast
//   per frame:   int32 frame | int32 count, then count x motion
//   per motion (26 B): int16 dx, dy, px, py, field_size | double contrast | double quality
private const val HEADER_SIZE = 24
private const val FRAME_HEADER_SIZE = 8
private const val MOTION_SIZE = 26
private const val INT16_BOUND = 32767.0

private val TRF1_MAGIC = "TRF1".toByteArray(Charsets.US_ASCII)

/**
 * Rescale a binary TRF1 transform file by [k]. Only dx, dy, px, py are scaled
 * (half-to-even rounding); field size and both doubles are copied verbatim.
 * Refuses (does not clamp) a scaled value outside ±32767, and fails on any
 * truncation or trailing bytes.
 */
fun scaleTrf(data: ByteArray, k: Double): ByteArray {
    require(data.size >= TRF1_MAGIC.size && data.copyOfRange(0, TRF1_MAGIC.size).contentEquals(TRF1_MAGIC)) {
        "not a TRF1 transform file"
    }
    require(data.size >= HEADER_SIZE) { "truncated TRF1 header" }
    val out = data.copyOf()
    val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    var pos = HEADER_SIZE
    while (pos < out.size) {
        require(pos + FRAME_HEADER_SIZE <= out.size) { "truncated frame header" }
        val count = buf.getInt(pos + 4)
        pos += FRAME_HEADER_SIZE
        for (motion in 0 until count) {  // negative count: no motions, as in Python
            require(pos + MOTION_SIZE <= out.size) { "truncated local motion" }
            for (field in 0 until 4) {
                val at = pos + field * 2
                val scaled = Math.rint(buf.getShort(at).toDouble() * k)
                require(scaled in -INT16_BOUND..INT16_BOUND) { "scaled value $scaled overflows int16 at k=$k" }
                buf.putShort(at, scaled.toInt().toShort())
            }
            pos += MOTION_SIZE
        }
    }
    return out
}

/**
 * The transform bytes pass 2 reads: [trf] itself at k == 1, else a copy
 * rescaled by k. Throws [IllegalArgumentException] for a non-TRF1 (ASCII) file
 * that needs rescaling.
 */
fun renderTrf(trf: ByteArray, k: Double): ByteArray = if (k == 1.0) trf else scaleTrf(trf, k)

/** The `<output>.trf.json` sidecar content (Python `json.dump({"long_edge": N})`). */
fun sidecarJson(longEdge: Int): String = "{\"long_edge\": $longEdge}"

/**
 * Scale a reused trf needs to act on [renderLong] frames.
 * [k] = renderLong / trfLong; [fromSidecar] says whether the edge came from a
 * sidecar. Without a sidecar the file is taken to match the render already.
 */
data class TrfReuse(val k: Double, val trfLong: Long, val fromSidecar: Boolean)

/**
 * [sidecar] is the content of `<trf>.json`, or null when that file does not
 * exist. Throws [IllegalArgumentException] for malformed JSON or a sidecar that
 * lacks a positive integer `long_edge` (same accept/reject set as Python's
 * `json.load` + `isinstance(edge, int)`, where JSON booleans count as ints).
 */
fun trfReuse(sidecar: String?, renderLong: Int): TrfReuse {
    if (sidecar == null) return TrfReuse(1.0, renderLong.toLong(), false)
    val meta = JsonReader(sidecar).readDocument()
    val edge: Long = when (val raw = (meta as? Map<*, *>)?.get("long_edge")) {
        is BigInteger -> {
            require(raw.bitLength() < 63) { "long_edge out of range" }
            raw.toLong()
        }
        true -> 1L
        false -> 0L
        else -> throw IllegalArgumentException("sidecar has no positive integer long_edge")
    }
    require(edge > 0) { "sidecar has no positive integer long_edge" }
    return TrfReuse(renderLong.toDouble() / edge, edge, true)
}

/** Strict JSON reader: enough to validate the sidecar exactly like Python's json module. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun readDocument(): Any? {
        skipWs()
        val value = readValue()
        skipWs()
        require(i == s.length) { "extra data after JSON value" }
        return value
    }

    private fun readValue(): Any? {
        val c = peek()
        return when {
            c == '{' -> readObject()
            c == '[' -> readArray()
            c == '"' -> readString()
            c == 't' -> literal("true", true)
            c == 'f' -> literal("false", false)
            c == 'n' -> literal("null", null)
            c == '-' || c in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("unexpected character '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        i++
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') {
            i++
            return map
        }
        while (true) {
            skipWs()
            require(peek() == '"') { "expected object key" }
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            val sep = next()
            if (sep == '}') return map
            require(sep == ',') { "expected ',' or '}'" }
        }
    }

    private fun readArray(): List<Any?> {
        i++
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') {
            i++
            return list
        }
        while (true) {
            skipWs()
            list += readValue()
            skipWs()
            val sep = next()
            if (sep == ']') return list
            require(sep == ',') { "expected ',' or ']'" }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> when (val e = next()) {
                    '"', '\\', '/' -> sb.append(e)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        require(i + 4 <= s.length) { "truncated unicode escape" }
                        val hex = s.substring(i, i + 4)
                        require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "bad unicode escape" }
                        sb.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> throw IllegalArgumentException("bad escape '\\$e'")
                }
                c < ' ' -> throw IllegalArgumentException("control character in string")
                else -> sb.append(c)
            }
        }
    }

    private fun readNumber(): Any {
        val start = i
        if (peek() == '-') i++
        if (peek() == '0') {
            i++
        } else {
            require(peek() in '1'..'9') { "bad number" }
            while (i < s.length && s[i] in '0'..'9') i++
        }
        var fractional = false
        if (i < s.length && s[i] == '.') {
            fractional = true
            i++
            require(i < s.length && s[i] in '0'..'9') { "bad fraction" }
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            fractional = true
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            require(i < s.length && s[i] in '0'..'9') { "bad exponent" }
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val text = s.substring(start, i)
        return if (fractional) text.toDouble() else BigInteger(text)
    }

    private fun literal(word: String, value: Any?): Any? {
        require(s.startsWith(word, i)) { "unexpected token" }
        i += word.length
        return value
    }

    private fun peek(): Char {
        require(i < s.length) { "unexpected end of JSON" }
        return s[i]
    }

    private fun next(): Char = peek().also { i++ }

    private fun expect(c: Char) {
        require(next() == c) { "expected '$c'" }
    }

    private fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }
}
