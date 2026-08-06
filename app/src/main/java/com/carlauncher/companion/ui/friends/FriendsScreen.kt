package com.carlauncher.companion.ui.friends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.data.cloud.Friend
import com.carlauncher.companion.data.cloud.FriendsRepository
import com.carlauncher.companion.data.cloud.SendRequestResult
import com.carlauncher.companion.ui.common.AccentDivider
import com.carlauncher.companion.ui.common.IconBadge
import com.carlauncher.companion.ui.common.NeonCard
import com.carlauncher.companion.ui.common.NeonPill
import com.carlauncher.companion.ui.common.SectionLabel
import com.carlauncher.companion.ui.theme.AccentAlert
import com.carlauncher.companion.ui.theme.AccentProfile
import kotlinx.coroutines.launch

/**
 * Search by exact username, manage incoming/outgoing requests, and see current friends.
 *
 * There is no browsing or listing of other users anywhere in this screen — only an exact
 * username search, matching `find_user_by_username`'s server-side design (see
 * `FriendsRepository`'s doc comment for why: it's what stops the user base being scraped).
 */
@Composable
fun FriendsScreen(
    friendsRepository: FriendsRepository,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = AccentProfile

    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableStateOf(0) }

    fun refresh() { refreshToken++ }

    LaunchedEffect(refreshToken) {
        loading = true
        friends = friendsRepository.listFriends()
        loading = false
    }

    val incoming = friends.filter { it.direction == Friend.Direction.INCOMING_REQUEST }
    val outgoing = friends.filter { it.direction == Friend.Direction.OUTGOING_REQUEST }
    val accepted = friends.filter { it.direction == Friend.Direction.FRIEND }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SectionLabel(stringResource(R.string.friends_add_title), tint = accent)
        Spacer(Modifier.height(8.dp))
        NeonCard(accent, Modifier.fillMaxWidth(), glow = false) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }; searchMessage = null },
                        label = { Text(stringResource(R.string.auth_field_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    if (searching) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.height(24.dp))
                    } else {
                        NeonPill(
                            text = stringResource(R.string.friends_add_button),
                            accent = accent,
                            leading = {
                                androidx.compose.material3.Icon(
                                    Icons.Filled.PersonAdd,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.height(16.dp),
                                )
                            },
                            selected = query.isNotBlank(),
                            onClick = {
                                if (query.isBlank()) return@NeonPill
                                searching = true
                                searchMessage = null
                                scope.launch {
                                    when (val result = friendsRepository.sendRequest(query)) {
                                        SendRequestResult.Sent -> {
                                            searchMessage = context.getString(R.string.friends_request_sent_format, query)
                                            query = ""
                                            refresh()
                                        }
                                        SendRequestResult.AutoAccepted -> {
                                            searchMessage = context.getString(R.string.friends_auto_accepted_format, query)
                                            query = ""
                                            refresh()
                                        }
                                        SendRequestResult.UserNotFound -> searchMessage = context.getString(R.string.friends_user_not_found)
                                        SendRequestResult.Offline -> searchMessage = context.getString(R.string.friends_offline)
                                        SendRequestResult.Failed -> searchMessage = context.getString(R.string.auth_error_unknown)
                                    }
                                    searching = false
                                }
                            },
                        )
                    }
                }
                searchMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (loading) {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = accent)
            }
            return
        }

        if (incoming.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.friends_requests_label), tint = accent)
            Spacer(Modifier.height(6.dp))
            incoming.forEach { friend ->
                FriendRow(friend, accent, onClick = { onOpenProfile(friend.userId) }) {
                    Row {
                        NeonPill(
                            text = stringResource(R.string.friends_accept),
                            accent = accent,
                            selected = true,
                            onClick = {
                                scope.launch { friendsRepository.acceptRequest(friend.userId); refresh() }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        NeonPill(
                            text = stringResource(R.string.friends_decline),
                            accent = AccentAlert,
                            onClick = {
                                scope.launch { friendsRepository.declineRequest(friend.userId); refresh() }
                            },
                        )
                    }
                }
            }
        }

        if (outgoing.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel(stringResource(R.string.friends_sent_label), tint = accent)
            Spacer(Modifier.height(6.dp))
            outgoing.forEach { friend ->
                FriendRow(friend, accent, onClick = { onOpenProfile(friend.userId) }) {
                    Text(stringResource(R.string.friends_pending), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.friends_friends_label), tint = accent)
        Spacer(Modifier.height(6.dp))
        if (accepted.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(Icons.Filled.People, MaterialTheme.colorScheme.onSurfaceVariant, size = 56.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.friends_no_friends_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            accepted.forEach { friend -> FriendRow(friend, accent, onClick = { onOpenProfile(friend.userId) }) {} }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).clickable(onClick = onClick),
            ) {
                IconBadge(Icons.Filled.People, accent, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(friend.displayName?.ifBlank { null } ?: friend.username, style = MaterialTheme.typography.titleMedium)
                    if (!friend.displayName.isNullOrBlank()) {
                        Text(stringResource(R.string.friends_at_username_format, friend.username), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            trailing()
        }
        AccentDivider(accent)
    }
}
