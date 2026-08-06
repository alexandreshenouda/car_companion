package com.carlauncher.companion.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MapFocusRequest(val lat: Double, val lng: Double)

/**
 * Cross-screen signal so History can ask Map to recenter on a specific point when tapped.
 * Hand-rolled rather than a nav arg since MapScreen already lives behind the bottom-tab
 * route and doesn't otherwise take arguments.
 */
class MapFocusRequestHolder {
    private val _request = MutableStateFlow<MapFocusRequest?>(null)
    val request: StateFlow<MapFocusRequest?> = _request.asStateFlow()

    fun request(lat: Double, lng: Double) {
        _request.value = MapFocusRequest(lat, lng)
    }

    fun consume() {
        _request.value = null
    }
}
