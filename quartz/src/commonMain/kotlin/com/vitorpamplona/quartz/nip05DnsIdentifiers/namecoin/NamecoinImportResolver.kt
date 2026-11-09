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

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface NamecoinTraversalResult {
    data class Success(
        val value: JsonObject,
    ) : NamecoinTraversalResult

    data class Failure(
        val outcome: NamecoinResolveOutcome,
    ) : NamecoinTraversalResult

    data class SelectorNotFound(
        val name: String,
        val selector: String,
    ) : NamecoinTraversalResult
}

/**
 * One fail-closed, lazy traversal through Namecoin Domain Name Objects.
 *
 * A new instance is created for every top-level resolution. Its lookup memo,
 * recursion stack, depth bound, and global lookup budget therefore cannot leak
 * into an unrelated resolution. Only the selected map branch is traversed.
 */
internal class NamecoinImportResolver(
    private val limits: NamecoinResolutionLimits,
    private val fetcher: suspend (namecoinName: String) -> String?,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val lookupMemo = mutableMapOf<String, NamecoinTraversalResult>()
    private val resolutionMemo = mutableMapOf<String, NamecoinTraversalResult>()
    private val activeReferences = mutableSetOf<String>()
    private val referenceStack = mutableListOf<String>()
    private var lookupCount = 0

    suspend fun resolve(
        namecoinName: String,
        selector: String = "",
    ): NamecoinTraversalResult {
        val labels =
            parseSelector(selector, allowWildcard = true)
                ?: return malformed(namecoinName, "invalid subdomain selector: $selector")
        return resolveReference(
            sourceName = namecoinName,
            targetName = namecoinName,
            selector = labels,
            depth = 0,
            operation = Operation.ROOT,
        )
    }

    private enum class Operation { ROOT, IMPORT, DELEGATE }

    private data class ImportOp(
        val name: String,
        val selector: List<String>,
    )

    private suspend fun resolveReference(
        sourceName: String,
        targetName: String,
        selector: List<String>,
        depth: Int,
        operation: Operation,
    ): NamecoinTraversalResult {
        if (depth > limits.maxDepth) {
            return failure(NamecoinResolveOutcome.DepthLimitExceeded(targetName, limits.maxDepth))
        }

        val selectorText = selector.joinToString(".")
        val visitKey = "$targetName|$selectorText"
        val memoKey = "$visitKey@$depth"
        if (operation != Operation.ROOT) {
            resolutionMemo[memoKey]?.let { return it }
        }

        if (visitKey in activeReferences) {
            return failure(
                NamecoinResolveOutcome.CyclicReference(
                    name = targetName,
                    path = referenceStack + visitKey,
                ),
            )
        }

        activeReferences += visitKey
        referenceStack += visitKey
        try {
            val record =
                when (val loaded = lookupRecord(targetName)) {
                    is NamecoinTraversalResult.Success -> loaded.value
                    is NamecoinTraversalResult.Failure -> {
                        return referenceFailure(operation, sourceName, targetName, loaded.outcome)
                    }

                    is NamecoinTraversalResult.SelectorNotFound -> error("lookup cannot select a subdomain")
                }

            val resolved = resolveObject(record, targetName, selector, depth)
            if (resolved is NamecoinTraversalResult.SelectorNotFound && operation != Operation.ROOT) {
                return referenceFailure(
                    operation = operation,
                    sourceName = sourceName,
                    targetName = targetName,
                    outcome =
                        NamecoinResolveOutcome.MalformedRecord(
                            name = targetName,
                            error = "subdomain selector not found: " + resolved.selector,
                        ),
                )
            }
            if (operation != Operation.ROOT &&
                !(resolved is NamecoinTraversalResult.Failure && resolved.outcome is NamecoinResolveOutcome.CyclicReference)
            ) {
                resolutionMemo[memoKey] = resolved
            }
            return resolved
        } finally {
            referenceStack.removeAt(referenceStack.lastIndex)
            activeReferences -= visitKey
        }
    }

    private suspend fun lookupRecord(name: String): NamecoinTraversalResult {
        lookupMemo[name]?.let { return it }
        if (lookupCount >= limits.maxLookups) {
            return failure(NamecoinResolveOutcome.LookupBudgetExhausted(name, limits.maxLookups))
        }
        lookupCount += 1

        val result =
            try {
                val raw = fetcher(name)
                if (raw == null) {
                    failure(NamecoinResolveOutcome.NameNotFound(name))
                } else {
                    try {
                        val element = json.parseToJsonElement(raw)
                        val obj =
                            element as? JsonObject
                                ?: return failure(
                                    NamecoinResolveOutcome.MalformedRecord(
                                        name = name,
                                        error = "top-level value is " + element::class.simpleName + ", expected JSON object",
                                    ),
                                )
                        NamecoinTraversalResult.Success(obj)
                    } catch (e: Exception) {
                        failure(
                            NamecoinResolveOutcome.MalformedRecord(
                                name = name,
                                error = e.message ?: "unparseable JSON value",
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NamecoinLookupException.NameNotFound) {
                failure(NamecoinResolveOutcome.NameNotFound(name))
            } catch (e: NamecoinLookupException.NameExpired) {
                failure(NamecoinResolveOutcome.NameNotFound(name))
            } catch (e: NamecoinLookupException.ServersUnreachable) {
                failure(
                    NamecoinResolveOutcome.ServersUnreachable(
                        e.message ?: "All ElectrumX servers unreachable",
                    ),
                )
            }

        lookupMemo[name] = result
        return result
    }

    private suspend fun resolveObject(
        raw: JsonObject,
        currentName: String,
        selector: List<String>,
        depth: Int,
    ): NamecoinTraversalResult {
        if (depth > limits.maxDepth) {
            return failure(NamecoinResolveOutcome.DepthLimitExceeded(currentName, limits.maxDepth))
        }

        // ncdns's deprecated delegate semantics are exclusive: a successful
        // delegate replaces this object; local records are not fallbacks.
        val delegateItem = raw["delegate"]
        if (delegateItem != null && delegateItem !is JsonNull) {
            val operations =
                parseOperations(delegateItem)
                    ?: return malformed(currentName, "invalid delegate item")
            if (operations.size != 1) {
                return malformed(currentName, "delegate requires exactly one target")
            }
            val op = operations.single()
            // First select the delegate's own selector, then the subdomain
            // relative to this object. DNS selectors are left-to-right here.
            val delegatedSelector = selector + op.selector
            return resolveReference(
                sourceName = currentName,
                targetName = op.name,
                selector = delegatedSelector,
                depth = depth + 1,
                operation = Operation.DELEGATE,
            )
        }

        val withDefaults =
            when (val expanded = expandDefaultMap(raw, currentName, depth)) {
                is NamecoinTraversalResult.Success -> expanded.value
                else -> return expanded
            }

        val merged =
            when (val expanded = expandImports(withDefaults, currentName, depth)) {
                is NamecoinTraversalResult.Success -> expanded.value
                else -> return expanded
            }

        return selectSubdomain(merged, currentName, selector, depth)
    }

    private suspend fun expandDefaultMap(
        obj: JsonObject,
        currentName: String,
        depth: Int,
    ): NamecoinTraversalResult {
        val mapElement = obj["map"] ?: return NamecoinTraversalResult.Success(obj)
        if (mapElement is JsonNull) return NamecoinTraversalResult.Success(obj)
        val map = mapElement as? JsonObject ?: return malformed(currentName, "map item must be an object")
        val defaultElement = map[""] ?: return NamecoinTraversalResult.Success(obj)
        val withoutDefault = removeDefaultMapEntry(obj, map)
        if (defaultElement is JsonNull) return NamecoinTraversalResult.Success(withoutDefault)

        // String map children are the legacy IP shorthand. IP handling is
        // deliberately outside this resolver, so such a default has no
        // Nostr fields to contribute.
        val defaultObject =
            when (defaultElement) {
                is JsonObject -> defaultElement
                is JsonPrimitive -> {
                    if (!defaultElement.isString) return malformed(currentName, "default map child must be an object or string")
                    JsonObject(emptyMap())
                }

                else -> return malformed(currentName, "default map child must be an object or string")
            }

        return when (val expanded = resolveObject(defaultObject, currentName, emptyList(), depth + 1)) {
            is NamecoinTraversalResult.Success ->
                NamecoinTraversalResult.Success(
                    mergeImporterWins(importer = withoutDefault, imported = expanded.value),
                )

            else -> expanded
        }
    }

    private suspend fun expandImports(
        obj: JsonObject,
        currentName: String,
        depth: Int,
    ): NamecoinTraversalResult {
        val importItem = obj["import"] ?: return NamecoinTraversalResult.Success(obj)
        if (importItem is JsonNull) return NamecoinTraversalResult.Success(removeKey(obj, "import"))
        val operations = parseOperations(importItem) ?: return malformed(currentName, "invalid import item")

        var accumulator = JsonObject(emptyMap())
        for (op in operations) {
            when (
                val imported =
                    resolveReference(
                        sourceName = currentName,
                        targetName = op.name,
                        selector = op.selector,
                        depth = depth + 1,
                        operation = Operation.IMPORT,
                    )
            ) {
                is NamecoinTraversalResult.Success -> {
                    // IFA-0001: direct items win, then the FIRST import, then
                    // subsequent imports. This is intentionally not a fold
                    // where the later import overwrites the earlier one.
                    accumulator = mergeImporterWins(importer = accumulator, imported = imported.value)
                }

                else -> return imported
            }
        }

        return NamecoinTraversalResult.Success(
            mergeImporterWins(importer = removeKey(obj, "import"), imported = accumulator),
        )
    }

    private suspend fun selectSubdomain(
        obj: JsonObject,
        currentName: String,
        selector: List<String>,
        depth: Int,
    ): NamecoinTraversalResult {
        if (selector.isEmpty()) return NamecoinTraversalResult.Success(obj)
        val mapElement = obj["map"] ?: return selectorNotFound(currentName, selector)
        if (mapElement is JsonNull) return selectorNotFound(currentName, selector)
        val map = mapElement as? JsonObject ?: return malformed(currentName, "map item must be an object")

        val label = selector.last()
        val exact = map[label]?.takeUnless { it is JsonNull }
        val wildcard = map["*"]?.takeUnless { it is JsonNull }
        val child = exact ?: wildcard ?: return selectorNotFound(currentName, selector)
        val childObject =
            when (child) {
                is JsonObject -> child
                is JsonPrimitive -> {
                    if (!child.isString) return malformed(currentName, "map child must be an object or string")
                    JsonObject(emptyMap())
                }

                else -> return malformed(currentName, "map child must be an object or string")
            }

        return resolveObject(
            raw = childObject,
            currentName = currentName,
            selector = selector.dropLast(1),
            depth = depth + 1,
        )
    }

    private fun referenceFailure(
        operation: Operation,
        sourceName: String,
        targetName: String,
        outcome: NamecoinResolveOutcome,
    ): NamecoinTraversalResult.Failure {
        if (operation == Operation.ROOT || outcome.isTraversalControlFailure()) {
            return NamecoinTraversalResult.Failure(outcome)
        }
        return when (operation) {
            Operation.ROOT -> NamecoinTraversalResult.Failure(outcome)
            Operation.IMPORT ->
                NamecoinTraversalResult.Failure(
                    NamecoinResolveOutcome.ImportFailed(sourceName, targetName, outcome),
                )

            Operation.DELEGATE ->
                NamecoinTraversalResult.Failure(
                    NamecoinResolveOutcome.DelegationFailed(sourceName, targetName, outcome),
                )
        }
    }

    private fun NamecoinResolveOutcome.isTraversalControlFailure(): Boolean =
        this is NamecoinResolveOutcome.CyclicReference ||
            this is NamecoinResolveOutcome.LookupBudgetExhausted ||
            this is NamecoinResolveOutcome.DepthLimitExceeded

    private fun parseOperations(item: JsonElement): List<ImportOp>? {
        if (item is JsonPrimitive && item.isString) {
            return operation(item.content, "")?.let(::listOf)
        }
        val array = item as? JsonArray ?: return null
        if (array.isEmpty()) return emptyList()

        if (array.all { it is JsonPrimitive && it.isString }) {
            val strings = array.map { (it as JsonPrimitive).content }
            // IFA-0001 defines an all-string array as multiple imports. Keep
            // the older [name, selector] shorthand only when the second item
            // cannot itself be a Namecoin key.
            if (strings.size == 2 && looksLikeNamecoinKey(strings[0]) && !looksLikeNamecoinKey(strings[1])) {
                return operation(strings[0], strings[1])?.let(::listOf)
            }
            return strings.map { name -> operation(name, "") ?: return null }
        }

        return array.map { element ->
            val inner = element as? JsonArray ?: return null
            if (inner.isEmpty()) return null
            val name = (inner[0] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val selector =
                if (inner.size >= 2) {
                    (inner[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                } else {
                    ""
                }
            operation(name, selector) ?: return null
        }
    }

    private fun operation(
        name: String,
        selector: String,
    ): ImportOp? {
        if (!NamecoinDomainNames.isValidReference(name)) return null
        val labels = parseSelector(selector, allowWildcard = true) ?: return null
        return ImportOp(name, labels)
    }

    private fun parseSelector(
        selector: String,
        allowWildcard: Boolean,
    ): List<String>? {
        if (selector.isEmpty()) return emptyList()
        if (selector.length > 253 || selector.startsWith('.') || selector.endsWith('.')) return null
        val labels = selector.split('.')
        if (labels.any { label -> !NamecoinDomainNames.isValidMapLabel(label, allowWildcard) }) return null
        return labels.map { it.lowercase() }
    }

    private fun mergeImporterWins(
        importer: JsonObject,
        imported: JsonObject,
    ): JsonObject {
        if (imported.isEmpty()) return importer
        if (importer.isEmpty()) return imported
        val out = LinkedHashMap<String, JsonElement>(importer.size + imported.size)
        for ((key, value) in imported) out[key] = value
        for ((key, value) in importer) out[key] = value
        return JsonObject(out)
    }

    private fun removeDefaultMapEntry(
        obj: JsonObject,
        map: JsonObject,
    ): JsonObject {
        val newMap = LinkedHashMap<String, JsonElement>(map.size)
        for ((key, value) in map) if (key.isNotEmpty()) newMap[key] = value
        return replaceKey(obj, "map", JsonObject(newMap))
    }

    private fun removeKey(
        obj: JsonObject,
        keyToRemove: String,
    ): JsonObject {
        if (!obj.containsKey(keyToRemove)) return obj
        val out = LinkedHashMap<String, JsonElement>(obj.size - 1)
        for ((key, value) in obj) if (key != keyToRemove) out[key] = value
        return JsonObject(out)
    }

    private fun replaceKey(
        obj: JsonObject,
        keyToReplace: String,
        newValue: JsonElement,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(obj.size)
        for ((key, value) in obj) out[key] = if (key == keyToReplace) newValue else value
        return JsonObject(out)
    }

    private fun looksLikeNamecoinKey(value: String): Boolean = value.contains('/') && NamecoinDomainNames.isValidReference(value)

    private fun malformed(
        name: String,
        error: String,
    ): NamecoinTraversalResult.Failure = failure(NamecoinResolveOutcome.MalformedRecord(name, error))

    private fun failure(outcome: NamecoinResolveOutcome): NamecoinTraversalResult.Failure = NamecoinTraversalResult.Failure(outcome)

    private fun selectorNotFound(
        name: String,
        selector: List<String>,
    ): NamecoinTraversalResult.SelectorNotFound = NamecoinTraversalResult.SelectorNotFound(name, selector.joinToString("."))
}

internal object NamecoinDomainNames {
    private val APEX_REGEX = Regex("^(?:xn--)?[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val MAP_LABEL_REGEX = Regex("^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$")
    private val NUMERIC_REGEX = Regex("^[0-9]+$")

    fun isValidApex(label: String): Boolean = label.length in 1..63 && APEX_REGEX.matches(label) && !NUMERIC_REGEX.matches(label)

    fun isValidMapLabel(
        label: String,
        allowWildcard: Boolean = false,
    ): Boolean = (allowWildcard && label == "*") || (label.length in 1..63 && MAP_LABEL_REGEX.matches(label))

    fun isValidReference(name: String): Boolean {
        if (name.length !in 3..255 || !name.contains('/') || name.endsWith(".bit", ignoreCase = true)) return false
        if (!name.all { it.code in 33..126 }) return false
        if (name.startsWith("d/")) return isValidApex(name.removePrefix("d/"))
        return true
    }
}
