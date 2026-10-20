package aniext.core.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RateLimitTest {

    @Test
    fun `sliding window grants permits and reports the remaining wait`() {
        var now = 0L
        val limiter = SlidingWindowLimiter(permits = 2, period = 1.seconds, clock = { now })

        assertEquals(0L, limiter.tryAcquire())
        now = 100.milliseconds.inWholeNanoseconds
        assertEquals(0L, limiter.tryAcquire())
        now = 400.milliseconds.inWholeNanoseconds
        assertEquals(600.milliseconds.inWholeNanoseconds, limiter.tryAcquire())

        // The first permit leaves the window at t = 1s.
        now = 1.seconds.inWholeNanoseconds
        assertEquals(0L, limiter.tryAcquire())
        assertEquals(100.milliseconds.inWholeNanoseconds, limiter.tryAcquire())
    }

    /** Terminal interceptor so no network is touched. */
    private val okResponder = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody())
            .build()
    }

    @Test
    fun `interceptor delays matching hosts and leaves others alone`() {
        val client = OkHttpClient.Builder()
            .rateLimit(permits = 1, period = 300.milliseconds) { it.host == "limited.test" }
            .addInterceptor(okResponder)
            .build()

        fun timeOf(url: String): Long {
            val start = System.nanoTime()
            client.newCall(Request.Builder().url(url).build()).execute().close()
            return (System.nanoTime() - start) / 1_000_000
        }

        timeOf("https://limited.test/a")
        val limitedWait = timeOf("https://limited.test/b")
        val unlimitedWait = timeOf("https://free.test/c")

        assertTrue("second limited call should wait ~300ms, waited ${limitedWait}ms", limitedWait >= 200)
        assertTrue("other hosts must not wait, waited ${unlimitedWait}ms", unlimitedWait < 150)
    }
}
