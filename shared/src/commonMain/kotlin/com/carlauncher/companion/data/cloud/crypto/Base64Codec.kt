package com.carlauncher.companion.data.cloud.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Shared by [KeyVault] and [com.carlauncher.companion.data.cloud.CloudSyncManager] — every
 * byte blob this app sends through PostgREST travels as base64 text, never `bytea`, so a
 * client doesn't have to special-case Postgres's `\x`-hex-escaped bytea rendering. */
@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64(): String = Base64.Default.encode(this)

@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray = Base64.Default.decode(this)
