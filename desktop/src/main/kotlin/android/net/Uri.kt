package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The parts of `android.net.Uri` this app uses, over `java.net.URI`. */
class Uri private constructor(private val raw: String) {

    private val parsed: URI? = runCatching { URI(raw) }.getOrElse {
        // Be as lenient as Android: encode what java.net.URI refuses.
        runCatching { URI(raw.replace(" ", "%20").replace("|", "%7C")) }.getOrNull()
    }

    val scheme: String? get() = parsed?.scheme
    val host: String? get() = parsed?.host
    val port: Int get() = parsed?.port ?: -1
    val path: String? get() = parsed?.path
    val query: String? get() = parsed?.rawQuery?.let(::decodeRaw)
    val fragment: String? get() = parsed?.fragment
    val authority: String? get() = parsed?.authority
    val pathSegments: List<String> get() = path.orEmpty().split('/').filter { it.isNotEmpty() }
    val lastPathSegment: String? get() = pathSegments.lastOrNull()
    val queryParameterNames: Set<String>
        get() = queryPairs().map { it.first }.toCollection(LinkedHashSet())

    fun getQueryParameter(key: String): String? = queryPairs().firstOrNull { it.first == key }?.second
    fun getQueryParameters(key: String): List<String> = queryPairs().filter { it.first == key }.map { it.second }

    private fun queryPairs(): List<Pair<String, String>> =
        parsed?.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val i = it.indexOf('=')
            if (i < 0) decodeRaw(it) to "" else decodeRaw(it.substring(0, i)) to decodeRaw(it.substring(i + 1))
        }

    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is Uri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()

    companion object {
        @JvmField val EMPTY = Uri("")

        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)

        @JvmStatic fun encode(s: String?): String? = encode(s, null)

        @JvmStatic fun encode(s: String?, allow: String?): String? {
            if (s == null) return null
            val unreserved = "-_.!~*'()"
            val sb = StringBuilder()
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in unreserved || (allow != null && c in allow)) {
                    sb.append(c)
                } else {
                    sb.append('%').append("%02X".format(b.toInt() and 0xff))
                }
            }
            return sb.toString()
        }

        @JvmStatic fun decode(s: String?): String? = s?.let(::decodeRaw)

        private fun decodeRaw(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8) }.getOrDefault(s)

        @Suppress("unused")
        private fun encodeForm(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
    }
}
