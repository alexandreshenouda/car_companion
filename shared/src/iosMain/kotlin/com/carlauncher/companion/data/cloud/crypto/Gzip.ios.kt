package com.carlauncher.companion.data.cloud.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.value
import platform.zlib.uLongVar
import platform.zlib.uncompress

/**
 * Apple's `Compression` framework (originally used here) turns out not to be usable from
 * Kotlin/Native: its Objective-C bindings aren't part of Kotlin/Native's default platform
 * libraries (only a handful of pre-iOS-13 `platform.darwin.compression_*` symbols are, and
 * this project needs none of them) — confirmed once a real compile against the iOS SDK was
 * possible (see README's "iOS port" section on CI). `platform.zlib` is a standard
 * Kotlin/Native platform library instead (bound from libz, which every Apple platform
 * ships), and its one-shot `compress`/`uncompress` functions are a direct swap for what
 * `compression_encode_buffer`/`compression_decode_buffer` were doing.
 *
 * No single GPS/stats backup chunk this app produces gets remotely close to this — chunking
 * exists specifically to keep them small — but zlib's one-shot functions need a fixed
 * destination capacity up front rather than growing one, so this is a generous, deliberately
 * oversized ceiling rather than a measured size.
 */
private const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

@OptIn(ExperimentalForeignApi::class)
internal actual fun compress(input: ByteArray): ByteArray = memScoped {
    val destination = allocArray<UByteVar>(MAX_PAYLOAD_BYTES)
    val destLen = alloc<uLongVar>()
    destLen.value = MAX_PAYLOAD_BYTES.convert()
    val result = platform.zlib.compress(
        destination, destLen.ptr,
        input.toUByteArray().toCValues(), input.size.convert(),
    )
    check(result == 0) { "zlib compress failed: $result" }
    destination.readBytes(destLen.value.convert())
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun decompress(input: ByteArray): ByteArray = memScoped {
    val destination = allocArray<UByteVar>(MAX_PAYLOAD_BYTES)
    val destLen = alloc<uLongVar>()
    destLen.value = MAX_PAYLOAD_BYTES.convert()
    val result = uncompress(
        destination, destLen.ptr,
        input.toUByteArray().toCValues(), input.size.convert(),
    )
    check(result == 0) { "zlib uncompress failed: $result — payload larger than the $MAX_PAYLOAD_BYTES byte ceiling, or corrupt" }
    destination.readBytes(destLen.value.convert())
}
