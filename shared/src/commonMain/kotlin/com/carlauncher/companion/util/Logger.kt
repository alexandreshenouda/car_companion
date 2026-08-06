package com.carlauncher.companion.util

/** Trivial multiplatform stand-in for `android.util.Log`, used by the cloud sync layer for
 * warnings it wants surfaced in device logs but that must never crash a sync/restore pass. */
expect object Logger {
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun d(tag: String, message: String)
}
