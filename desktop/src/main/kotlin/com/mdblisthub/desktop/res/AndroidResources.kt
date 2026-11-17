package com.mdblisthub.desktop.res

import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads the TV app's Android resources from the classpath (`/android-res/`),
 * where the Gradle build copies them untouched next to an id table.
 *
 * Strings follow Android's rules: `values-<lang>` first, then `values`; quotes,
 * escapes and whitespace collapsing as aapt does them; plurals by CLDR's
 * "one/other" for the four languages the app ships.
 */
object AndroidResources {

    private const val ROOT = "/android-res"

    private val modules: List<String> by lazy {
        stream("$ROOT/modules.txt")?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    }

    /** id -> (type, name) */
    private val ids: Map<Int, Pair<String, String>> by lazy {
        stream("$ROOT/ids.txt")?.bufferedReader()?.readLines().orEmpty().mapNotNull { line ->
            val (id, ref) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val (type, name) = ref.split('/', limit = 2)
            id.toInt() to (type to name)
        }.toMap()
    }

    private class Values(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>,
    )

    private val values = ConcurrentHashMap<String, Values>()

    private fun valuesFor(folder: String): Values = values.getOrPut(folder) {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val arrays = HashMap<String, List<String>>()
        modules.forEach { module ->
            listOf("strings.xml", "plurals.xml", "arrays.xml").forEach { fileName ->
                val input = stream("$ROOT/$module/$folder/$fileName") ?: return@forEach
                val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                    .newDocumentBuilder().parse(input)
                val children = doc.documentElement.childNodes
                for (i in 0 until children.length) {
                    val el = children.item(i) as? Element ?: continue
                    val name = el.getAttribute("name")
                    when (el.tagName) {
                        "string" -> strings[name] = unescape(textOf(el))
                        "plurals" -> {
                            val items = HashMap<String, String>()
                            el.getElementsByTagName("item").let { list ->
                                for (j in 0 until list.length) {
                                    val item = list.item(j) as Element
                                    items[item.getAttribute("quantity")] = unescape(textOf(item))
                                }
                            }
                            plurals[name] = items
                        }
                        "string-array" -> {
                            val items = mutableListOf<String>()
                            el.getElementsByTagName("item").let { list ->
                                for (j in 0 until list.length) items += unescape(textOf(list.item(j) as Element))
                            }
                            arrays[name] = items
                        }
                    }
                }
            }
        }
        Values(strings, plurals, arrays)
    }

    private fun folders(locale: Locale): List<String> = buildList {
        val lang = locale.language
        if (lang.isNotEmpty()) {
            if (locale.country.isNotEmpty()) add("values-$lang-r${locale.country}")
            add("values-$lang")
        }
        add("values")
    }

    private fun nameOf(id: Int, type: String): String {
        val (t, name) = ids[id] ?: error("Unknown resource id 0x${id.toString(16)}")
        require(t == type) { "Resource $name is a $t, not a $type" }
        return name
    }

    fun string(id: Int, locale: Locale): String {
        val name = nameOf(id, "string")
        return folders(locale).firstNotNullOfOrNull { valuesFor(it).strings[name] } ?: name
    }

    fun string(id: Int, locale: Locale, vararg args: Any?): String {
        val raw = string(id, locale)
        return if (args.isEmpty()) raw else runCatching { String.format(locale, raw, *args) }.getOrDefault(raw)
    }

    fun plural(id: Int, quantity: Int, locale: Locale, vararg args: Any?): String {
        val name = nameOf(id, "plurals")
        val forms = folders(locale).firstNotNullOfOrNull { valuesFor(it).plurals[name] } ?: return name
        val one = when (locale.language) {
            "pt", "fr" -> quantity == 0 || quantity == 1
            else -> quantity == 1
        }
        val raw = (if (quantity == 0) forms["zero"] else null)
            ?: (if (one) forms["one"] else null)
            ?: forms["other"] ?: forms.values.first()
        return if (args.isEmpty()) raw else runCatching { String.format(locale, raw, *args) }.getOrDefault(raw)
    }

    fun stringArray(id: Int, locale: Locale): Array<String> {
        val name = nameOf(id, "array")
        return (folders(locale).firstNotNullOfOrNull { valuesFor(it).arrays[name] } ?: emptyList()).toTypedArray()
    }

    /** Opens the file behind a drawable/font/raw id. */
    fun open(id: Int): InputStream {
        val (type, name) = ids[id] ?: error("Unknown resource id 0x${id.toString(16)}")
        return findFile(type, name)?.let { stream(it) } ?: error("No file for $type/$name")
    }

    fun extension(id: Int): String {
        val (type, name) = ids[id] ?: error("Unknown resource id 0x${id.toString(16)}")
        return findFile(type, name)?.substringAfterLast('.', "").orEmpty()
    }

    private val fileIndex = ConcurrentHashMap<String, String>()

    private fun findFile(type: String, name: String): String? = fileIndex["$type/$name"] ?: run {
        val folders = when (type) {
            "drawable" -> listOf("drawable-nodpi", "drawable", "drawable-xxhdpi", "drawable-xhdpi")
            "font" -> listOf("font")
            "raw" -> listOf("raw")
            else -> listOf(type)
        }
        val extensions = listOf("png", "webp", "jpg", "xml", "otf", "ttf", "mp4", "txt", "json")
        for (module in modules) for (folder in folders) for (ext in extensions) {
            val path = "$ROOT/$module/$folder/$name.$ext"
            if (AndroidResources::class.java.getResource(path) != null) {
                fileIndex["$type/$name"] = path
                return path
            }
        }
        null
    }

    private fun stream(path: String): InputStream? = AndroidResources::class.java.getResourceAsStream(path)

    /** Text content, with inline markup (`<b>`, `<xliff:g>`) flattened. */
    private fun textOf(node: Node): String = buildString {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> append(child.nodeValue)
                Node.ELEMENT_NODE -> append(textOf(child))
            }
        }
    }

    /** aapt2's rules: quoted runs keep whitespace, the rest collapses; backslash escapes. */
    internal fun unescape(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var lastWasSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val n = raw[i + 1]
                    when (n) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> if (i + 5 < raw.length) {
                            out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4
                        }
                        else -> out.append(n)
                    }
                    lastWasSpace = false
                    i += 2
                    continue
                }
                c == '"' -> { quoted = !quoted }
                c.isWhitespace() && !quoted -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                    i++
                    continue
                }
                else -> out.append(c)
            }
            lastWasSpace = false
            i++
        }
        return out.toString().trim()
    }
}
