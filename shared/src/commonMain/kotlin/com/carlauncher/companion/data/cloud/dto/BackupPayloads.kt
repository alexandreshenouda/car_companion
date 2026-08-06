package com.carlauncher.companion.data.cloud.dto

import kotlinx.serialization.Serializable

/**
 * Plaintext shapes for the two end-to-end encrypted backup categories — what's inside a
 * [com.carlauncher.companion.data.cloud.crypto.CryptoBox] envelope before it's sealed, or
 * after it's opened. These never touch the network directly; the ciphertext they turn into is
 * what actually goes into `private_backups` (see [PrivateBackupRow]).
 */

@Serializable
data class GpsPointPayload(val lat: Double, val lng: Double, val ts: Long, val speedKmh: Int)

/** One chunk = one device's page of points, in upload order. */
@Serializable
data class GpsChunkPayload(val deviceId: String, val points: List<GpsPointPayload>)

/** Mirrors `TrophyProgressEntity` — the cached global stats snapshot, minus the local row id. */
@Serializable
data class StatsBackupPayload(
    val totalDistanceKm: Double,
    val longestTripKm: Double,
    val maxSpeedKmh: Int,
    val totalMovingSeconds: Long,
    val tripCount: Int,
    val nightTripCount: Int,
    val earlyTripCount: Int,
    val distinctDrivingDays: Int,
    val bestStreakDays: Int,
    val currentStreakDays: Int,
    val seasonsDriven: Int,
    val departmentCodes: String,
    val mapSquaresVisited: Int,
    val maxDistanceFromBaseKm: Double,
    val computedAt: Long,
)
