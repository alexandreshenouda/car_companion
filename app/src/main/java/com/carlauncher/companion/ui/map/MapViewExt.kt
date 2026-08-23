package com.carlauncher.companion.ui.map

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.views.MapView

/** Suspends until [MapView] has a real size, so zoom-to-bounds calls made right after attaching don't act on a 0x0 view. */
suspend fun MapView.awaitFirstLayout() {
    if (width > 0 && height > 0) return
    // addOnFirstLayoutListener silently drops the listener once osmdroid has recorded its first
    // layout pass, so suspending past that point would never resume.
    if (isLayoutOccurred) return
    suspendCancellableCoroutine<Unit> { continuation ->
        addOnFirstLayoutListener { _, _, _, _, _ ->
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}
