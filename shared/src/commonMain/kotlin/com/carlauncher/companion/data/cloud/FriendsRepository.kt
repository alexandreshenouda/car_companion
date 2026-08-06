package com.carlauncher.companion.data.cloud

import com.carlauncher.companion.data.cloud.dto.BlockUserParams
import com.carlauncher.companion.data.cloud.dto.FoundUserRow
import com.carlauncher.companion.data.cloud.dto.FriendRow
import com.carlauncher.companion.data.cloud.dto.RespondFriendRequestParams
import com.carlauncher.companion.data.cloud.dto.UsernameParam
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc

/** One entry in the friends list — a confirmed friend, or a request pending in either
 * direction. Mirrors the shape `get_friends()` returns, translated out of raw wire strings. */
data class Friend(
    val userId: String,
    val username: String,
    val displayName: String?,
    val direction: Direction,
) {
    enum class Direction { FRIEND, INCOMING_REQUEST, OUTGOING_REQUEST }
}

sealed interface SendRequestResult {
    data object Sent : SendRequestResult
    /** The other person had already sent a request — accepting theirs beats leaving two
     * pending rows pointed at each other, so the RPC resolves it as an accept instead. */
    data object AutoAccepted : SendRequestResult
    data object UserNotFound : SendRequestResult
    data object Offline : SendRequestResult
    data object Failed : SendRequestResult
}

/**
 * Friend requests and the friends list, over the `find_user_by_username` / `send_friend_request`
 * / `respond_friend_request` / `block_user` / `get_friends` RPCs (`supabase/schema.sql`).
 *
 * There is deliberately no "list all users" or prefix-search path anywhere in this class —
 * discovery is exact-username-match only, server-rate-limited, which is what keeps the user
 * base from being scraped. See `find_user_by_username`'s doc comment in the schema.
 */
class FriendsRepository(private val provider: SupabaseClientProvider) {

    suspend fun findByUsername(username: String): FoundUserRow? {
        val client = provider.client ?: return null
        return runCatching {
            client.postgrest.rpc("find_user_by_username", UsernameParam(username.trim().lowercase()))
                .decodeList<FoundUserRow>()
                .firstOrNull()
        }.getOrNull()
    }

    suspend fun sendRequest(username: String): SendRequestResult {
        val client = provider.client ?: return SendRequestResult.Failed
        return try {
            val status = client.postgrest
                .rpc("send_friend_request", UsernameParam(username.trim().lowercase()))
                .decodeAs<String>()
            when (status) {
                "accepted" -> SendRequestResult.AutoAccepted
                else -> SendRequestResult.Sent
            }
        } catch (e: Exception) {
            when {
                e.message?.contains("user_not_found") == true -> SendRequestResult.UserNotFound
                e.message?.contains("Unable to resolve host") == true -> SendRequestResult.Offline
                else -> SendRequestResult.Failed
            }
        }
    }

    suspend fun acceptRequest(requesterId: String): Boolean =
        respond(requesterId, accept = true)

    suspend fun declineRequest(requesterId: String): Boolean =
        respond(requesterId, accept = false)

    private suspend fun respond(requesterId: String, accept: Boolean): Boolean {
        val client = provider.client ?: return false
        return runCatching {
            client.postgrest.rpc("respond_friend_request", RespondFriendRequestParams(requesterId, accept))
            true
        }.getOrDefault(false)
    }

    /** Removes any existing relationship and prevents future requests in either direction.
     * There is no unblock UI — matches the RPC, which only ever adds a block, never removes one. */
    suspend fun block(userId: String): Boolean {
        val client = provider.client ?: return false
        return runCatching {
            client.postgrest.rpc("block_user", BlockUserParams(userId))
            true
        }.getOrDefault(false)
    }

    suspend fun listFriends(): List<Friend> {
        val client = provider.client ?: return emptyList()
        return runCatching {
            client.postgrest.rpc("get_friends").decodeList<FriendRow>().map {
                Friend(
                    userId = it.otherId,
                    username = it.otherUsername,
                    displayName = it.otherDisplayName,
                    direction = when (it.direction) {
                        "outgoing" -> Friend.Direction.OUTGOING_REQUEST
                        "incoming" -> Friend.Direction.INCOMING_REQUEST
                        else -> Friend.Direction.FRIEND
                    },
                )
            }
        }.getOrDefault(emptyList())
    }
}
