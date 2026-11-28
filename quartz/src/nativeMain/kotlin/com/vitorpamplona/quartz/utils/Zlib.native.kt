/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.quartz.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DATA_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NEED_DICT
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflateBound
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.inflateReset
import platform.zlib.z_stream
import platform.zlib.deflate as zDeflate
import platform.zlib.inflate as zInflate

/** zlib's window bits for a zlib-wrapped stream (a header and an Adler-32 trailer around the deflate data). */
private const val ZLIB_WINDOW_BITS = 15

@OptIn(ExperimentalForeignApi::class)
internal actual class ZlibInflater actual constructor() {
    private val stream: z_stream = nativeHeap.alloc<z_stream>()

    // The array zlib reads from between calls: pinned, so it stays where next_in points.
    private var input: Pinned<ByteArray>? = null
    private var finished = false
    private var needsDictionary = false
    private var bytesRead = 0L
    private var ended = false

    init {
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.next_in = null
        stream.avail_in = 0u
        val status = inflateInit2(stream.ptr, ZLIB_WINDOW_BITS)
        if (status != Z_OK) {
            nativeHeap.free(stream)
            ended = true
            throw IllegalStateException("inflateInit2 failed: $status")
        }
    }

    actual fun setInput(
        input: ByteArray,
        offset: Int,
        length: Int,
    ) {
        check(!ended) { "inflater already ended" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) { "input range out of bounds" }
        releaseInput()
        if (length > 0) {
            val pinned = input.pin()
            this.input = pinned
            stream.next_in = pinned.addressOf(offset).reinterpret()
        }
        stream.avail_in = length.toUInt()
    }

    actual fun inflate(
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(!ended) { "inflater already ended" }
        require(offset >= 0 && length >= 0 && offset + length <= output.size) { "output range out of bounds" }
        if (length == 0 || finished || needsDictionary) return 0
        val before = stream.avail_in
        val written =
            output.usePinned { pinned ->
                stream.next_out = pinned.addressOf(offset).reinterpret()
                stream.avail_out = length.toUInt()
                val status = zInflate(stream.ptr, Z_NO_FLUSH)
                val produced = length - stream.avail_out.toInt()
                // The output array is unpinned past this block: zlib must not keep a pointer into it.
                stream.next_out = null
                stream.avail_out = 0u
                bytesRead += (before - stream.avail_in).toLong()
                when (status) {
                    Z_OK, Z_BUF_ERROR -> {}
                    Z_STREAM_END -> finished = true
                    Z_NEED_DICT -> needsDictionary = true
                    Z_DATA_ERROR -> throw ZlibDataFormatException(stream.msg?.toKString() ?: "invalid zlib data")
                    else -> throw IllegalStateException("inflate failed: $status")
                }
                produced
            }
        return written
    }

    actual fun finished(): Boolean = finished

    actual fun needsInput(): Boolean = stream.avail_in == 0u

    actual fun needsDictionary(): Boolean = needsDictionary

    actual fun bytesRead(): Long = bytesRead

    actual fun remaining(): Int = stream.avail_in.toInt()

    actual fun reset() {
        check(!ended) { "inflater already ended" }
        inflateReset(stream.ptr)
        releaseInput()
        stream.avail_in = 0u
        finished = false
        needsDictionary = false
        bytesRead = 0L
    }

    actual fun end() {
        if (ended) return
        ended = true
        inflateEnd(stream.ptr)
        releaseInput()
        nativeHeap.free(stream)
    }

    private fun releaseInput() {
        input?.unpin()
        input = null
        stream.next_in = null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual object Zlib {
    actual fun deflate(input: ByteArray): ByteArray =
        memScoped {
            val stream = alloc<z_stream>()
            stream.zalloc = null
            stream.zfree = null
            stream.opaque = null

            deflateInit2(
                stream.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                ZLIB_WINDOW_BITS,
                8,
                Z_DEFAULT_STRATEGY,
            ).let { check(it == Z_OK) { "deflateInit2 failed: $it" } }

            try {
                val maxSize = deflateBound(stream.ptr, input.size.toULong()).toInt()
                val output = ByteArray(maxSize)

                val written =
                    input.usePinned { pinIn ->
                        output.usePinned { pinOut ->
                            if (input.isNotEmpty()) {
                                stream.next_in = pinIn.addressOf(0).reinterpret()
                            }
                            stream.avail_in = input.size.toUInt()

                            stream.next_out = pinOut.addressOf(0).reinterpret()
                            stream.avail_out = maxSize.toUInt()

                            zDeflate(stream.ptr, Z_FINISH)
                                .let { check(it == Z_STREAM_END) { "deflate failed: $it" } }

                            maxSize - stream.avail_out.toInt()
                        }
                    }

                output.copyOf(written)
            } finally {
                deflateEnd(stream.ptr)
            }
        }
}

internal actual class ZlibDataFormatException actual constructor(
    message: String,
) : Exception(message)

internal actual class EofException actual constructor(
    message: String,
) : Exception(message)
