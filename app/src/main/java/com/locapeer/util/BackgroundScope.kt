package com.locapeer.util

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for singletons' background work. A bare
 * `CoroutineScope(SupervisorJob() + ...)` hands any uncaught exception to the thread's default
 * handler, which crashes the whole app - so one malformed relay event, a transient Keystore
 * error or a failed DB write in fire-and-forget work took the process down. This scope logs
 * the failure under [tag] instead; the SupervisorJob keeps sibling coroutines running.
 */
fun backgroundScope(tag: String, dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope =
    CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e -> Log.e(tag, "Uncaught background failure", e) }
    )
