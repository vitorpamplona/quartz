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

import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.concurrent.ConcurrentMap
import com.vitorpamplona.quartz.utils.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The explicit network route for one ElectrumX server. */
sealed interface ElectrumXRoute {
    /** An explicit direct (or app-session-bypass) decision. Quartz never infers this from a missing proxy. */
    data object Direct : ElectrumXRoute

    /** Carry the target hostname through SOCKS5, then negotiate TLS with that same target hostname. */
    data class Socks5(
        val host: String,
        val port: Int,
    ) : ElectrumXRoute

    /** A route is required by app policy but currently unavailable. The lookup must fail closed. */
    data object Unavailable : ElectrumXRoute
}

/** Called for every connection so an app can apply per-server policy and a changing Tor port. */
fun interface ElectrumXRouteProvider {
    fun routeFor(server: ElectrumxServer): ElectrumXRoute
}

internal interface ElectrumXConnection : ElectrumXExchange {
    val tlsVersion: String?
    val serverCertPem: String?
    val certFingerprint: String?

    suspend fun close()
}

internal fun interface ElectrumXConnectionFactory {
    suspend fun connect(
        server: ElectrumxServer,
        route: ElectrumXRoute,
        pinnedCertsPem: List<String>,
    ): ElectrumXConnection
}

private suspend fun <T> withOperationTimeout(
    timeoutMs: Long,
    operation: String,
    block: suspend () -> T,
): T {
    try {
        return withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        // An outer caller's timeout/cancellation must retain structured-concurrency semantics.
        currentCoroutineContext().ensureActive()
        throw ElectrumXProtocolException("$operation timed out after ${timeoutMs}ms", e)
    }
}

private inline fun <T> NSLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** Lightweight query-only ElectrumX client backed by Apple's Network framework. */
@OptIn(ExperimentalAtomicApi::class)
class ElectrumXClient(
    private val connectTimeoutMs: Long = 10_000L,
    private val readTimeoutMs: Long = 15_000L,
    private val routeProvider: ElectrumXRouteProvider = ElectrumXRouteProvider { ElectrumXRoute.Direct },
    private val hedgeDelayMs: Long = DEFAULT_HEDGE_DELAY_MS,
) : IElectrumXClient {
    private var connectionFactory: ElectrumXConnectionFactory = NetworkElectrumXConnectionFactory()
    private val serverMutexes = ConcurrentMap<String, Mutex>()
    private val dynamicCerts = AtomicReference<List<String>>(emptyList())

    internal constructor(
        connectTimeoutMs: Long,
        readTimeoutMs: Long,
        routeProvider: ElectrumXRouteProvider,
        hedgeDelayMs: Long,
        connectionFactory: ElectrumXConnectionFactory,
    ) : this(connectTimeoutMs, readTimeoutMs, routeProvider, hedgeDelayMs) {
        this.connectionFactory = connectionFactory
    }

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        require(hedgeDelayMs >= 0) { "hedgeDelayMs must not be negative" }
    }

    suspend fun nameShow(
        identifier: String,
        server: ElectrumxServer,
    ): NameShowResult? =
        try {
            queryServer(identifier, server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NamecoinLookupException) {
            throw e
        } catch (e: Exception) {
            logFailure(identifier, server, e)
            null
        }

    @Suppress("DEPRECATION")
    @Deprecated(
        "Quartz no longer picks ElectrumX servers: pass one. Removed in 3.0.0.",
        ReplaceWith("nameShow(identifier, server)"),
    )
    suspend fun nameShow(identifier: String): NameShowResult? = nameShow(identifier, DEFAULT_ELECTRUMX_SERVERS.first())

    override suspend fun nameShowWithFallback(
        identifier: String,
        servers: List<ElectrumxServer>,
    ): NameShowResult? =
        coroutineScope {
            if (servers.isEmpty()) throw NamecoinLookupException.ServersUnreachable(null)
            HedgedRace(this, identifier, servers).run()
        }

    suspend fun testServer(
        server: ElectrumxServer,
        testName: String? = "d/testls",
    ): ServerTestResult {
        val startedAt = currentTimeMillis()
        return try {
            val connection = openConnection(server)
            try {
                connection.call(
                    1,
                    "server.version",
                    listOf(JsonPrimitive("AmethystNMC/0.1"), JsonPrimitive(ElectrumXProtocol.PROTOCOL_VERSION)),
                )
                if (testName != null) {
                    connection.call(
                        2,
                        "blockchain.scripthash.get_history",
                        listOf(JsonPrimitive(ElectrumXProtocol.scriptHash(testName))),
                    )
                }
                ServerTestResult(
                    server = server,
                    success = true,
                    responseTimeMs = currentTimeMillis() - startedAt,
                    tlsVersion = connection.tlsVersion,
                    serverCertPem = connection.serverCertPem,
                    certFingerprint = connection.certFingerprint,
                )
            } finally {
                connection.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerTestResult(
                server = server,
                success = false,
                responseTimeMs = currentTimeMillis() - startedAt,
                error = e.message?.take(150) ?: "Unknown error",
                serverCertPem = (e as? ElectrumXPeerCertificateException)?.certificate?.pem,
                certFingerprint = (e as? ElectrumXPeerCertificateException)?.certificate?.fingerprint,
            )
        }
    }

    @Deprecated("Pin the certificate on its server instead. Removed in 3.0.0.")
    fun addPinnedCert(pem: String) {
        while (true) {
            val current = dynamicCerts.load()
            if (dynamicCerts.compareAndSet(current, current + pem)) return
        }
    }

    @Deprecated("Pin each certificate on its server in ElectrumxServer.pinnedCertsPem. Removed in 3.0.0.")
    fun setDynamicCerts(pems: List<String>) {
        dynamicCerts.store(pems.toList())
    }

    private suspend fun queryServer(
        identifier: String,
        server: ElectrumxServer,
    ): NameShowResult? =
        serverMutexes.getOrPut(server.host + ":" + server.port) { Mutex() }.withLock {
            val connection = openConnection(server)
            try {
                queryNamecoinName(identifier, connection)
            } finally {
                connection.close()
            }
        }

    private suspend fun openConnection(server: ElectrumxServer): ElectrumXConnection {
        val route = routeProvider.routeFor(server)
        if (route is ElectrumXRoute.Unavailable) {
            throw NamecoinLookupException.ServersUnreachable(
                IllegalStateException("No permitted route to " + server.host + ":" + server.port),
            )
        }
        val connection =
            withOperationTimeout(connectTimeoutMs, "ElectrumX connection") {
                connectionFactory.connect(server, route, trustPinsFor(server))
            }
        return TimeoutConnection(connection)
    }

    private inner class TimeoutConnection(
        private val delegate: ElectrumXConnection,
    ) : ElectrumXConnection {
        override val tlsVersion: String? get() = delegate.tlsVersion
        override val serverCertPem: String? get() = delegate.serverCertPem
        override val certFingerprint: String? get() = delegate.certFingerprint

        override suspend fun call(
            requestId: Int,
            method: String,
            params: List<JsonElement>,
        ): JsonElement =
            withOperationTimeout(readTimeoutMs, "ElectrumX response") {
                delegate.call(requestId, method, params)
            }

        override suspend fun close() = delegate.close()
    }

    @Suppress("DEPRECATION")
    private fun trustPinsFor(server: ElectrumxServer): List<String> =
        if (server.pinnedCertsPem.isNotEmpty()) {
            server.pinnedCertsPem
        } else if (server.usePinnedTrustStore || server.host.endsWith(".onion")) {
            LEGACY_PINNED_CERTS + dynamicCerts.load()
        } else {
            emptyList()
        }

    private fun logFailure(
        identifier: String,
        server: ElectrumxServer,
        e: Exception,
    ) = Log.w("ElectrumXClient", "Failed to look up $identifier on " + server.host + ":" + server.port, e)

    private inner class HedgedRace(
        private val scope: CoroutineScope,
        private val identifier: String,
        private val servers: List<ElectrumxServer>,
    ) {
        private val answer = CompletableDeferred<NameShowResult>()
        private val raceJob = SupervisorJob(scope.coroutineContext[Job])
        private val raceScope = CoroutineScope(scope.coroutineContext + raceJob)
        private val lock = NSLock()
        private var nextIndex = 0
        private var running = 0
        private var negative: NamecoinLookupException? = null
        private var lastError: Exception? = null

        suspend fun run(): NameShowResult {
            startNext()
            try {
                return answer.await()
            } finally {
                raceJob.cancel()
            }
        }

        private fun startNext() {
            val server =
                lock.withLock {
                    if (negative != null || nextIndex >= servers.size) return
                    running++
                    servers[nextIndex++]
                }
            raceScope.launch { attempt(server) }
        }

        private suspend fun CoroutineScope.attempt(server: ElectrumxServer) {
            val hedge =
                launch {
                    delay(hedgeDelayMs)
                    startNext()
                }
            try {
                val result = queryServer(identifier, server)
                if (result != null) answer.complete(result) else failed(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NamecoinLookupException.NameNotFound) {
                negativeAnswer(e)
            } catch (e: NamecoinLookupException.NameExpired) {
                negativeAnswer(e)
            } catch (e: Exception) {
                logFailure(identifier, server, e)
                failed(e)
            } catch (e: Throwable) {
                answer.completeExceptionally(e)
            } finally {
                hedge.cancel()
            }
        }

        private fun failed(error: Exception?) {
            lock.withLock {
                running--
                if (error != null) lastError = error
            }
            startNext()
            settleIfDone()
        }

        private fun negativeAnswer(e: NamecoinLookupException) {
            val first =
                lock.withLock {
                    running--
                    (negative == null).also { if (it) negative = e }
                }
            if (first) {
                raceScope.launch {
                    delay(hedgeDelayMs)
                    answer.completeExceptionally(e)
                }
            }
            settleIfDone()
        }

        private fun settleIfDone() {
            val outcome =
                lock.withLock {
                    if (running > 0 || (negative == null && nextIndex < servers.size)) return
                    negative ?: NamecoinLookupException.ServersUnreachable(lastError)
                }
            answer.completeExceptionally(outcome)
        }
    }

    companion object {
        const val DEFAULT_HEDGE_DELAY_MS = 1_500L
        private val LEGACY_PINNED_CERTS = listOf(NMC2_BITCOINS_SK_CERT_PEM, RELAY_TESTLS_BIT_CERT_PEM)
    }
}
