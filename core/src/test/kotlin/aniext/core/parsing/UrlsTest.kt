package aniext.core.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlsTest {

    private val base = "https://example.org/shows/list.html"

    @Test
    fun `resolves relative, root-relative, protocol-relative and absolute links`() {
        assertEquals("https://example.org/shows/item-1", resolveUrl(base, "item-1"))
        assertEquals("https://example.org/img/a.jpg", resolveUrl(base, " /img/a.jpg "))
        assertEquals("https://cdn.example.net/a.jpg", resolveUrl(base, "//cdn.example.net/a.jpg"))
        assertEquals("http://other.example/x", resolveUrl(base, "http://other.example/x"))
        assertEquals("https://example.org/a%20b", resolveUrl(base, "/a b"))
    }

    @Test
    fun `rejects unsafe or empty input`() {
        for (href in listOf(null, "", "   ", "javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,x", "mailto:a@b.c", "ftp://example.org/f", "https://user:pw@example.org/")) {
            assertNull("should reject '$href'", resolveUrl(base, href))
        }
        assertNull(resolveUrl("not a url", "/x"))
    }

    @Test
    fun `safe https parsing`() {
        assertEquals("https://example.org/", "https://example.org".toSafeHttpsUrlOrNull().toString())
        assertNull("http://example.org".toSafeHttpsUrlOrNull())
        assertNull("https://a:b@example.org".toSafeHttpsUrlOrNull())
        assertNull("example.org".toSafeHttpsUrlOrNull())
    }
}
