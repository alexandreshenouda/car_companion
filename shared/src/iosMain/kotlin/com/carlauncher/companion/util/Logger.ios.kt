package com.carlauncher.companion.util

import platform.Foundation.NSLog

actual object Logger {
    actual fun w(tag: String, message: String, throwable: Throwable?) {
        NSLog("W/%s: %s%s", tag, message, throwable?.let { " — $it" }.orEmpty())
    }

    actual fun d(tag: String, message: String) {
        NSLog("D/%s: %s", tag, message)
    }
}
