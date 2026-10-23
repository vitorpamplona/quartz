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

import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * HotSpot never JIT-compiles a method with more than 8,000 bytes of bytecode
 * (`DontCompileHugeMethods`): it runs in the interpreter forever. [EventFactory.create] builds
 * every parsed event, and as one `when` over all kinds it grew past that, which cost 11 to 21%
 * of every parse. It is now split; this fails when a part grows back toward the limit.
 */
class EventFactoryJitSizeTest {
    @Test
    fun everyFactoryMethodStaysJitCompilable() {
        val sizes = methodCodeSizes(EventFactory.Companion::class.java)
        assertTrue("create" in sizes, "class file not read: $sizes")
        val tooBig = sizes.filterValues { it > LIMIT }
        assertTrue(tooBig.isEmpty(), "over $LIMIT bytes of bytecode, split further: $tooBig")
    }

    /** Method name to the length of its bytecode, read from the class file. */
    private fun methodCodeSizes(type: Class<*>): Map<String, Int> {
        val stream = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class") ?: error("no class file for $type")
        DataInputStream(stream.buffered()).use { input ->
            input.readInt() // magic
            input.readUnsignedShort() // minor
            input.readUnsignedShort() // major
            val utf8 = HashMap<Int, String>()
            val count = input.readUnsignedShort()
            var index = 1
            while (index < count) {
                when (input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.skipBytes(4)
                    5, 6 -> {
                        input.skipBytes(8)
                        index++ // longs and doubles take two slots
                    }
                    7, 8, 16, 19, 20 -> input.skipBytes(2)
                    9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                    15 -> input.skipBytes(3)
                    else -> error("unknown constant pool tag")
                }
                index++
            }
            input.skipBytes(6) // access, this, super
            input.skipBytes(2 * input.readUnsignedShort()) // interfaces
            repeat(input.readUnsignedShort()) { skipMember(input) } // fields
            val sizes = HashMap<String, Int>()
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort() // access
                val name = utf8.getValue(input.readUnsignedShort())
                input.readUnsignedShort() // descriptor
                repeat(input.readUnsignedShort()) {
                    val attribute = utf8.getValue(input.readUnsignedShort())
                    val length = input.readInt()
                    if (attribute == "Code") {
                        input.skipBytes(4) // max stack, max locals
                        val codeLength = input.readInt()
                        sizes[name] = maxOf(sizes[name] ?: 0, codeLength)
                        input.skipBytes(length - 8)
                    } else {
                        input.skipBytes(length)
                    }
                }
            }
            return sizes
        }
    }

    private fun skipMember(input: DataInputStream) {
        input.skipBytes(6) // access, name, descriptor
        repeat(input.readUnsignedShort()) {
            input.skipBytes(2)
            input.skipBytes(input.readInt())
        }
    }

    companion object {
        /** Below HotSpot's 8,000, with room for kinds to be added before a part needs splitting. */
        private const val LIMIT = 6_000
    }
}
