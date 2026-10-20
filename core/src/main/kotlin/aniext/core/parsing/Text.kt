package aniext.core.parsing

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.text.ParseException
import java.text.SimpleDateFormat

private val BLOCK_TAGS = setOf("p", "div", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "table", "tr")
private val INLINE_WHITESPACE = Regex("[ \\t\\u00A0]+")
private val EXCESS_NEWLINES = Regex("\n{3,}")

/**
 * Converts an HTML fragment (or plain text) to readable plain text: tags are dropped, entities
 * decoded, `<br>` and block elements become line breaks and existing newlines are kept.
 */
fun htmlToText(html: String): String {
    val out = StringBuilder()
    Jsoup.parseBodyFragment(html).body().traverse(
        object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> out.append(node.wholeText)
                    node is Element && node.normalName() == "br" -> out.append('\n')
                    node is Element && node.normalName() in BLOCK_TAGS -> out.append("\n\n")
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK_TAGS) out.append("\n\n")
            }
        },
    )
    return out.toString()
        .replace("\r\n", "\n")
        .lineSequence()
        .joinToString("\n") { it.replace(INLINE_WHITESPACE, " ").trim() }
        .replace(EXCESS_NEWLINES, "\n\n")
        .trim()
}

/**
 * Orders strings the way people expect file and episode names to sort: digit runs compare by
 * numeric value ("Part 2" < "Part 10"), everything else case-insensitively.
 */
val naturalOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numA = a.substring(startA, i).trimStart('0')
            val numB = b.substring(startB, j).trimStart('0')
            if (numA.length != numB.length) return@Comparator numA.length - numB.length
            val cmp = numA.compareTo(numB)
            if (cmp != 0) return@Comparator cmp
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return@Comparator cmp
            i++
            j++
        }
    }
    (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

/** Epoch milliseconds for [date], or 0 (the app's "unknown date") if absent or unparsable. */
fun SimpleDateFormat.tryParse(date: String?): Long {
    if (date.isNullOrBlank()) return 0L
    return try {
        parse(date.trim())?.time ?: 0L
    } catch (_: ParseException) {
        0L
    }
}
