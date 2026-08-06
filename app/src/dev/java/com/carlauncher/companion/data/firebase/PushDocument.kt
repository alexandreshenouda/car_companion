package com.carlauncher.companion.data.firebase

import com.google.firebase.firestore.DocumentSnapshot

/** Mirrors one entry of the `points` array inside a Firestore push doc. */
data class FirestorePoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val ts: Long = 0,
    val speedKmh: Long = 0,
)

/** Raw shape used only for Firestore's reflection-based `toObject()` mapping. */
data class FirestorePushRaw(
    val points: List<FirestorePoint> = emptyList(),
)

/** A `tracks/{deviceId}/pushes/{id}` document, with its id and server timestamp attached. */
data class PushDocument(
    val id: String,
    val pushedAtMillis: Long,
    val points: List<FirestorePoint>,
)

fun DocumentSnapshot.toPushDocument(): PushDocument? {
    val pushedAt = getTimestamp("pushedAt") ?: return null
    val raw = toObject(FirestorePushRaw::class.java) ?: return null
    return PushDocument(id = id, pushedAtMillis = pushedAt.toDate().time, points = raw.points)
}
