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
package com.vitorpamplona.quartz.nip05DnsIdentifiers.namecoin

import com.vitorpamplona.quartz.network.quartz_nw_connection_receive
import com.vitorpamplona.quartz.network.quartz_nw_socks5_server_create_options
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_connection_send
import platform.Network.nw_connection_send_completion_t
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_failed
import platform.Network.nw_listener_state_ready
import platform.Network.nw_listener_t
import platform.Network.nw_parameters_configure_protocol_block_t
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_t
import platform.Network.nw_protocol_stack_prepend_application_protocol
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Network.nw_tls_create_options
import platform.Security.sec_identity_t
import platform.Security.sec_protocol_options_set_local_identity
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class LoopbackReply {
    SUCCESS,
    MALFORMED,
    SERVER_ERROR,
}

/** A local Network.framework server that can terminate a SOCKS5 CONNECT and answer Electrum RPC. */
@OptIn(ExperimentalForeignApi::class)
internal class LoopbackElectrumXFixture(
    private val socks5: Boolean,
    private val reply: LoopbackReply = LoopbackReply.SUCCESS,
    private val tlsIdentity: sec_identity_t = null,
) {
    private val queue = dispatch_queue_create("com.vitorpamplona.quartz.electrumx.fixture", null)
    private val connections = mutableListOf<nw_connection_t>()
    private var listener: nw_listener_t = null

    var acceptedCount: Int = 0
        private set
    var requestCount: Int = 0
        private set
    var targetHost: String? = null
        private set
    var targetPort: Int? = null
        private set
    var protocolError: String? = null
        private set

    suspend fun start(): Int {
        val parameters =
            when {
                tlsIdentity == null -> createCleartextTcpParameters()
                !socks5 -> {
                    val configureTls: nw_parameters_configure_protocol_block_t = { options ->
                        sec_protocol_options_set_local_identity(options, tlsIdentity)
                    }
                    nw_parameters_create_secure_tcp(configureTls) { _ -> Unit }
                }
                else ->
                    createSocks5TlsParameters(tlsIdentity) { host, port ->
                        targetHost = host
                        targetPort = port
                    }
            }
        val listener =
            nw_listener_create(parameters)
                ?: throw IllegalStateException("Could not create loopback listener")
        this.listener = listener
        return suspendCancellableCoroutine { continuation ->
            nw_listener_set_new_connection_handler(listener) { connection ->
                if (connection != null) accept(connection)
            }
            nw_listener_set_state_changed_handler(listener) { state, error ->
                when (state) {
                    nw_listener_state_ready ->
                        if (continuation.isActive) continuation.resume(nw_listener_get_port(listener).toInt())
                    nw_listener_state_failed ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Loopback listener failed: $error"))
                        }
                }
            }
            continuation.invokeOnCancellation { close() }
            nw_listener_set_queue(listener, queue)
            nw_listener_start(listener)
        }
    }

    fun close() {
        listener?.let { nw_listener_cancel(it) }
        listener = null
        connections.forEach { platform.Network.nw_connection_cancel(it) }
        connections.clear()
    }

    private fun createSocks5TlsParameters(
        identity: sec_identity_t,
        onTarget: (String, Int) -> Unit,
    ): nw_parameters_t {
        val parameters = createCleartextTcpParameters()
        val stack =
            nw_parameters_copy_default_protocol_stack(parameters)
                ?: error("Could not create SOCKS5 fixture protocol stack")
        val tlsOptions = nw_tls_create_options() ?: error("Could not create SOCKS5 fixture TLS options")
        val securityOptions = nw_tls_copy_sec_protocol_options(tlsOptions)
        sec_protocol_options_set_local_identity(securityOptions, identity)
        val socksOptions =
            quartz_nw_socks5_server_create_options(tlsOptions) { host, port ->
                onTarget(host?.toKString().orEmpty(), port.toInt())
            } ?: error("Could not create SOCKS5 fixture tunnel options")
        nw_protocol_stack_prepend_application_protocol(stack, socksOptions)
        return parameters
    }

    private fun accept(connection: nw_connection_t) {
        connections += connection
        acceptedCount++
        nw_connection_set_state_changed_handler(connection) { state, error ->
            when {
                error != null -> protocolError = "Accepted connection failed: $error"
                state == nw_connection_state_ready ->
                    if (socks5 && tlsIdentity == null) serveSocks(connection) else receiveRequest(connection)
            }
        }
        nw_connection_set_queue(connection, queue)
        nw_connection_start(connection)
    }

    private fun serveSocks(connection: nw_connection_t) {
        receiveExact(connection, 2) { greeting ->
            if (greeting[0] != 5.toByte()) {
                protocolError = "SOCKS greeting has the wrong version"
                return@receiveExact
            }
            receiveExact(connection, greeting[1].toInt()) { methods ->
                if (!methods.contains(0)) {
                    protocolError = "SOCKS client does not offer no-auth"
                    return@receiveExact
                }
                send(connection, byteArrayOf(5, 0)) {
                    receiveExact(connection, 4) { header ->
                        if (header[0] != 5.toByte() || header[1] != 1.toByte() || header[3] != 3.toByte()) {
                            protocolError = "SOCKS request is not a domain CONNECT: ${header.toList()}"
                            return@receiveExact
                        }
                        receiveExact(connection, 1) { length ->
                            val hostLength = length[0].toInt() and 0xff
                            receiveExact(connection, hostLength + 2) { address ->
                                targetHost = address.copyOfRange(0, hostLength).decodeToString()
                                targetPort =
                                    ((address[hostLength].toInt() and 0xff) shl 8) or
                                    (address[hostLength + 1].toInt() and 0xff)
                                send(connection, byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)) {
                                    receiveRequest(connection)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun receiveRequest(connection: nw_connection_t) {
        var pending = byteArrayOf()
        lateinit var receiveMore: () -> Unit
        receiveMore = {
            quartz_nw_connection_receive(connection, 1u, 65_536u) { data, isComplete, error ->
                if (error != null || isComplete) {
                    if (protocolError == null && pending.isNotEmpty()) protocolError = "Connection ended inside an RPC request"
                    return@quartz_nw_connection_receive
                }
                pending += copyBytes(data)
                val newline = pending.indexOfFirst { it == 0x0a.toByte() }
                if (newline < 0) {
                    receiveMore()
                } else {
                    val request = pending.copyOfRange(0, newline).decodeToString()
                    requestCount++
                    pending = pending.copyOfRange(newline + 1, pending.size)
                    send(connection, responseFor(request).encodeToByteArray()) { receiveMore() }
                }
            }
        }
        receiveMore()
    }

    private fun responseFor(request: String): String {
        val id = ID_REGEX.find(request)?.groupValues?.get(1) ?: "1"
        return when (reply) {
            LoopbackReply.MALFORMED -> "this is not JSON\n"
            LoopbackReply.SERVER_ERROR ->
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-1,\"message\":\"fixture failure\"}}\n"
            LoopbackReply.SUCCESS -> {
                val result =
                    if (request.contains("server.version")) {
                        "[\"ElectrumX 1.16\",\"1.4\"]"
                    } else {
                        "[]"
                    }
                "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n"
            }
        }
    }

    private fun receiveExact(
        connection: nw_connection_t,
        size: Int,
        onComplete: (ByteArray) -> Unit,
    ) {
        if (size == 0) {
            onComplete(byteArrayOf())
            return
        }
        var received = byteArrayOf()
        lateinit var receiveMore: () -> Unit
        receiveMore = {
            quartz_nw_connection_receive(connection, 1u, (size - received.size).toUInt()) { data, isComplete, error ->
                if (error != null || isComplete) {
                    protocolError = "Connection ended during SOCKS handshake"
                    return@quartz_nw_connection_receive
                }
                received += copyBytes(data)
                if (received.size == size) onComplete(received) else receiveMore()
            }
        }
        receiveMore()
    }

    private fun send(
        connection: nw_connection_t,
        bytes: ByteArray,
        onComplete: () -> Unit,
    ) {
        val context =
            nw_content_context_create("com.vitorpamplona.quartz.electrumx.fixture")
                ?: error("Could not create fixture response context")
        val pinned = bytes.pin()
        val data = dispatch_data_create(pinned.addressOf(0), bytes.size.toULong(), queue, null)
        if (data == null) {
            pinned.unpin()
            protocolError = "Could not allocate fixture response"
            return
        }
        val completion: nw_connection_send_completion_t = { error ->
            pinned.unpin()
            if (error != null) protocolError = "Fixture send failed: $error" else onComplete()
        }
        nw_connection_send(connection, data, context, true, completion)
    }

    private companion object {
        val ID_REGEX = "\"id\":([0-9]+)".toRegex()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun copyBytes(data: dispatch_data_t): ByteArray {
    if (data == null) return byteArrayOf()
    val result = ByteArray(dispatch_data_get_size(data).toInt())
    var offset = 0
    dispatch_data_apply(data) { _, _, buffer, size ->
        val length = size.toInt()
        if (buffer != null && length > 0) {
            buffer.readBytes(length).copyInto(result, offset)
            offset += length
        }
        true
    }
    return result
}
