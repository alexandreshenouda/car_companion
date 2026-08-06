package com.carlauncher.companion.data.firebase

import com.carlauncher.companion.data.model.DiscoveredDevice
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

/** Thin wrapper over the `tracks/{deviceId}/pushes` Firestore contract described in GPS_TRACKING.md. */
class TrackRemoteSource(private val firestore: FirebaseFirestore) {

    private fun pushesCollection(deviceId: String) =
        firestore.collection("tracks").document(deviceId).collection("pushes")

    /** Live tail of the most recent pushes — drives the "latest known position" UI. */
    fun observeLatestPushes(deviceId: String, limit: Long = 5): Flow<List<PushDocument>> = callbackFlow {
        val registration = pushesCollection(deviceId)
            .orderBy("pushedAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.toPushDocument() })
            }
        awaitClose { registration.remove() }
    }

    /** One page of pushes strictly after [afterMillis], oldest first — used for catch-up sync. */
    suspend fun fetchPushesAfter(deviceId: String, afterMillis: Long, pageSize: Long): List<PushDocument> {
        val snapshot = pushesCollection(deviceId)
            .whereGreaterThan("pushedAt", Timestamp(Date(afterMillis)))
            .orderBy("pushedAt", Query.Direction.ASCENDING)
            .limit(pageSize)
            .get()
            .await()
        return snapshot.documents.mapNotNull { it.toPushDocument() }
    }

    /**
     * Finds every device that has ever pushed a location, by running a collection-group query
     * across all `tracks/{deviceId}/pushes` subcollections at once — there's no top-level
     * `devices` index to read instead. Cheap because our own sync deletes push docs once cached,
     * so this scales with device count, not history depth. Requires a Firestore rule of the form
     * `match /{path=**}/pushes/{pushId} { allow read: if request.auth != null; }`, since
     * collection-group reads aren't covered by the plain nested-path rule.
     */
    suspend fun discoverDeviceIds(): List<DiscoveredDevice> {
        val snapshot = firestore.collectionGroup("pushes").get().await()
        return snapshot.documents
            .mapNotNull { doc ->
                val deviceId = doc.reference.parent.parent?.id ?: return@mapNotNull null
                val pushedAtMillis = doc.getTimestamp("pushedAt")?.toDate()?.time ?: return@mapNotNull null
                deviceId to pushedAtMillis
            }
            .groupBy({ it.first }, { it.second })
            .map { (deviceId, timestamps) -> DiscoveredDevice(deviceId, timestamps.max()) }
            .sortedByDescending { it.lastSeenMillis }
    }

    /** Deletes push docs once they're durably cached locally, to keep Firestore storage bounded. */
    suspend fun deletePushes(deviceId: String, pushIds: List<String>) {
        if (pushIds.isEmpty()) return
        val collection = pushesCollection(deviceId)
        // Firestore batches are capped at 500 writes; chunk defensively even though a single
        // catch-up page (<=100 pushes) never gets close to that.
        pushIds.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { id -> batch.delete(collection.document(id)) }
            batch.commit().await()
        }
    }

    /** Deletes every push doc for a device — used when the user removes the car entirely. */
    suspend fun deleteAllPushes(deviceId: String) {
        val snapshot = pushesCollection(deviceId).get().await()
        deletePushes(deviceId, snapshot.documents.map { it.id })
    }
}
