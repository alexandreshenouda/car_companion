package com.carlauncher.companion.data.cloud.crypto

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val CHUNK_SIZE = 4096

internal actual fun compress(input: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION).apply {
        setInput(input)
        finish()
    }
    val out = ByteArrayOutputStream(input.size / 4)
    val buffer = ByteArray(CHUNK_SIZE)
    try {
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
    } finally {
        deflater.end()
    }
    return out.toByteArray()
}

internal actual fun decompress(input: ByteArray): ByteArray {
    val inflater = Inflater().apply { setInput(input) }
    val out = ByteArrayOutputStream(input.size * 4)
    val buffer = ByteArray(CHUNK_SIZE)
    try {
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            out.write(buffer, 0, count)
        }
    } finally {
        inflater.end()
    }
    return out.toByteArray()
}
