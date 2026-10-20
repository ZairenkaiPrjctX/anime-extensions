package aniext.core.network

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Bounded exponential backoff. [maxAttempts] counts the first try, so the default makes at most
 * two retries and waits at most `initialDelay + initialDelay * multiplier` in total.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 4.seconds,
    val multiplier: Double = 2.0,
) {
    init {
        require(maxAttempts in 1..10) { "maxAttempts must be within 1..10" }
        require(!initialDelay.isNegative() && !maxDelay.isNegative()) { "delays must not be negative" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    /** Delay before retry number [retry] (1-based). */
    fun delayBefore(retry: Int): Duration {
        var delay = initialDelay
        repeat(retry - 1) { delay = (delay * multiplier).coerceAtMost(maxDelay) }
        return delay.coerceAtMost(maxDelay)
    }

    companion object {
        val NONE = RetryPolicy(maxAttempts = 1)
    }
}

/** HTTP statuses that are worth retrying: timeouts, throttling and gateway/server overload. */
val TRANSIENT_HTTP_CODES: Set<Int> = setOf(408, 425, 429, 500, 502, 503, 504)

/**
 * Whether [this] failure is likely to succeed on retry. Permanent failures (4xx, parse errors,
 * unavailable content) and cancellation are never retried. OkHttp reports a cancelled call as
 * `IOException("Canceled")`, which is excluded too.
 */
fun Throwable.isTransientNetworkFailure(): Boolean = when (this) {
    is CancellationException -> false
    is HttpStatusException -> code in TRANSIENT_HTTP_CODES
    is HttpException -> code in TRANSIENT_HTTP_CODES
    is SourceException -> false
    is IOException -> message != "Canceled"
    else -> false
}

/**
 * Runs [block], retrying transient failures according to [policy]. Waiting uses coroutine
 * [delay], so no thread is blocked. The last failure is rethrown unchanged.
 */
suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    isRetryable: (Throwable) -> Boolean = Throwable::isTransientNetworkFailure,
    sleep: suspend (Duration) -> Unit = { delay(it) },
    block: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return block(attempt)
        } catch (e: Throwable) {
            if (e is CancellationException || attempt >= policy.maxAttempts || !isRetryable(e)) throw e
            sleep(policy.delayBefore(attempt))
            attempt++
        }
    }
}
