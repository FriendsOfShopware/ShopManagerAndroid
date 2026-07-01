package de.shyim.shopware.api

import kotlinx.coroutines.CancellationException

// Like runCatching, but re-throws CancellationException so structured-concurrency cancellation is
// never swallowed. Use this around suspend work whose failure should degrade gracefully but whose
// cancellation must still propagate (the plain runCatching/try-catch would otherwise eat it).
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
