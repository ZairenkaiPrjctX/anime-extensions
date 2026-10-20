package aniext.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ErrorsTest {

    @Test
    fun `redacted URLs drop credentials, query and fragment`() {
        val url = "https://user:secret@api.example.org:8443/v1/items?token=abc#frag".toHttpUrl()
        assertEquals("https://api.example.org:8443/v1/items", url.redacted())
        assertEquals("https://example.org/", "https://example.org/?q=1".toHttpUrl().redacted())
    }

    private fun response(code: Int) = Response.Builder()
        .request(Request.Builder().url("https://api.example.org/search?key=SECRET").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body("{}".toResponseBody())
        .build()

    @Test
    fun `requireSuccess passes 2xx through`() {
        val ok = response(200)
        assertEquals(ok, ok.requireSuccess())
    }

    @Test
    fun `requireSuccess classifies failures without leaking the query`() {
        try {
            response(429).requireSuccess()
            fail("expected HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(429, e.code)
            assertTrue(e.isRateLimited)
            assertFalse(e.message!!.contains("SECRET"))
            assertEquals("HTTP 429 from https://api.example.org/search", e.message)
        }
    }
}
