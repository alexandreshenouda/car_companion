package com.carlauncher.companion.util

import android.util.Log

actual object Logger {
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }
}
