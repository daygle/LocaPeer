package com.locapeer.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reads at most [maxBytes] plus one probe byte. The caller owns/ closes [input]. */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
        if (count == -1) return output.toByteArray()
        require(count > 0) { "Input stream made no progress" }
        require(count <= maxBytes - total) { "Input exceeds $maxBytes bytes" }
        output.write(buffer, 0, count)
        total += count
    }
}
