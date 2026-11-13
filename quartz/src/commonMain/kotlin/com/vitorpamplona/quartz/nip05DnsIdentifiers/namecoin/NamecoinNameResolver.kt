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

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Detailed outcome of a Namecoin resolution attempt. */
sealed class NamecoinResolveOutcome {
    data class Success(
        val result: NamecoinNostrResult,
    ) : NamecoinResolveOutcome()

    /** The name does not exist on the Namecoin blockchain. */
    data class NameNotFound(
        val name: String,
    ) : NamecoinResolveOutcome()

    /** The selected record was valid but has no usable root nostr pubkey. */
    data class NoNostrField(
        val name: String,
    ) : NamecoinResolveOutcome()

    /** A blockchain value or one of its traversed constructs is malformed. */
    data class MalformedRecord(
        val name: String,
        val error: String,
    ) : NamecoinResolveOutcome()

    /** An import failed; local records were not used as a replacement. */
    data class ImportFailed(
        val name: String,
        val target: String,
        val cause: NamecoinResolveOutcome,
    ) : NamecoinResolveOutcome()

    /** A delegation failed; local records were not used as a replacement. */
    data class DelegationFailed(
        val name: String,
        val target: String,
        val cause: NamecoinResolveOutcome,
    ) : NamecoinResolveOutcome()

    /** A name/selector pair recurred while it was already being resolved. */
    data class CyclicReference(
        val name: String,
        val path: List<String>,
    ) : NamecoinResolveOutcome()

    /** The traversal-wide backend lookup budget was exhausted. */
    data class LookupBudgetExhausted(
        val name: String,
        val limit: Int,
    ) : NamecoinResolveOutcome()

    /** The combined reference/map traversal depth bound was exceeded. */
    data class DepthLimitExceeded(
        val name: String,
        val limit: Int,
    ) : NamecoinResolveOutcome()

    /** All ElectrumX servers were unreachable. */
    data class ServersUnreachable(
        val message: String,
    ) : NamecoinResolveOutcome()

    /** The identifier could not be parsed as a Namecoin name. */
    data class InvalidIdentifier(
        val identifier: String,
    ) : NamecoinResolveOutcome()

    /** Timed out waiting for a response. */
    data object Timeout : NamecoinResolveOutcome()
}

/** Bounds shared by every lookup and reference in one resolution traversal. */
data class NamecoinResolutionLimits(
    val maxLookups: Int = 16,
    val maxDepth: Int = 16,
) {
    init {
        require(maxLookups >= 1) { "maxLookups must be at least 1" }
        require(maxDepth >= 1) { "maxDepth must be at least 1" }
    }
}

/** Result of resolving a Namecoin name to Nostr identity data. */
data class NamecoinNostrResult(
    /** Hex-encoded 32-byte Schnorr public key. */
    val pubkey: String,
    /** Optional relay URLs where this user can be found. */
    val relays: List<String> = emptyList(),
    /** The apex Namecoin name that was resolved (for example, d/example). */
    val namecoinName: String,
    /** NIP-05 local part, or the selected dotted subdomain ("_" for apex). */
    val localPart: String = "_",
)

/**
 * Resolves Namecoin names to Nostr public keys without transport/UI policy.
 *
 * Browser callers should pass a URL-derived hostname to [resolveDomainDetailed].
 * NIP-05 callers use [resolve] and [resolveDetailed].
 *
 * Each call walks one traversal: the apex record, then the `import`s, `delegate`s
 * and `map` children on the selected path only, bounded by [NamecoinResolutionLimits]
 * and the overall `lookupTimeoutMs`. Any failure along that path fails the whole
 * resolution; local items are never used as a fallback for a failed import or delegate.
 *
 * Trust: the pubkey is whatever the ElectrumX servers return for each name on the path.
 * A `delegate` hands the record to its target's owner (ncdns semantics: local items are
 * ignored), and an `import` fills in any item the record lacks. If a referenced name
 * expires and someone else registers it, they control what this name resolves to, as
 * with a dangling DNS CNAME.
 */
class NamecoinNameResolver private constructor(
    private val electrumxClient: IElectrumXClient,
    private val resolutionLimits: NamecoinResolutionLimits,
    private val lookupTimeoutMs: Long,
    private val serverListProvider: () -> List<ElectrumxServer>,
) {
    constructor(
        electrumxClient: IElectrumXClient,
        lookupTimeoutMs: Long = 20_000L,
        serverListProvider: () -> List<ElectrumxServer>,
    ) : this(electrumxClient, NamecoinResolutionLimits(), lookupTimeoutMs, serverListProvider)

    constructor(
        electrumxClient: IElectrumXClient,
        lookupTimeoutMs: Long = 20_000L,
        resolutionLimits: NamecoinResolutionLimits,
        serverListProvider: () -> List<ElectrumxServer>,
    ) : this(electrumxClient, resolutionLimits, lookupTimeoutMs, serverListProvider)

    @Suppress("DEPRECATION")
    @Deprecated(
        "Quartz no longer picks ElectrumX servers: pass serverListProvider. Removed in 3.0.0.",
        ReplaceWith("NamecoinNameResolver(electrumxClient, lookupTimeoutMs, serverListProvider)"),
    )
    constructor(
        electrumxClient: IElectrumXClient,
        lookupTimeoutMs: Long = 20_000L,
    ) : this(electrumxClient, NamecoinResolutionLimits(), lookupTimeoutMs, { DEFAULT_ELECTRUMX_SERVERS })

    companion object {
        private val HEX_PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val LOCAL_PART_REGEX = Regex("^[a-z0-9._-]+$")

        /** Check whether an identifier should be routed to Namecoin NIP-05 resolution. */
        fun isNamecoinIdentifier(identifier: String): Boolean {
            val normalized = identifier.trim().lowercase()
            return normalized.endsWith(".bit") ||
                normalized.startsWith("d/") ||
                normalized.startsWith("id/")
        }

        internal fun isValidPubkey(value: String): Boolean = HEX_PUBKEY_REGEX.matches(value)
    }

    /** Resolve a NIP-05-style identifier, returning null for every failure. */
    suspend fun resolve(identifier: String): NamecoinNostrResult? = (resolveDetailed(identifier) as? NamecoinResolveOutcome.Success)?.result

    /** Resolve a NIP-05-style identifier with a detailed, fail-closed outcome. */
    suspend fun resolveDetailed(identifier: String): NamecoinResolveOutcome {
        val parsed = parseIdentifier(identifier) ?: return NamecoinResolveOutcome.InvalidIdentifier(identifier)
        return resolveWithTimeout(parsed)
    }

    /**
     * Resolve a URL-derived .bit hostname to the selected record's root pubkey.
     * A full URL, port, path, userinfo, or non-.bit hostname is rejected.
     */
    suspend fun resolveDomain(hostname: String): NamecoinNostrResult? = (resolveDomainDetailed(hostname) as? NamecoinResolveOutcome.Success)?.result

    /**
     * Resolve a .bit hostname with detailed failure and no-pubkey outcomes.
     *
     * Pass the ASCII hostname already taken from the URL; one trailing dot and letter case
     * are normalized. `www.example.bit` looks up `d/example` and walks `map.www`. Only a
     * direct root pubkey (`"nostr": "<hex>"`, `nostr.pubkey`) or `nostr.names["_"]` of the
     * selected record counts; a subdomain does not inherit its parent's pubkey.
     *
     * [NamecoinResolveOutcome.NoNostrField] is the only outcome meaning "the record resolved
     * but names no pubkey". Every other non-[NamecoinResolveOutcome.Success] outcome is a
     * resolution failure and must not be treated as a site without a pubkey.
     */
    suspend fun resolveDomainDetailed(hostname: String): NamecoinResolveOutcome {
        val parsed = parseBitHostname(hostname) ?: return NamecoinResolveOutcome.InvalidIdentifier(hostname)
        return resolveWithTimeout(parsed)
    }

    private enum class Namespace { DOMAIN, HOST, IDENTITY }

    private data class ParsedIdentifier(
        val namecoinName: String,
        val localPart: String = "_",
        val namespace: Namespace,
        val subdomainSelector: String = "",
    )

    private suspend fun resolveWithTimeout(parsed: ParsedIdentifier): NamecoinResolveOutcome =
        withTimeoutOrNull(lookupTimeoutMs) { performLookupDetailed(parsed) }
            ?: NamecoinResolveOutcome.Timeout

    private suspend fun performLookupDetailed(parsed: ParsedIdentifier): NamecoinResolveOutcome {
        val traversal =
            NamecoinImportResolver(resolutionLimits) { name ->
                electrumxClient.nameShowWithFallback(name, serverListProvider())?.value
            }

        val selected = traversal.resolve(parsed.namecoinName, parsed.subdomainSelector)
        val value =
            when (selected) {
                is NamecoinTraversalResult.Success -> selected.value
                is NamecoinTraversalResult.Failure -> return selected.outcome
                is NamecoinTraversalResult.SelectorNotFound ->
                    return NamecoinResolveOutcome.NoNostrField(parsed.namecoinName)
            }

        val result =
            when (parsed.namespace) {
                Namespace.DOMAIN -> extractFromDomainValue(value, parsed)
                Namespace.HOST -> extractFromBitDomainValue(value, parsed)
                Namespace.IDENTITY -> extractFromIdentityValue(value, parsed)
            }

        return result?.let(NamecoinResolveOutcome::Success)
            ?: NamecoinResolveOutcome.NoNostrField(parsed.namecoinName)
    }

    private fun parseIdentifier(raw: String): ParsedIdentifier? {
        val input = raw.trim()
        if (input.startsWith("d/", ignoreCase = true)) {
            val normalized = input.lowercase()
            if (!NamecoinDomainNames.isValidReference(normalized)) return null
            return ParsedIdentifier(normalized, namespace = Namespace.DOMAIN)
        }
        if (input.startsWith("id/", ignoreCase = true)) {
            val normalized = input.lowercase()
            if (normalized.length !in 4..255 || !normalized.all { it.code in 33..126 }) return null
            return ParsedIdentifier(normalized, namespace = Namespace.IDENTITY)
        }

        val at = input.indexOf('@')
        if (at >= 0) {
            if (at != input.lastIndexOf('@')) return null
            val localPart = input.substring(0, at).lowercase().ifEmpty { "_" }
            if (!LOCAL_PART_REGEX.matches(localPart)) return null
            val host = parseBitHostname(input.substring(at + 1)) ?: return null
            return host.copy(localPart = localPart, namespace = Namespace.DOMAIN)
        }

        return parseBitHostname(input, Namespace.DOMAIN)
    }

    private fun parseBitHostname(
        raw: String,
        namespace: Namespace = Namespace.HOST,
    ): ParsedIdentifier? {
        val trimmed = raw.trim()
        val input = trimmed.removeSuffix(".")
        if (input.isEmpty() || input.length > 253) return null
        val labels = input.lowercase().split('.')
        if (labels.size < 2 || labels.last() != "bit") return null

        val apex = labels[labels.lastIndex - 1]
        if (!NamecoinDomainNames.isValidApex(apex)) return null
        val subdomains = labels.dropLast(2)
        if (subdomains.any { !NamecoinDomainNames.isValidMapLabel(it) }) return null

        return ParsedIdentifier(
            namecoinName = "d/$apex",
            namespace = namespace,
            subdomainSelector = subdomains.joinToString("."),
        )
    }

    /** Browser hostname extraction never picks an arbitrary names entry. */
    private fun extractFromBitDomainValue(
        value: JsonObject,
        parsed: ParsedIdentifier,
    ): NamecoinNostrResult? {
        val nostrField = value["nostr"] ?: return null
        var relays = emptyList<String>()
        val pubkey =
            when (nostrField) {
                is JsonPrimitive -> nostrField.content.takeIf { nostrField.isString && isValidPubkey(it) }
                is JsonObject -> {
                    val direct = (nostrField["pubkey"] as? JsonPrimitive)?.content
                    val root = (nostrField["names"] as? JsonObject)?.get("_") as? JsonPrimitive
                    when {
                        direct != null && isValidPubkey(direct) -> {
                            relays = extractRelayArray(nostrField)
                            direct
                        }

                        root != null && isValidPubkey(root.content) -> {
                            relays = extractRelays(nostrField, root.content)
                            root.content
                        }

                        else -> null
                    }
                }

                else -> null
            } ?: return null

        return NamecoinNostrResult(
            pubkey = pubkey.lowercase(),
            relays = relays,
            namecoinName = parsed.namecoinName,
            localPart = parsed.subdomainSelector.ifEmpty { "_" },
        )
    }

    /** Existing NIP-05-compatible domain extraction, including root fallback. */
    private fun extractFromDomainValue(
        value: JsonObject,
        parsed: ParsedIdentifier,
    ): NamecoinNostrResult? {
        val nostrField = value["nostr"] ?: return null
        if (nostrField is JsonPrimitive && nostrField.isString) {
            val pubkey = nostrField.content
            if (parsed.localPart == "_" && isValidPubkey(pubkey)) {
                return NamecoinNostrResult(pubkey.lowercase(), namecoinName = parsed.namecoinName)
            }
            if (parsed.localPart != "_") return null
        }

        if (nostrField is JsonObject) {
            val names = nostrField["names"] as? JsonObject
            if (names != null) {
                val exact = names[parsed.localPart] as? JsonPrimitive
                val root = names["_"] as? JsonPrimitive
                val first = if (parsed.localPart == "_") names.entries.firstOrNull() else null
                val matched =
                    when {
                        exact != null && isValidPubkey(exact.content) -> parsed.localPart to exact.content
                        root != null && isValidPubkey(root.content) -> "_" to root.content
                        first != null &&
                            (first.value as? JsonPrimitive)?.content?.let(::isValidPubkey) == true ->
                            first.key to (first.value as JsonPrimitive).content

                        else -> null
                    }
                if (matched != null) {
                    return NamecoinNostrResult(
                        pubkey = matched.second.lowercase(),
                        relays = extractRelays(nostrField, matched.second),
                        namecoinName = parsed.namecoinName,
                        localPart = matched.first,
                    )
                }
                if (parsed.localPart != "_") return null
            }

            if (parsed.localPart == "_") {
                val pubkey = (nostrField["pubkey"] as? JsonPrimitive)?.content
                if (pubkey != null && isValidPubkey(pubkey)) {
                    return NamecoinNostrResult(
                        pubkey = pubkey.lowercase(),
                        relays = extractRelayArray(nostrField),
                        namecoinName = parsed.namecoinName,
                    )
                }
            }
        }
        return null
    }

    private fun extractFromIdentityValue(
        value: JsonObject,
        parsed: ParsedIdentifier,
    ): NamecoinNostrResult? {
        val nostrField = value["nostr"] ?: return null
        if (nostrField is JsonPrimitive && nostrField.isString && isValidPubkey(nostrField.content)) {
            return NamecoinNostrResult(nostrField.content.lowercase(), namecoinName = parsed.namecoinName)
        }
        if (nostrField is JsonObject) {
            val pubkey = (nostrField["pubkey"] as? JsonPrimitive)?.content
            if (pubkey != null && isValidPubkey(pubkey)) {
                return NamecoinNostrResult(
                    pubkey = pubkey.lowercase(),
                    relays = extractRelayArray(nostrField),
                    namecoinName = parsed.namecoinName,
                )
            }
            val root = (nostrField["names"] as? JsonObject)?.get("_") as? JsonPrimitive
            if (root != null && isValidPubkey(root.content)) {
                return NamecoinNostrResult(
                    pubkey = root.content.lowercase(),
                    relays = extractRelays(nostrField, root.content),
                    namecoinName = parsed.namecoinName,
                )
            }
        }
        return null
    }

    private fun extractRelays(
        nostrObj: JsonObject,
        pubkey: String,
    ): List<String> {
        val relaysMap = nostrObj["relays"] as? JsonObject ?: return emptyList()
        val relayArray =
            (relaysMap[pubkey.lowercase()] ?: relaysMap[pubkey]) as? JsonArray
                ?: return emptyList()
        return relayArray.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    private fun extractRelayArray(nostrObj: JsonObject): List<String> =
        (nostrObj["relays"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.content
        } ?: emptyList()
}
