package com.carlauncher.companion.data.cloud.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import platform.Compression.COMPRESSION_ZLIB
import platform.Compression.compression_decode_buffer
import platform.Compression.compression_encode_buffer

/**
 * No single GPS/stats backup chunk this app produces gets remotely close to this — chunking
 * exists specifically to keep them small — but `compression_encode_buffer`/
 * `compression_decode_buffer` need a fixed destination capacity up front rather than growing
 * one, so this is a generous, deliberately oversized ceiling rather than a measured size.
 */
private const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

@OptIn(ExperimentalForeignApi::class)
internal actual fun compress(input: ByteArray): ByteArray = memScoped {
    val destination = allocArray<kotlinx.cinterop.UByteVar>(MAX_PAYLOAD_BYTES)
    val producedSize = compression_encode_buffer(
        destination, MAX_PAYLOAD_BYTES.convert(),
        input.toUByteArray().toCValues(), input.size.convert(),
        null,
        COMPRESSION_ZLIB,
    )
    check(producedSize > 0uL) { "compression_encode_buffer failed" }
    destination.readBytes(producedSize.convert())
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun decompress(input: ByteArray): ByteArray = memScoped {
    val destination = allocArray<kotlinx.cinterop.UByteVar>(MAX_PAYLOAD_BYTES)
    val producedSize = compression_decode_buffer(
        destination, MAX_PAYLOAD_BYTES.convert(),
        input.toUByteArray().toCValues(), input.size.convert(),
        null,
        COMPRESSION_ZLIB,
    )
    check(producedSize > 0uL) { "compression_decode_buffer failed — payload larger than the $MAX_PAYLOAD_BYTES byte ceiling, or corrupt" }
    destination.readBytes(producedSize.convert())
}
