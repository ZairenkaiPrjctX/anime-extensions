package aniext.core.network

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTest {

    private val sleeps = mutableListOf<Duration>()
    private val policy = RetryPolicy(maxAttempts = 3, initialDelay = 100.milliseconds, maxDelay = 150.milliseconds)

    private fun <T> retry(block: suspend (Int) -> T): T = runBlocking { withRetry(policy, sleep = { sleeps += it }, block = block) }

    @Test
    fun `succeeds after transient failures and backs off with a cap`() {
        val result = retry { attempt ->
            if (attempt < 3) throw HttpException(503)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(100.milliseconds, 150.milliseconds), sleeps)
    }

    @Test
    fun `gives up after maxAttempts and rethrows the last failure`() {
        var calls = 0
        val failure = SocketTimeoutException("timeout")
        try {
            retry<Unit> {
                calls++
                throw failure
            }
            fail("expected exception")
        } catch (e: SocketTimeoutException) {
            assertSame(failure, e)
        }
        assertEquals(3, calls)
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `permanent failures are not retried`() {
        for (permanent in listOf(HttpException(404), HttpStatusException(403, "https://x/y"), InvalidResponseException("bad"), MissingFieldException("title", "item"))) {
            var calls = 0
            try {
                retry<Unit> {
                    calls++
                    throw permanent
                }
            } catch (_: Exception) {
            }
            assertEquals("${permanent::class.simpleName} must not be retried", 1, calls)
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `cancellation propagates immediately`() {
        var calls = 0
        try {
            retry<Unit> {
                calls++
                throw CancellationException("stop")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, calls)
    }

    @Test
    fun `transient classification`() {
        assertTrue(HttpException(429).isTransientNetworkFailure())
        assertTrue(HttpStatusException(502, "https://x").isTransientNetworkFailure())
        assertTrue(IOException("connection reset").isTransientNetworkFailure())
        assertFalse(IOException("Canceled").isTransientNetworkFailure())
        assertFalse(HttpException(400).isTransientNetworkFailure())
        assertFalse(IllegalStateException().isTransientNetworkFailure())
        assertFalse(ContentUnavailableException("gone").isTransientNetworkFailure())
    }

    @Test
    fun `policy validates its bounds`() {
        assertEquals(4.seconds, RetryPolicy(initialDelay = 1.seconds, maxDelay = 4.seconds).delayBefore(5))
        try {
            RetryPolicy(maxAttempts = 0)
            fail("expected validation error")
        } catch (_: IllegalArgumentException) {
        }
    }
}
