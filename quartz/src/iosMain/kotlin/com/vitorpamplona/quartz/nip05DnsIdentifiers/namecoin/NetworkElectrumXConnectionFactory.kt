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
import com.vitorpamplona.quartz.network.quartz_nw_socks5_create_options
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import platform.CoreFoundation.CFRelease
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_copy_protocol_metadata
import platform.Network.nw_connection_create
import platform.Network.nw_connection_send
import platform.Network.nw_connection_send_completion_t
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_configure_protocol_block_t
import platform.Network.nw_parameters_copy_default_protocol_stack
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_set_prefer_no_proxy
import platform.Network.nw_parameters_t
import platform.Network.nw_protocol_copy_tls_definition
import platform.Network.nw_protocol_stack_prepend_application_protocol
import platform.Network.nw_protocol_stack_set_transport_protocol
import platform.Network.nw_tcp_create_options
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Network.nw_tls_create_options
import platform.Security.sec_protocol_metadata_get_negotiated_tls_protocol_version
import platform.Security.sec_protocol_options_set_tls_server_name
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_trust_copy_ref
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
internal class NetworkElectrumXConnectionFactory : ElectrumXConnectionFactory {
    override suspend fun connect(
        server: ElectrumxServer,
        route: ElectrumXRoute,
        pinnedCertsPem: List<String>,
    ): ElectrumXConnection {
        when (route) {
            ElectrumXRoute.Unavailable ->
                throw NamecoinLookupException.ServersUnreachable(
                    IllegalStateException("No permitted route to " + server.host + ":" + server.port),
                )
            is ElectrumXRoute.Socks5 -> {
                require(route.host.isNotEmpty()) { "SOCKS5 host must not be empty" }
                require(route.port in 1..65535) { "SOCKS5 port is out of range" }
            }
            ElectrumXRoute.Direct -> Unit
        }

        require(server.host.isNotEmpty()) { "ElectrumX host must not be empty" }
        require(server.port in 1..65535) { "ElectrumX port is out of range" }
        val queue = dispatch_queue_create("com.vitorpamplona.quartz.electrumx.connection", null)
        var captured: AppleCertificateCapture? = null
        var connectionToReject: nw_connection_t = null
        var tlsRejected = false
        val configureTls: nw_parameters_configure_protocol_block_t = { options ->
            // TLS always authenticates the target, never the SOCKS proxy.
            sec_protocol_options_set_tls_server_name(options, server.host)
            sec_protocol_options_set_verify_block(
                options,
                { _, trust, completion ->
                    val nativeTrust = sec_trust_copy_ref(trust)
                    val trusted =
                        if (nativeTrust == null) {
                            false
                        } else {
                            try {
                                if (captured == null) captured = AppleCertificateSupport.captureLeaf(nativeTrust)
                                AppleCertificateSupport.evaluate(
                                    nativeTrust,
                                    pinnedCertsPem,
                                    verifyHostname = requiresHostnameValidation(server, pinnedCertsPem),
                                )
                            } finally {
                                CFRelease(nativeTrust)
                            }
                        }
                    if (!trusted) tlsRejected = true
                    completion?.invoke(trusted)
                    if (!trusted) connectionToReject?.let { nw_connection_cancel(it) }
                },
                queue,
            )
        }
        val parameters =
            when (route) {
                is ElectrumXRoute.Socks5 -> createSocks5TcpParameters(server, configureTls)
                ElectrumXRoute.Direct ->
                    if (server.useSsl) {
                        nw_parameters_create_secure_tcp(configureTls) { _ -> Unit }
                            ?: throw ElectrumXProtocolException("Could not create Network.framework parameters")
                    } else {
                        createCleartextTcpParameters()
                    }
                ElectrumXRoute.Unavailable -> error("Unavailable route was rejected before connection creation")
            }
        if (route == ElectrumXRoute.Direct) nw_parameters_set_prefer_no_proxy(parameters, true)
        val endpoint =
            when (route) {
                is ElectrumXRoute.Socks5 -> nw_endpoint_create_host(route.host, route.port.toString())
                else -> nw_endpoint_create_host(server.host, server.port.toString())
            } ?: throw ElectrumXProtocolException("Could not create ElectrumX endpoint")
        val connection =
            nw_connection_create(endpoint, parameters)
                ?: throw ElectrumXProtocolException("Could not create Network.framework connection")
        connectionToReject = connection
        val result = NetworkElectrumXConnection(connection, server.useSsl) { captured }

        return suspendCancellableCoroutine { continuation ->
            nw_connection_set_state_changed_handler(connection) { state, error ->
                when (state) {
                    nw_connection_state_waiting ->
                        if (route is ElectrumXRoute.Socks5 && error != null && continuation.isActive) {
                            // A required proxy that cannot be reached is terminal. Never wait for
                            // Network.framework to recover through another route or direct target.
                            continuation.resumeWithException(
                                networkError("ElectrumX SOCKS5 proxy connection failed", error),
                            )
                            nw_connection_cancel(connection)
                        }
                    nw_connection_state_ready -> {
                        result.readTlsVersion()
                        if (continuation.isActive) continuation.resume(result)
                    }
                    nw_connection_state_failed ->
                        if (continuation.isActive) {
                            val failure = networkError("ElectrumX connection failed", error)
                            val peer = captured
                            continuation.resumeWithException(
                                if (peer == null) failure else ElectrumXPeerCertificateException(failure, peer),
                            )
                        }
                    nw_connection_state_cancelled ->
                        if (continuation.isActive) {
                            if (tlsRejected) {
                                val failure = networkError("ElectrumX TLS handshake was rejected", error)
                                val peer = captured
                                continuation.resumeWithException(
                                    if (peer == null) failure else ElectrumXPeerCertificateException(failure, peer),
                                )
                            } else {
                                continuation.resumeWithException(CancellationException("ElectrumX connection was cancelled"))
                            }
                        }
                }
            }
            continuation.invokeOnCancellation { nw_connection_cancel(connection) }
            nw_connection_set_queue(connection, queue)
            nw_connection_start(connection)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun createCleartextTcpParameters(): nw_parameters_t {
    // Kotlin/Native wraps Apple's special block constants and loses their sentinel identity.
    // An explicit TCP-only stack cannot accidentally negotiate TLS through that interop path.
    val parameters = nw_parameters_create() ?: throw ElectrumXProtocolException("Could not create TCP parameters")
    val stack =
        nw_parameters_copy_default_protocol_stack(parameters)
            ?: throw ElectrumXProtocolException("Could not create TCP protocol stack")
    val tcp = nw_tcp_create_options() ?: throw ElectrumXProtocolException("Could not create TCP options")
    nw_protocol_stack_set_transport_protocol(stack, tcp)
    return parameters
}

@OptIn(ExperimentalForeignApi::class)
private fun createSocks5TcpParameters(
    server: ElectrumxServer,
    configureTls: nw_parameters_configure_protocol_block_t,
): nw_parameters_t {
    val parameters = createCleartextTcpParameters()
    val stack =
        nw_parameters_copy_default_protocol_stack(parameters)
            ?: throw ElectrumXProtocolException("Could not create SOCKS5 protocol stack")
    // Kotlin/Native releases these returned Objective-C objects with their wrappers.
    // A second native nw_release corrupts the protocol stack when its wrapper is finalized.
    val tlsOptions =
        if (server.useSsl) {
            val options =
                nw_tls_create_options()
                    ?: throw ElectrumXProtocolException("Could not create SOCKS5 TLS options")
            val securityOptions = nw_tls_copy_sec_protocol_options(options)
            configureTls?.invoke(securityOptions)
            options
        } else {
            null
        }
    val socksOptions =
        quartz_nw_socks5_create_options(server.host, server.port.toUShort(), tlsOptions)
            ?: throw ElectrumXProtocolException("Could not create SOCKS5 tunnel options")
    nw_protocol_stack_prepend_application_protocol(stack, socksOptions)
    // The only TCP destination is the proxy itself; no direct-target fallback exists.
    nw_parameters_set_prefer_no_proxy(parameters, true)
    return parameters
}

private fun requiresHostnameValidation(
    server: ElectrumxServer,
    pinnedCertsPem: List<String>,
): Boolean {
    val host =
        server.host
            .trim()
            .trimEnd('.')
            .lowercase()
    if (host.endsWith(".onion")) return false
    val pinnedIpLiteral = pinnedCertsPem.isNotEmpty() && (host.contains(':') || IPV4_LITERAL.matches(host))
    return !pinnedIpLiteral
}

private val IPV4_LITERAL = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")

internal class ElectrumXPeerCertificateException(
    cause: ElectrumXProtocolException,
    val certificate: AppleCertificateCapture,
) : ElectrumXProtocolException(cause.message ?: "ElectrumX TLS handshake failed", cause)

@OptIn(ExperimentalForeignApi::class)
private class NetworkElectrumXConnection(
    private val connection: nw_connection_t,
    private val usesTls: Boolean,
    private val capture: () -> AppleCertificateCapture?,
) : ElectrumXConnection {
    private val decoder = ElectrumXLineDecoder()
    private val calls = Mutex()
    private var negotiatedTlsVersion: String? = null

    override val tlsVersion: String? get() = negotiatedTlsVersion
    override val serverCertPem: String? get() = capture()?.pem
    override val certFingerprint: String? get() = capture()?.fingerprint

    fun readTlsVersion() {
        if (!usesTls) return
        val definition = nw_protocol_copy_tls_definition()
        val metadata = nw_connection_copy_protocol_metadata(connection, definition)
        negotiatedTlsVersion =
            when (sec_protocol_metadata_get_negotiated_tls_protocol_version(metadata).toUInt()) {
                TLS_1_2 -> "TLSv1.2"
                TLS_1_3 -> "TLSv1.3"
                else -> null
            }
    }

    override suspend fun call(
        requestId: Int,
        method: String,
        params: List<JsonElement>,
    ): JsonElement =
        calls.withLock {
            send((ElectrumXProtocol.request(requestId, method, params) + "\n").encodeToByteArray())
            while (true) {
                decoder.nextLine()?.let { return@withLock ElectrumXProtocol.response(it, requestId) }
                val chunk = receive()
                if (chunk.bytes.isNotEmpty()) decoder.append(chunk.bytes)
                decoder.nextLine()?.let { return@withLock ElectrumXProtocol.response(it, requestId) }
                if (chunk.isComplete) decoder.finish()
            }
            error("unreachable")
        }

    override suspend fun close() {
        nw_connection_cancel(connection)
    }

    private suspend fun send(bytes: ByteArray) =
        suspendCancellableCoroutine { continuation ->
            // Apple's exported default context crosses this Kotlin/Native boundary as a block.
            // A real context object preserves stream semantics without invoking that sentinel.
            val context =
                nw_content_context_create("com.vitorpamplona.quartz.electrumx.request")
                    ?: throw ElectrumXProtocolException("Could not create ElectrumX request context")
            val pinned = bytes.pin()
            val data = dispatch_data_create(pinned.addressOf(0), bytes.size.toULong(), null, null)
            if (data == null) {
                pinned.unpin()
                continuation.resumeWithException(ElectrumXProtocolException("Could not allocate ElectrumX request"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { nw_connection_cancel(connection) }
            val completion: nw_connection_send_completion_t = { error ->
                pinned.unpin()
                if (continuation.isActive) {
                    if (error == null) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(networkError("ElectrumX request failed", error))
                    }
                }
            }
            nw_connection_send(connection, data, context, true, completion)
        }

    private suspend fun receive(): NetworkChunk =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { nw_connection_cancel(connection) }
            quartz_nw_connection_receive(connection, 1u, MAX_RECEIVE_BYTES.toUInt()) { data, isComplete, error ->
                if (!continuation.isActive) return@quartz_nw_connection_receive
                if (error != null) {
                    continuation.resumeWithException(networkError("ElectrumX response failed", error))
                } else {
                    continuation.resume(NetworkChunk(copyDispatchData(data), isComplete))
                }
            }
        }

    private fun copyDispatchData(data: dispatch_data_t): ByteArray {
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

    private data class NetworkChunk(
        val bytes: ByteArray,
        val isComplete: Boolean,
    )

    companion object {
        private const val MAX_RECEIVE_BYTES = 64 * 1024
        private const val TLS_1_2 = 0x0303u
        private const val TLS_1_3 = 0x0304u
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun networkError(
    prefix: String,
    error: nw_error_t,
): ElectrumXProtocolException =
    ElectrumXProtocolException(
        prefix + " (Network.framework domain " + nw_error_get_error_domain(error) +
            ", code " + nw_error_get_error_code(error) + ")",
    )
