package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.dto.FeedActivityRow
import com.carlauncher.companion.data.cloud.dto.GetFeedParams
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One rendered activity card. `createdAt` is parsed once here rather than on every recompose. */
data class FeedActivity(
    val activityId: String,
    val actorId: String,
    val actorUsername: String,
    val actorDisplayName: String?,
    val kind: String,
    val subjectId: String?,
    val subjectKey: String?,
    val createdAt: Long,
    val title: String?,
    val subtitle: String?,
    val distanceKm: Double,
    val maxSpeedKmh: Int,
    val modCount: Int,
    /** ISO timestamp string, not epoch millis — passed straight through to
     * [SharedContentRepository.getCarPhoto]'s cache key rather than parsed, since nothing here
     * needs it as a comparable time, only as a "did the photo change" version marker. */
    val photoUpdatedAt: String?,
)

/**
 * The community Feed — a paged read of the `get_feed` RPC (`supabase/schema.sql`). Visibility
 * is entirely server-side: the function already filters to what the caller is allowed to see
 * (their own activity, plus shared items from friends or everyone per each actor's own
 * visibility setting), so there is nothing for the client to additionally filter or trust.
 */
class FeedRepository(private val provider: SupabaseClientProvider) {

    /** @param scope "friends" or "everyone", matching [CloudPrefsRepository]'s [FeedScope]. Null defers to the RPC's own default. */
    @OptIn(ExperimentalTime::class)
    suspend fun page(scope: String?, before: Long?, limit: Int = 30): List<FeedActivity> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest
                .rpc("get_feed", GetFeedParams(scope = scope, before = before?.let { Instant.fromEpochMilliseconds(it) }?.toString(), limit = limit))
                .decodeList<FeedActivityRow>()
                .map { it.toActivity() }
        }.getOrDefault(emptyList())
    }
}

@OptIn(ExperimentalTime::class)
private fun FeedActivityRow.toActivity() = FeedActivity(
    activityId = activityId,
    actorId = actorId,
    actorUsername = actorUsername,
    actorDisplayName = actorDisplayName,
    kind = kind,
    subjectId = subjectId,
    subjectKey = subjectKey,
    createdAt = Instant.parse(createdAt).toEpochMilliseconds(),
    title = title,
    subtitle = subtitle,
    distanceKm = distanceKm,
    maxSpeedKmh = maxSpeedKmh,
    modCount = modCount,
    photoUpdatedAt = photoUpdatedAt,
)
