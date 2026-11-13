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
package com.vitorpamplona.quartz.routstr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip50Search.IndexableFields
import com.vitorpamplona.quartz.nip50Search.SearchFieldExtractor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.routstr.node.RoutstrDiscovery
import com.vitorpamplona.quartz.routstr.node.RoutstrEndpoint
import com.vitorpamplona.quartz.routstr.node.RoutstrEndpoint.Type
import com.vitorpamplona.quartz.routstr.node.RoutstrNodeContent
import com.vitorpamplona.quartz.routstr.node.RoutstrNodeEvent
import com.vitorpamplona.quartz.utils.EventFactory
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Routstr node announcements as live nodes publish them. Every fixture is a real kind-38421 event
 * from relay.routstr.com, relay.damus.io or nos.lol, fetched 2026-11-13.
 */
class RoutstrNodeEventTest {
    /** routstr-core's own shape: an https host, a Tor onion, three mints and a version. */
    private val thefux =
        """{"content":"{\"name\":\"thefux\",\"about\":\"Thefux Provider\"}","created_at":1794477964,"id":"59300ca3fbe356a919fbbac4f5c1ed20f51043799026f7a200fe0d89599d9157","kind":38421,"pubkey":"3d8333c5a3467c62b055c72f7f197df161faad20a5219ae694a92457c0e48a7c","sig":"cb5cefcff3355a220e51c9572a33e2e04c3f60175873a9bd3ccb774d6c3651daeda57eac84eaceceaf0a2af239fe2a4068e671fbaafcf5128a841bfcf772d4b6","tags":[["d","3d8333c5a346"],["u","https://routstr.otrta.me"],["u","http://zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion"],["mint","https://mint.minibits.cash/Bitcoin"],["mint","https://mint.cubabitcoin.org"],["mint","https://mint.mountainlake.io"],["version","0.5.1+gd980acc"]]}"""

    /** Reachable only over the FIPS mesh; its content carries extra fields beside name and about. */
    private val archipelago =
        """{"content":"{\"about\":\"AI inference sold over the FIPS mesh; reachable only from FIPS nodes. Pay with Cashu ecash.\",\"models\":[{\"context_length\":8192,\"id\":\"archy-llama\",\"max_completion_tokens\":1024,\"name\":\"Qwen2.5 1.5B Instruct\",\"sats_up_front\":13,\"usd_per_million_tokens\":{\"completion\":2.0,\"prompt\":1.0}},{\"context_length\":32768,\"id\":\"qwen3.8-27b\",\"max_completion_tokens\":8192,\"name\":\"Qwen3.8 27B\",\"sats_up_front\":53,\"usd_per_million_tokens\":{\"completion\":2.0,\"prompt\":1.0}}],\"name\":\"Archipelago npub15eparr6gjry\",\"transport\":\"fips\"}","created_at":1793305307,"id":"d075d0dae62245f429df95d7d61d911aa89950aceddb4dc2c93b432eff430b47","kind":38421,"pubkey":"a643d18f4890c8c08c5053cd3633c7dee550c4f589570e47948d363ae6e896ad","sig":"b0764c9b8859be23ba032eceb761d9d2f1a9d3c66ec332d8cb761b4d420518fb20d252a3321e99d84b0795deabc752e1618fca41fbc7c45ec05225145991e1a4","tags":[["d","archipelago-fips"],["u","http://npub15eparr6gjryvprzs20xnvv78mmj4p38439tsu3u535mr4ehgj6ksy0z6fx.fips:5679/routstr"],["t","fips"],["t","archipelago"],["mint","https://mint.minibits.cash/Bitcoin"],["version","0.5.0+g8ce9ef6"]]}"""

    /** An https endpoint on a bare IPv4 address. */
    private val ashen =
        """{"content":"{\"name\":\"Ashen Routstr Node\",\"about\":\"Ashen’s Routstr node — access AI models with Bitcoin payments. Simple, open, and ready for your agents.\"}","created_at":1794203296,"id":"bfa17a7310e95fed7c5f0d7b207f1e45b327846a0068df932929ecba45655b5b","kind":38421,"pubkey":"636647f243dbe58a2a4b6f0afd032e006845983737da2d647dbc0fff87635f69","sig":"c4bc76e2dc11a81da73ebee3cc4bec4cfb8f143ff0557462b248f7f172991d3ba93e01f02be3d849b73c55a3e89a6e5296ee62a4ec391264ee5aa2962c7b8723","tags":[["d","636647f243db"],["u","https://140.245.192.194"],["mint","https://mint.cubabitcoin.org"],["mint","https://mint.minibits.cash/Bitcoin"],["version","0.5.0+g2c69ba4"]]}"""

    /** The one node that lists its models in a tag; no mints (Lightning only). */
    private val yalr =
        """{"content":"{\"name\":\"YALR\",\"about\":\"OpenAI-compatible LLM router paid over Lightning\"}","created_at":1793236295,"id":"2f61486868c7a538142134dd9aa4889133dcdea7248cfcc76e114ef04039d96e","kind":38421,"pubkey":"2d9a87f4b5ef98e5fba295248044f0743d134ffef23e15f90f7bafae7713a635","sig":"b730efcf57248229719b8e68c1843bdd5985ab2e2c20bda7abc126308b59e330b4dddf4f8077234d0b96e78988ff3b2b19c4a36534d861890bac892b50bc3d9a","tags":[["d","yalr.v0l.io"],["u","https://yalr.v0l.io"],["models","claude-opus-5.5","claude-sonnet-5.5","qwen3.8-27b-uncensored"],["version","0.1.0"]]}"""

    private fun parse(json: String) = assertIs<RoutstrNodeEvent>(Event.fromJson(json))

    @Test
    fun readsRoutstrCoreShape() {
        val node = parse(thefux)
        assertEquals("3d8333c5a346", node.dTag())
        assertEquals("thefux", node.name())
        assertEquals("Thefux Provider", node.about())
        assertEquals("0.5.1+gd980acc", node.version())
        assertEquals(listOf("https://mint.minibits.cash/Bitcoin", "https://mint.cubabitcoin.org", "https://mint.mountainlake.io"), node.mints())
        assertEquals(emptyList(), node.models())
        assertEquals(emptyList(), node.topics())

        val https = RoutstrEndpoint("https://routstr.otrta.me", Type.HTTPS)
        val onion = RoutstrEndpoint("http://zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion", Type.ONION)
        assertEquals(listOf(https, onion), node.endpoints())
        assertEquals(listOf(https), node.httpsEndpoints())
        assertEquals(listOf(onion), node.onionEndpoints())
        assertEquals(listOf(https), node.usableEndpoints(torEnabled = false))
        assertEquals(listOf(https, onion), node.usableEndpoints(torEnabled = true))
    }

    @Test
    fun aFipsOnlyNodeHasNothingToCall() {
        val node = parse(archipelago)
        assertEquals("Archipelago npub15eparr6gjry", node.name())
        assertTrue(node.about()!!.startsWith("AI inference sold over the FIPS mesh"))
        assertEquals(listOf("fips", "archipelago"), node.topics())
        assertEquals(listOf(Type.OTHER), node.endpoints().map { it.type })
        assertEquals(emptyList(), node.usableEndpoints(torEnabled = true))
        // The content's own `models` array is not the tag: only the tag is read.
        assertEquals(emptyList(), node.models())
    }

    @Test
    fun anIpLiteralHttpsHostIsNotUsable() {
        val node = parse(ashen)
        assertEquals("Ashen’s Routstr node — access AI models with Bitcoin payments. Simple, open, and ready for your agents.", node.about())
        assertEquals(listOf(RoutstrEndpoint("https://140.245.192.194", Type.OTHER)), node.endpoints())
        assertEquals(emptyList(), node.usableEndpoints(torEnabled = true))
    }

    @Test
    fun readsTheModelsTag() {
        val node = parse(yalr)
        assertEquals(listOf("claude-opus-5.5", "claude-sonnet-5.5", "qwen3.8-27b-uncensored"), node.models())
        assertEquals(emptyList(), node.mints())
        assertEquals("0.1.0", node.version())
        assertEquals(listOf("https://yalr.v0l.io"), node.usableEndpoints(torEnabled = false).map { it.url })
    }

    @Test
    fun classifiesEveryAddressShapeSeenLive() {
        val cases =
            listOf(
                "https://routstr.otrta.me" to Type.HTTPS,
                "https://api.routstr.com/" to Type.HTTPS,
                "https://ai.aliyun.dpdns.org/" to Type.HTTPS,
                "https://bartly.eth64.de:17881" to Type.HTTPS,
                "https://fiscal-cancel-immune-finishing.trycloudflare.com" to Type.HTTPS,
                "HTTPS://Node.Example.COM/v1" to Type.HTTPS,
                "https://xn--80ak6aa92e.xn--p1ai" to Type.HTTPS,
                "http://zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion" to Type.ONION,
                "https://zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion/v1" to Type.ONION,
                "http://api.zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion:8080" to Type.ONION,
                // Not a v3 onion.
                "http://test.onion" to Type.OTHER,
                "http://expyuzz4wqqyqhjn.onion" to Type.OTHER,
                // Plain http to anything but an onion.
                "http://localchimera.com:8000" to Type.OTHER,
                "http://warus.hldns.ru:8000" to Type.OTHER,
                "http://paygate:8000" to Type.OTHER,
                "http://127.0.0.1:8010" to Type.OTHER,
                "http://99.68.36.178:8000" to Type.OTHER,
                "http://100.90.84.147:8011" to Type.OTHER,
                // FIPS mesh names, over either scheme.
                "http://npub1pxd3pll90t2n8hd6ntcpx27wzr9yws4hspzfxfhg5329qkjnx2aqfmya5a.fips:28080" to Type.OTHER,
                "https://npub1pxd3pll90t2n8hd6ntcpx27wzr9yws4hspzfxfhg5329qkjnx2aqfmya5a.fips" to Type.OTHER,
                // IP literals and non-public names over https.
                "https://140.245.192.194" to Type.OTHER,
                "https://[2001:db8::1]/v1" to Type.OTHER,
                "https://localhost:8000" to Type.OTHER,
                "https://paygate" to Type.OTHER,
                "https://MacBook-Pro-2.local" to Type.OTHER,
                "https://router.internal" to Type.OTHER,
                // Malformed.
                "https://user:pass@node.example.com" to Type.OTHER,
                "https://node.example.com:0" to Type.OTHER,
                "https://node.example.com:99999" to Type.OTHER,
                "https://node.example.com:abc" to Type.OTHER,
                "https://-bad-.example.com" to Type.OTHER,
                "https://" to Type.OTHER,
                "wss://relay.routstr.com" to Type.OTHER,
                "ftp://node.example.com" to Type.OTHER,
                "node.example.com" to Type.OTHER,
                "" to Type.OTHER,
            )
        val wrong = cases.filter { (url, type) -> RoutstrEndpoint.typeOf(url) != type }.map { (url, type) -> "$url: expected $type, got ${RoutstrEndpoint.typeOf(url)}" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun readsContentLeniently() {
        val nulls = RoutstrNodeContent(null, null)
        listOf("", "   ", "not json", "{", "{bad json}", "[]", "\"name\"", "{}", """{"name": 5, "about": ["x"]}""", """{"name": "  ", "about": null}""")
            .forEach { assertEquals(nulls, RoutstrNodeContent.parse(it), it) }
        assertEquals(RoutstrNodeContent("Node", null), RoutstrNodeContent.parse("""  {"name":" Node "}  """))

        val event = EventFactory.create<Event>("11".repeat(32), "22".repeat(32), 1L, RoutstrNodeEvent.KIND, arrayOf(arrayOf("d", "x")), "{oops", "33".repeat(64))
        assertIs<RoutstrNodeEvent>(event)
        assertNull(event.name())
        assertNull(event.about())
        assertEquals("", event.indexableContent())
        assertEquals(emptyList(), event.endpoints())
    }

    @Test
    fun freshnessIsAWeekByDefault() {
        val node = parse(thefux)
        assertTrue(node.isFresh(now = node.createdAt))
        assertTrue(node.isFresh(now = node.createdAt + TimeUtils.ONE_WEEK))
        assertTrue(!node.isFresh(now = node.createdAt + TimeUtils.ONE_WEEK + 1))
        assertTrue(!node.isFresh(now = node.createdAt + TimeUtils.ONE_DAY * 2, maxAge = TimeUtils.ONE_DAY.toLong()))
    }

    @Test
    fun discoveryAsksTheDocsRelaysForTheKind() {
        assertEquals(listOf("wss://relay.routstr.com/", "wss://relay.damus.io/", "wss://nos.lol/"), RoutstrDiscovery.DEFAULT_RELAYS.map { it.url })
        val all = RoutstrDiscovery.filter()
        assertEquals(listOf(RoutstrNodeEvent.KIND), all.kinds)
        assertNull(all.since)
        val recent = RoutstrDiscovery.filter(since = 1_794_000_000L)
        assertEquals(1_794_000_000L, recent.since)
        assertTrue(recent.match(parse(thefux)))
        assertTrue(!recent.match(parse(yalr)))
    }

    @Test
    fun searchIndexesNameAndAbout() {
        val node = parse(thefux)
        assertIs<SearchableEvent>(node)
        assertEquals("thefux\nThefux Provider", node.indexableContent())
        val fields = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(node))
        assertEquals(listOf("thefux"), fields.primary)
        assertEquals(listOf("Thefux Provider"), fields.secondary)
    }

    @Test
    fun buildParsesBackAsItsClass() =
        runTest {
            val signer = NostrSignerInternal(KeyPair())
            val template =
                RoutstrNodeEvent.build(
                    endpoints = listOf("https://node.example.com", "http://zdnt2juw6htljsu25ftnbvdicgvr4s3ts4ldlx2myjcuyz4woaf4l7id.onion", "http://192.168.1.2:8000"),
                    mints = listOf("https://mint.minibits.cash/Bitcoin"),
                    version = "0.5.1",
                    name = "My \"Node\"",
                    about = "Pay in sats",
                    models = listOf("gpt-x", "claude-y"),
                    topics = listOf("directory"),
                    dTag = signer.pubKey.take(12),
                    createdAt = 1_794_477_964L,
                )
            val signed = signer.sign(template)
            val node = parse(signed.toJson())

            assertEquals(signer.pubKey.take(12), node.dTag())
            assertEquals("""{"name":"My \"Node\"","about":"Pay in sats"}""", node.content)
            assertEquals("My \"Node\"", node.name())
            assertEquals("Pay in sats", node.about())
            assertEquals(listOf(Type.HTTPS, Type.ONION, Type.OTHER), node.endpoints().map { it.type })
            assertEquals(listOf("https://node.example.com"), node.usableEndpoints(torEnabled = false).map { it.url })
            assertEquals(listOf("https://mint.minibits.cash/Bitcoin"), node.mints())
            assertEquals("0.5.1", node.version())
            assertEquals(listOf("gpt-x", "claude-y"), node.models())
            assertEquals(listOf("directory"), node.topics())
            assertEquals(1_794_477_964L, node.createdAt)

            val bare = parse(signer.sign(RoutstrNodeEvent.build(endpoints = listOf("https://node.example.com"))).toJson())
            assertEquals("{}", bare.content)
            assertNull(bare.name())
            assertNull(bare.version())
            assertEquals(emptyList(), bare.models())
        }
}
