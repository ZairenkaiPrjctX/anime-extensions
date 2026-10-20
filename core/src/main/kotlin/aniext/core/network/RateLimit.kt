package aniext.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Limits requests whose URL matches [shouldLimit] to [permits] per sliding [period].
 *
 * The extensions-lib `eu.kanade.tachiyomi.network.interceptor.rateLimit` is a deprecated no-op in
 * lib 16, so sources must bring their own limiter. Compare hosts, not full URLs:
 * ```
 * network.client.newBuilder().rateLimit(2) { it.host == "api.example.org" }.build()
 * ```
 */
fun OkHttpClient.Builder.rateLimit(
    permits: Int,
    period: Duration = 1.seconds,
    shouldLimit: (HttpUrl) -> Boolean = { true },
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(SlidingWindowLimiter(permits, period), shouldLimit))

/**
 * Sliding-window permit accounting with an injectable monotonic [clock] (nanoseconds).
 * Thread-safe; it never sleeps itself.
 */
class SlidingWindowLimiter(
    private val permits: Int,
    period: Duration,
    private val clock: () -> Long = System::nanoTime,
) {
    private val periodNanos = period.inWholeNanoseconds
    private val dispatched = ArrayDeque<Long>(permits)
    private val lock = ReentrantLock()

    init {
        require(permits > 0) { "permits must be positive" }
        require(periodNanos > 0) { "period must be positive" }
    }

    /** Takes a permit and returns 0, or returns how many nanoseconds to wait before trying again. */
    fun tryAcquire(): Long = lock.withLock {
        val now = clock()
        while (dispatched.isNotEmpty() && now - dispatched.first() >= periodNanos) {
            dispatched.removeFirst()
        }
        if (dispatched.size < permits) {
            dispatched.addLast(now)
            0L
        } else {
            periodNanos - (now - dispatched.first())
        }
    }
}

/**
 * OkHttp interceptors run synchronously on OkHttp's worker threads, so waiting for a permit has
 * to park that thread. Waits are sliced so a cancelled call is released within [WAIT_SLICE].
 */
class RateLimitInterceptor(
    private val limiter: SlidingWindowLimiter,
    private val shouldLimit: (HttpUrl) -> Boolean = { true },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!shouldLimit(request.url)) return chain.proceed(request)

        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val waitNanos = limiter.tryAcquire()
            if (waitNanos == 0L) break
            LockSupport.parkNanos(this, minOf(waitNanos, WAIT_SLICE.inWholeNanoseconds))
        }
        return chain.proceed(request)
    }

    private companion object {
        val WAIT_SLICE = 250.milliseconds
    }
}
