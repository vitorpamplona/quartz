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
package com.vitorpamplona.quartz.nip34Git.git

/**
 * File types that hold no searchable text, known by name: archives, compiled code and native
 * libraries, images, fonts, audio and video. [GitRepoSnapshot.searchContents] leaves them out
 * before fetching, since a blob asked for by id comes back whatever its size, and these are most
 * of a repository's large files.
 */
object GitBinaryFiles {
    /** Lower-case extensions, without the dot. */
    val EXTENSIONS: Set<String> =
        listOf(
            // archives and compressed files
            "7z br bz2 gz lz lz4 lzma rar tar tgz txz xz z zip zst",
            // packages, compiled code and native libraries
            "a aab aar apk bin class dex dll dmg dylib exe ipa jar jnilib lib o pyc so wasm",
            // images
            "avif bmp gif heic heif icns ico jpeg jpg png psd tif tiff webp",
            // fonts
            "eot otf ttc ttf woff woff2",
            // audio and video
            "aac avi flac m4a m4v mkv mov mp3 mp4 mpeg mpg oga ogg opus wav webm wma wmv",
            // documents, databases, keys and models stored as binary
            "db jks keystore onnx p12 pdf sqlite tflite",
        ).flatMapTo(HashSet()) { it.split(' ') }

    /** True when [fileName]'s extension (after its last dot, any case) names a binary file type. */
    fun hasBinaryExtension(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        if (dot < 0 || dot == fileName.length - 1) return false
        return fileName.substring(dot + 1).lowercase() in EXTENSIONS
    }
}
