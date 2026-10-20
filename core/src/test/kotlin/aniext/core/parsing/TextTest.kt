package aniext.core.parsing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TextTest {

    @Test
    fun `html is converted to readable text`() {
        val html = "From <a href=\"http://x\">the site</a>:<br>Line&nbsp;two &amp; more<p>Para   one</p><ul><li>a</li><li>b</li></ul>"
        assertEquals("From the site:\nLine two & more\n\nPara one\n\na\n\nb", htmlToText(html))
    }

    @Test
    fun `plain text newlines are preserved and collapsed`() {
        assertEquals("first\n\nsecond", htmlToText("  first \n\n\n\n second  "))
        assertEquals("", htmlToText("<p> </p>"))
    }

    @Test
    fun `natural order compares digit runs numerically`() {
        val names = listOf("Part 10.mp4", "part 2.mp4", "Part 1.mp4", "Part 02b.mp4", "Intro.mp4")
        assertEquals(listOf("Intro.mp4", "Part 1.mp4", "part 2.mp4", "Part 02b.mp4", "Part 10.mp4"), names.sortedWith(naturalOrder))
        assertEquals(0, naturalOrder.compare("a1", "a1"))
    }

    @Test
    fun `tryParse returns epoch millis or zero`() {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals(1215142529000L, format.tryParse("2008-07-04 03:35:29"))
        assertEquals(0L, format.tryParse("yesterday"))
        assertEquals(0L, format.tryParse(null))
    }
}
