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
package com.vitorpamplona.quartz.llamagarden

import com.vitorpamplona.quartz.drss.eventList.DrssEventListEvent
import com.vitorpamplona.quartz.drss.eventList.UnrecognizedKind30102Event
import com.vitorpamplona.quartz.drss.feedList.DrssFeedListEvent
import com.vitorpamplona.quartz.drss.feedList.UnrecognizedKind30100Event
import com.vitorpamplona.quartz.kinds.KindNames
import com.vitorpamplona.quartz.llamagarden.curation.LlamaGardenCuration
import com.vitorpamplona.quartz.llamagarden.modelRequest.ModelRequestEvent
import com.vitorpamplona.quartz.llamagarden.modelRequest.UnrecognizedKind30103Event
import com.vitorpamplona.quartz.llamagarden.modelRequest.tags.RequestTypeTag
import com.vitorpamplona.quartz.llamagarden.release.ClientReleaseEvent
import com.vitorpamplona.quartz.llamagarden.seederRequest.SeederRequestEvent
import com.vitorpamplona.quartz.llamagarden.torrent.ModelTorrentEvent
import com.vitorpamplona.quartz.llamagarden.torrent.UnrecognizedKind30099Event
import com.vitorpamplona.quartz.llamagarden.torrent.quantType
import com.vitorpamplona.quartz.llamagarden.torrent.tags.FileClassTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.HfMatchTag
import com.vitorpamplona.quartz.llamagarden.torrent.tags.ModelKindTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip13Pow.pow
import com.vitorpamplona.quartz.nip32Labeling.LabelEvent
import com.vitorpamplona.quartz.nip50Search.IndexableFields
import com.vitorpamplona.quartz.nip50Search.SearchFieldExtractor
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * llama.garden's kinds (https://github.com/etemiz/llama.garden) and the other apps' events on the
 * same numbers, exactly as nos.lol and nostr.mom held them on 2026-11-18, signatures included. The
 * seeder request asks for seeders of [kimiListingJson]; the approval vouches for [fluxListingJson],
 * whose author is not one of the viewer's whitelisted curators.
 */
class LlamaGardenEventsTest {
    private val kimiListingJson =
        """{"content": "", "created_at": 1794702069, "id": "589b21e7a1f612571ffeee5249f7e61964e1141d53dde1d44af08588cfa2cc47", "kind": 30099, "pubkey": "77649e3424344dc6a068fc8f29265336d84b2b8f9449698609843427d468c341", "sig": "0e07fad5412397430384738283f2f5eefc80979511f19bf93b4fba328a3b99be4073d905491ca992fdab40d6256f8f8c76e57d384bdb3a86f79bdaa6c710c9e7", "tags": [["d", "9d990f92c6dcb8743cd9df1487327cbb787f5c8f"], ["magnet", "magnet:?xt=urn:btih:9d990f92c6dcb8743cd9df1487327cbb787f5c8f&dn=Kimi-K3.1-Pro%20%C2%B7%20modelopt_mixed%20%C2%B7%20nvidia&xl=1605247450766&tr=udp%3A//tracker.opentrackr.org%3A1337/announce&tr=udp%3A//tracker.openbittorrent.com%3A6969/announce&tr=udp%3A//open.stealth.si%3A80/announce&tr=udp%3A//explodie.org%3A6969/announce&tr=udp%3A//tracker.torrent.eu.org%3A451/announce&tr=udp%3A//exodus.desync.com%3A6969/announce&tr=https%3A//tracker.tamersunion.org%3A443/announce&tr=https%3A//tracker1.520.jp%3A443/announce&tr=https%3A//tracker.gbitt.info%3A443/announce&tr=https%3A//tracker2.520.jp%3A443/announce&ws=https%3A//huggingface.co/nvidia/&ws=http%3A//z1.api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/&ws=http%3A//z2.api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/&ws=http%3A//1%40api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/&ws=http%3A//2%40api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/&ws=http%3A//3%40api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/&ws=http%3A//4%40api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["name", "Kimi-K3.1-Pro---modelopt_mixed---nvidia-3af0462-9d990f9"], ["size", "1605247450766"], ["pieces", "95719"], ["piece_length", "16777216"], ["x", "438435436b30bde1660196152fc51e1c2fb7f84d934564d3e17102208ad12925"], ["torrent_size", "1934099"], ["torrent_created", "2026-11-15T00:21:08Z"], ["m", "application/x-bittorrent"], ["url", "https://nostr.download/438435436b30bde1660196152fc51e1c2fb7f84d934564d3e17102208ad12925.torrent"], ["webseed", "https://huggingface.co/nvidia/"], ["webseed", "http://z1.api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["webseed", "http://z2.api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["webseed", "http://1@api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["webseed", "http://2@api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["webseed", "http://3@api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["webseed", "http://4@api.llama.garden/nvidia/Kimi-K3.1-Pro-NVFP4/9d990f92c6dcb8743cd9df1487327cbb787f5c8f/"], ["tracker", "udp://tracker.opentrackr.org:1337/announce"], ["tracker", "udp://tracker.openbittorrent.com:6969/announce"], ["tracker", "udp://open.stealth.si:80/announce"], ["tracker", "udp://explodie.org:6969/announce"], ["tracker", "udp://tracker.torrent.eu.org:451/announce"], ["tracker", "udp://exodus.desync.com:6969/announce"], ["tracker", "https://tracker.tamersunion.org:443/announce"], ["tracker", "https://tracker1.520.jp:443/announce"], ["tracker", "https://tracker.gbitt.info:443/announce"], ["tracker", "https://tracker2.520.jp:443/announce"], ["source", "huggingface.co/nvidia/Kimi-K3.1-Pro-NVFP4"], ["display_name", "Kimi-K3.1-Pro · modelopt_mixed · nvidia"], ["file_class", "quant"], ["model_kind", "base"], ["quant_type", "modelopt_mixed"], ["quant_dev", "nvidia"], ["lab", "nvidia"], ["model_name", "Kimi-K3.1-Pro"], ["repo_id", "nvidia/Kimi-K3.1-Pro-NVFP4"], ["torrent_name", "Kimi-K3.1-Pro-NVFP4"], ["base_model", "moonshotai/Kimi-K3.1-Pro"], ["created_at", "2026-11-11 00:57:46+00:00"], ["version", "stream"], ["commit_sha", "3af0462972c9cc07e5ba6e0867b44f961ce95e9b"]]}"""

    private val fluxListingJson =
        """{"content": "", "created_at": 1793648395, "id": "96d436eaee9392c802e7a535fd9a08e532708efc9a6d9204995e48bedc138ef9", "kind": 30099, "pubkey": "41ee084919f21415f8c61c2dddbaf0eef8a32aea1fa15ea226dd2f9bc420dfc5", "sig": "500d67d48054945c1ef5d325f827ec4013a3d9c5c3059c0eef426ee7f8dd9feb75eb196e8f59501a36506d46ac508ab2aa65af50b31459c9b5f2d9a6f8012644", "tags": [["d", "3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e"], ["magnet", "magnet:?xt=urn:btih:3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e&dn=FLUX.1-schnell%20%C2%B7%20unsloth&xl=148052383157&tr=udp%3A//tracker.opentrackr.org%3A1337/announce&tr=udp%3A//tracker.openbittorrent.com%3A6969/announce&tr=udp%3A//open.stealth.si%3A80/announce&tr=udp%3A//explodie.org%3A6969/announce&tr=udp%3A//tracker.torrent.eu.org%3A451/announce&tr=udp%3A//exodus.desync.com%3A6969/announce&tr=https%3A//tracker.tamersunion.org%3A443/announce&tr=https%3A//tracker1.520.jp%3A443/announce&tr=https%3A//tracker.gbitt.info%3A443/announce&tr=https%3A//tracker2.520.jp%3A443/announce&ws=https%3A//huggingface.co/unsloth/&ws=http%3A//z1.api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/&ws=http%3A//z2.api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/&ws=http%3A//1%40api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/&ws=http%3A//2%40api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/&ws=http%3A//3%40api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/&ws=http%3A//4%40api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["name", "FLUX.1-schnell---unsloth-8c90c32-3aa151c.many"], ["size", "148052383157"], ["pieces", "8834"], ["piece_length", "16777216"], ["x", "0165aed862ab3cc377d944428423a7a5527efcef827a003b29cb95736d43e6a4"], ["torrent_size", "180603"], ["torrent_created", "2026-11-02T18:33:37Z"], ["m", "application/x-bittorrent"], ["url", "https://nostr.download/0165aed862ab3cc377d944428423a7a5527efcef827a003b29cb95736d43e6a4.torrent"], ["url", "https://blossom.primal.net/0165aed862ab3cc377d944428423a7a5527efcef827a003b29cb95736d43e6a4"], ["webseed", "https://huggingface.co/unsloth/"], ["webseed", "http://z1.api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["webseed", "http://z2.api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["webseed", "http://1@api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["webseed", "http://2@api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["webseed", "http://3@api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["webseed", "http://4@api.llama.garden/unsloth/FLUX.1-schnell-GGUF/3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e/"], ["tracker", "udp://tracker.opentrackr.org:1337/announce"], ["tracker", "udp://tracker.openbittorrent.com:6969/announce"], ["tracker", "udp://open.stealth.si:80/announce"], ["tracker", "udp://explodie.org:6969/announce"], ["tracker", "udp://tracker.torrent.eu.org:451/announce"], ["tracker", "udp://exodus.desync.com:6969/announce"], ["tracker", "https://tracker.tamersunion.org:443/announce"], ["tracker", "https://tracker1.520.jp:443/announce"], ["tracker", "https://tracker.gbitt.info:443/announce"], ["tracker", "https://tracker2.520.jp:443/announce"], ["source", "huggingface.co/unsloth/FLUX.1-schnell-GGUF"], ["display_name", "FLUX.1-schnell · unsloth"], ["file_class", "quant"], ["model_kind", "base"], ["quant_type", "gguf"], ["quant_dev", "unsloth"], ["quant_detail", "many"], ["lab", "unsloth"], ["model_name", "FLUX.1-schnell"], ["repo_id", "unsloth/FLUX.1-schnell-GGUF"], ["torrent_name", "FLUX.1-schnell-GGUF"], ["base_model", "black-forest-labs/FLUX.1-schnell"], ["created_at", "2025-06-30 10:50:31+00:00"], ["version", "stream"], ["commit_sha", "8c90c328734d443a0d7bbc2978611f4a2f90d37f"]]}"""

    private val qwenQuantListingJson =
        """{"content": "", "created_at": 1786472641, "id": "6ca5d38e1c3340f41824cb57160642dc5c59aa5d14c69eb8f98b700076ee78f8", "kind": 30099, "pubkey": "41ee084919f21415f8c61c2dddbaf0eef8a32aea1fa15ea226dd2f9bc420dfc5", "sig": "0b2095ab4270d22f6b6e070f96d71ddc169ff3a175042699e8f33e535219fd05edd732c9c4de7f1d74375e8d49d34f401e6fd9b2d73e7ee3e81db1d44a9e929c", "tags": [["d", "17616834d44b4e2d1b35494d0b80ee61da3cea79"], ["magnet", "magnet:?xt=urn:btih:17616834d44b4e2d1b35494d0b80ee61da3cea79&dn=Qwen_Qwen3.6-27B%20%C2%B7%20Q6_K%20%C2%B7%20bartowski&xl=23675466240&tr=udp%3A//tracker.opentrackr.org%3A1337/announce&tr=udp%3A//tracker.openbittorrent.com%3A6969/announce&tr=udp%3A//open.stealth.si%3A80/announce&tr=udp%3A//explodie.org%3A6969/announce&tr=udp%3A//tracker.torrent.eu.org%3A451/announce&tr=udp%3A//exodus.desync.com%3A6969/announce&tr=https%3A//tracker.tamersunion.org%3A443/announce&tr=https%3A//tracker1.520.jp%3A443/announce&tr=https%3A//tracker.gbitt.info%3A443/announce&tr=https%3A//tracker2.520.jp%3A443/announce&ws=https%3A//huggingface.co/bartowski/&ws=http%3A//z1.api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/&ws=http%3A//z2.api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/&ws=http%3A//1%40api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/&ws=http%3A//2%40api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/&ws=http%3A//3%40api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/&ws=http%3A//4%40api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["name", "Qwen_Qwen3.6-27B---Q6_K---bartowski-4612927-1761683"], ["size", "23675466240"], ["pieces", "1412"], ["piece_length", "16777216"], ["x", "757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9"], ["torrent_size", "29775"], ["torrent_created", "2026-08-11T18:18:07Z"], ["m", "application/x-bittorrent"], ["url", "https://nostr.download/757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9.torrent"], ["url", "https://blossom.primal.net/757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9"], ["url", "https://cdn.hzrd149.com/757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9.torrent"], ["webseed", "https://huggingface.co/bartowski/"], ["webseed", "http://z1.api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["webseed", "http://z2.api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["webseed", "http://1@api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["webseed", "http://2@api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["webseed", "http://3@api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["webseed", "http://4@api.llama.garden/bartowski/Qwen_Qwen3.6-27B-GGUF/17616834d44b4e2d1b35494d0b80ee61da3cea79/"], ["tracker", "udp://tracker.opentrackr.org:1337/announce"], ["tracker", "udp://tracker.openbittorrent.com:6969/announce"], ["tracker", "udp://open.stealth.si:80/announce"], ["tracker", "udp://explodie.org:6969/announce"], ["tracker", "udp://tracker.torrent.eu.org:451/announce"], ["tracker", "udp://exodus.desync.com:6969/announce"], ["tracker", "https://tracker.tamersunion.org:443/announce"], ["tracker", "https://tracker1.520.jp:443/announce"], ["tracker", "https://tracker.gbitt.info:443/announce"], ["tracker", "https://tracker2.520.jp:443/announce"], ["source", "huggingface.co/bartowski/Qwen_Qwen3.6-27B-GGUF"], ["display_name", "Qwen_Qwen3.6-27B · Q6_K · bartowski"], ["file_class", "quant"], ["model_kind", "base"], ["quant_type", "gguf"], ["quant_dev", "bartowski"], ["quant_detail", "Q6_K"], ["quant_bpw", "6.6"], ["lab", "bartowski"], ["model_name", "Qwen_Qwen3.6-27B"], ["repo_id", "bartowski/Qwen_Qwen3.6-27B-GGUF"], ["torrent_name", "Qwen_Qwen3.6-27B-GGUF"], ["base_model", "Qwen/Qwen3.6-27B"], ["created_at", "2026-04-22 14:24:37+00:00"], ["version", "stream"], ["commit_sha", "4612927928b49982f8319dc3e2e6f62b9b73b192"]]}"""

    private val seederRequestJson =
        """{"content": "", "created_at": 1794966788, "id": "00000df5fb288a30badbc9ab59db4f740d33054deb55dbfcc80620026847d6eb", "kind": 30102, "pubkey": "48b656633a3799a98c9d0be3478c9eb986c8e13ba11cd85dad98bd1f6d731545", "sig": "e0bf2990bc880348bb95c5b4489556254ed6aba33321834dfd7c9935e6ed27210f8d023045cae6f76cdeecccb180fa02163a211233e4a2fe5402470fa2f39ca1", "tags": [["d", "48b656633a3799a98c9d0be3478c9eb986c8e13ba11cd85dad98bd1f6d731545:9d990f92c6dcb8743cd9df1487327cbb787f5c8f"], ["e", "589b21e7a1f612571ffeee5249f7e61964e1141d53dde1d44af08588cfa2cc47"], ["i", "9d990f92c6dcb8743cd9df1487327cbb787f5c8f"], ["name", "Kimi-K3.1-Pro · modelopt_mixed · nvidia"], ["client", "waifu-magnet-26"], ["nonce", "2045570720"]]}"""

    private val approvalJson =
        """{"content": "", "created_at": 1793743816, "id": "fe5b0c8caa30de08e26189a69d7faf705722a169054bb4d1048247ef292f0707", "kind": 1985, "pubkey": "77649e3424344dc6a068fc8f29265336d84b2b8f9449698609843427d468c341", "sig": "2244f4faf096f5190eb4b4c4854601945a6fe3ca398aeba88ed0b4537772fea629ac87f7eed6a0c62aa6588c19b687c9da89fadf4439800f7f49e6f6d8b73e23", "tags": [["L", "llama.garden"], ["l", "approve", "llama.garden"], ["e", "96d436eaee9392c802e7a535fd9a08e532708efc9a6d9204995e48bedc138ef9", "wss://nostr.mom/"]]}"""

    private val requestWithVramJson =
        """{"content": "", "created_at": 1795009298, "id": "000007bdd448b3c6e7ea68fae37838200d160ba37da8249b3403bf5cdf7ff79e", "kind": 30103, "pubkey": "62cdf212934faaac7efd425621cafcdb963aaa82190e132444a25ace1c48c6aa", "sig": "1b123d6ba7f9dee630073983d63535ddbfd76d256481b2ad8c28b28d3cc9bcfb5b23950a3b129cca26ea43f0ad1ff94daee217e5d4ed3228e84f7e9397884b35", "tags": [["d", "62cdf212934faaac7efd425621cafcdb963aaa82190e132444a25ace1c48c6aa"], ["name", "DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF"], ["url", "https://huggingface.co/DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF"], ["type", "all"], ["vram_gb", "24"], ["client", "waifu-magnet-26"], ["nonce", "1358541319"]]}"""

    private val requestNameOnlyJson =
        """{"content": "", "created_at": 1794770386, "id": "0000037ebe6a1213421df76b7b662d10b75d69a58a77310794c9b9cb706cd899", "kind": 30103, "pubkey": "0c6d4839b4aefad79dcba97402b217aff23a676f00eb5dec46036b6f19cae85d", "sig": "4bcd53925862ed68f7ddea1ed79f23737ed371f1c395b1afd04bcd62207e8aa2802c1838567665465560373ad591c702d56f178fba958ed23c56e0a7115ad295", "tags": [["d", "0c6d4839b4aefad79dcba97402b217aff23a676f00eb5dec46036b6f19cae85d"], ["name", "qwen3.8-flashnext"], ["type", "all"], ["client", "waifu-magnet-26"], ["nonce", "3036289551"]]}"""

    private val usageCounterJson =
        """{"content": "{\"kind\":\"tree_usage_v1\",\"ownerPub\":\"ef350eb5b443c03fe9dbf910c1c5043dd845363ad3d0202076093da752c3bcfd\",\"universeId\":\"7afef4a1-c016-4cfa-9c76-eff105d1d77f\",\"dayKey\":\"2026-11-07\",\"powBits\":16,\"powNonce\":\"fd83:e21fd11b\",\"at\":\"2026-11-07T05:11:34.819Z\"}", "created_at": 1794028294, "id": "4fc4a7463364823550a64f195f8bbe3c011a0b2df330264eef0f43c949140cac", "kind": 30103, "pubkey": "3be46fe8c79dc36fa273ceff291ae96f67b551d08b88582fff339d2ba4309bce", "sig": "72c180a40ca61bdc592547ab9c70970d9db8436e9851784c0d82eb094da4307ee98bce6e8bdac7ef8833426fee04c6c1fb876e1036c89275c2a6feee6625c17a", "tags": [["arb", "root", "ef350eb5b443c03fe9dbf910c1c5043dd845363ad3d0202076093da752c3bcfd", "7afef4a1-c016-4cfa-9c76-eff105d1d77f"], ["m", "a:usage"], ["d", "usage:ef350eb5b443c03fe9dbf910c1c5043dd845363ad3d0202076093da752c3bcfd:7afef4a1-c016-4cfa-9c76-eff105d1d77f:2026-11-07:3be46fe8c79dc36fa273ceff291ae96f67b551d08b88582fff339d2ba4309bce"]]}"""

    private val showcaseVoteJson =
        """{"content": "", "created_at": 1790738909, "id": "b098c000cfc7e794420b328adac2112819e00127420966ea7da3493c54faee77", "kind": 30102, "pubkey": "eaecf940732d300080c3b1d6a6afb1d713c99c11d3b4f26a438c228e1713680f", "sig": "99387750e9e9a3c89b36f6d03f44184c933551d86981ba2229e05969e03ec9fae7ebdf741e4e220893c57312ddf0f625254bda6e79b671f5786e0f884ee9564d", "tags": [["d", "rodada-001"], ["a", "30101:e33641eda1b7241eeccdca6941a435f6b3b3f8416c71203470fa9d95b91c3ccd:rodada-001"], ["voto", "30100:e10883607cab10d9c58e29e32cb339aa9bf6174a3ede618e7ee84ec3cc51032d:teste", "kjhhj"], ["alt", "Voto na Rodada 1 da Vitrine Coletiva"]]}"""

    private val vouchCounterJson =
        """{"content": "{\"v\":1,\"count\":1}", "created_at": 1793479851, "id": "41a0cca13b264e7657ef8933589f86e789920d50b3b236c34009c56d2f1ae9ab", "kind": 30102, "pubkey": "43b9c88d476944c15ebb114dbec079514b7a4189f55d5f733c387d2e4e886547", "sig": "94d1fd1a3db4bdab8d3999531bcd73b2330b32025f41bd04e50dfce984e5b66a4068a832fd1135c8154d1214a773cbcc8938e6aa2016173019649c16e1e8c2ef", "tags": [["d", "vouches"], ["p", "9af9064ec21aa8d1f6f75180598fe7f47aa5916d1bdf2c044d2653e582d31385"], ["t", "nostrride"]]}"""

    private inline fun <reified T : Event> parse(json: String): T {
        val event = Event.fromJson(json)
        assertTrue(event.verify(), "id and signature of ${event.id}")
        return assertIs<T>(event)
    }

    private val me = "77649e3424344dc6a068fc8f29265336d84b2b8f9449698609843427d468c341"

    private val signer = NostrSignerInternal(KeyPair())

    /** Signs with a real key, checks the signature, and parses the JSON back as a relay would hand it over. */
    private suspend fun <T : Event> EventTemplate<T>.signAndParse(): Event {
        val signed: T = signer.sign(this)
        assertTrue(signed.verify(), "id and signature")
        return Event.fromJson(signed.toJson())
    }

    @Test
    fun readsAQuantListing() {
        val listing = parse<ModelTorrentEvent>(qwenQuantListingJson)

        assertTrue(listing.isCatalogListing())
        assertEquals("17616834d44b4e2d1b35494d0b80ee61da3cea79", listing.infoHash())
        assertEquals("Qwen_Qwen3.6-27B \u00b7 Q6_K \u00b7 bartowski", listing.title())
        assertEquals("Qwen_Qwen3.6-27B---Q6_K---bartowski-4612927-1761683", listing.name())
        assertEquals(23_675_466_240L, listing.totalSize())
        assertEquals(1412L, listing.pieces())
        assertEquals(16_777_216L, listing.pieceLength())
        assertEquals("757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9", listing.torrentFileSha256())
        assertEquals(29_775L, listing.torrentFileSize())
        assertEquals("2026-08-11T18:18:07Z", listing.torrentCreated())
        assertEquals(ModelTorrentEvent.TORRENT_MIME_TYPE, listing.mimeType())
        assertEquals(3, listing.torrentFileUrls().size)
        assertEquals("https://nostr.download/757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9.torrent", listing.torrentFileUrls().first())
        assertEquals(7, listing.webSeeds().size)
        assertEquals("https://huggingface.co/bartowski/", listing.webSeeds().first())
        assertEquals(10, listing.trackers().size)
        assertEquals("huggingface.co/bartowski/Qwen_Qwen3.6-27B-GGUF", listing.source())
        assertEquals(FileClassTag.QUANT, listing.fileClass())
        assertTrue(listing.isQuant())
        assertEquals(ModelKindTag.BASE, listing.modelKind())
        assertEquals("gguf", listing.quantType())
        assertEquals("bartowski", listing.quantDev())
        assertEquals("Q6_K", listing.quantDetail())
        assertEquals(6.6, listing.quantBpw())
        assertEquals("bartowski", listing.lab())
        assertEquals("Qwen_Qwen3.6-27B", listing.modelName())
        assertEquals("bartowski/Qwen_Qwen3.6-27B-GGUF", listing.repoId())
        assertEquals("https://huggingface.co/bartowski/Qwen_Qwen3.6-27B-GGUF", listing.huggingFaceUrl())
        assertEquals("Qwen_Qwen3.6-27B-GGUF", listing.torrentName())
        assertEquals("Qwen/Qwen3.6-27B", listing.baseModel())
        assertEquals("2026-04-22 14:24:37+00:00", listing.modelCreatedAt())
        assertEquals("stream", listing.modelRevision())
        assertEquals("4612927928b49982f8319dc3e2e6f62b9b73b192", listing.commitSha())
        assertNull(listing.hfMatch())
        assertFalse(listing.isCacheOnly())
        assertTrue(listing.magnetLink()!!.startsWith("magnet:?xt=urn:btih:17616834d44b4e2d1b35494d0b80ee61da3cea79&dn="))
        assertEquals(
            listing.magnetLink() + "&xs=https%3A%2F%2Fnostr.download%2Fx.torrent",
            listing.magnetWithExactSource("https://nostr.download/x.torrent"),
        )
    }

    @Test
    fun readsBaseListingsFromAnyAuthor() {
        val flux = parse<ModelTorrentEvent>(fluxListingJson)
        assertEquals("3aa151c28d56b34d83e7d06eacbe6a8b94bfb90e", flux.infoHash())
        assertEquals("41ee084919f21415f8c61c2dddbaf0eef8a32aea1fa15ea226dd2f9bc420dfc5", flux.pubKey)

        val kimi = parse<ModelTorrentEvent>(kimiListingJson)
        assertEquals("9d990f92c6dcb8743cd9df1487327cbb787f5c8f", kimi.infoHash())
        assertEquals(ModelKindTag.BASE, kimi.effectiveModelKind())
    }

    @Test
    fun readsASeederRequestForTheListingItNames() {
        val listing = parse<ModelTorrentEvent>(kimiListingJson)
        val request = parse<SeederRequestEvent>(seederRequestJson)

        assertEquals(listing.id, request.torrentListingId())
        assertEquals(listOf(listing.id), request.linkedEventIds())
        assertEquals(listing.infoHash(), request.infoHash())
        assertEquals(SeederRequestEvent.dTagFor(request.pubKey, listing.infoHash()!!), request.dTag())
        assertEquals("Kimi-K3.1-Pro \u00b7 modelopt_mixed \u00b7 nvidia", request.name())
        assertEquals("waifu-magnet-26", request.clientName())
        assertTrue(request.pow() >= 20, "mined: ${request.pow()} bits")
    }

    @Test
    fun readsModelRequests() {
        val full = parse<ModelRequestEvent>(requestWithVramJson)
        assertEquals(full.pubKey, full.dTag())
        assertEquals("DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF", full.name())
        assertEquals(listOf("https://huggingface.co/DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF"), full.links())
        assertEquals(RequestTypeTag.ALL, full.requestType())
        assertEquals(24, full.vramGb())
        assertTrue(full.pow() >= 20)

        val bare = parse<ModelRequestEvent>(requestNameOnlyJson)
        assertEquals("qwen3.8-flashnext", bare.name())
        assertEquals(emptyList(), bare.links())
        assertNull(bare.vramGb())
    }

    @Test
    fun readsACuratorApproval() {
        val approval = parse<LabelEvent>(approvalJson)
        assertEquals(LlamaGardenCuration.Verdict.APPROVE, LlamaGardenCuration.verdict(approval))
        assertEquals(listOf(Event.fromJson(fluxListingJson).id), LlamaGardenCuration.approvedListingIds(approval))
        assertEquals(emptyList(), LlamaGardenCuration.rejectedListingIds(approval))
    }

    @Test
    fun leavesTheOtherAppsShapesAlone() {
        assertIs<UnrecognizedKind30103Event>(parse<Event>(usageCounterJson))
        assertIs<UnrecognizedKind30102Event>(parse<Event>(showcaseVoteJson))
        assertIs<UnrecognizedKind30102Event>(parse<Event>(vouchCounterJson))

        val golded =
            EventFactory.create<Event>(
                "11".repeat(32),
                "e8125901c6a7cfdedd37bcfc66068c3eda41febea3ef9869c2cbc2be0986b45b",
                1_795_001_591L,
                30099,
                arrayOf(arrayOf("d", "nostr-golded-release"), arrayOf("v", "1.0.47"), arrayOf("code", "47")),
                "{\"schema\":\"nostr-golded-update-v1\",\"version_code\":47}",
                "00".repeat(64),
            )
        assertIs<UnrecognizedKind30099Event>(golded)

        // drss's own shapes keep winning on the numbers they share.
        val feedList =
            EventFactory.create<Event>("11".repeat(32), me, 1L, 30100, arrayOf(arrayOf("d", "drss"), arrayOf("p", me)), "", "00".repeat(64))
        assertIs<DrssFeedListEvent>(feedList)
        val eventList =
            EventFactory.create<Event>("11".repeat(32), me, 1L, 30102, arrayOf(arrayOf("d", "x"), arrayOf("e", "22".repeat(32))), "", "00".repeat(64))
        assertIs<DrssEventListEvent>(eventList)
        val otherRelease = EventFactory.create<Event>("11".repeat(32), me, 1L, 30100, arrayOf(arrayOf("d", "x"), arrayOf("version", "1")), "", "00".repeat(64))
        assertIs<UnrecognizedKind30100Event>(otherRelease)
    }

    @Test
    fun namesAndProbesTheKinds() {
        assertEquals("Model Torrent", KindNames.nameFor(ModelTorrentEvent.KIND))
        assertEquals("Model Request", KindNames.nameFor(ModelRequestEvent.KIND))
        assertIs<ModelTorrentEvent>(EventFactory.probe(ModelTorrentEvent.KIND))
        assertIs<ModelRequestEvent>(EventFactory.probe(ModelRequestEvent.KIND))
        // A seeder request only copies its listing's title: not indexed.
        assertFalse(Event.fromJson(seederRequestJson) is SearchableEvent)
    }

    @Test
    fun buildsACatalogListingThatParsesBack() =
        runTest {
            val listing =
                ModelTorrentEvent
                    .build(
                        infoHash = "17616834D44B4E2D1B35494D0B80EE61DA3CEA79",
                        name = "Qwen3.6-27B",
                        magnet = "magnet:?xt=urn:btih:17616834d44b4e2d1b35494d0b80ee61da3cea79",
                        totalSize = 23_675_466_240L,
                        torrentFileSha256 = "757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9",
                        torrentFileUrls = listOf("https://nostr.download/a.torrent"),
                        webSeeds = listOf("https://huggingface.co/Qwen/"),
                        trackers = listOf("udp://tracker.opentrackr.org:1337/announce", "udp://tracker.opentrackr.org:1337/announce"),
                        source = "huggingface.co/Qwen/Qwen3.6-27B",
                    ) {
                        quantType("gguf")
                    }.signAndParse()

            assertIs<ModelTorrentEvent>(listing)
            assertEquals(signer.pubKey, listing.pubKey)
            assertEquals("17616834d44b4e2d1b35494d0b80ee61da3cea79", listing.dTag())
            assertEquals("17616834d44b4e2d1b35494d0b80ee61da3cea79", listing.infoHash())
            assertEquals("magnet:?xt=urn:btih:17616834d44b4e2d1b35494d0b80ee61da3cea79", listing.magnetLink())
            assertEquals("Qwen3.6-27B", listing.name())
            assertEquals(23_675_466_240L, listing.totalSize())
            assertEquals("757e7ff736bbfdc17c959964119ecdd86ad95c2a2ab91c548e1509a94924a3e9", listing.torrentFileSha256())
            assertEquals(listOf("https://nostr.download/a.torrent"), listing.torrentFileUrls())
            assertEquals(listOf("https://huggingface.co/Qwen/"), listing.webSeeds())
            assertEquals(listOf("udp://tracker.opentrackr.org:1337/announce"), listing.trackers())
            assertEquals("Qwen/Qwen3.6-27B", listing.repoId())
            assertEquals("gguf", listing.quantType())
            assertEquals(ModelTorrentEvent.TORRENT_MIME_TYPE, listing.mimeType())
        }

    @Test
    fun buildsAUserSubmissionLikeTheViewer() =
        runTest {
            val submission =
                ModelTorrentEvent
                    .buildSubmission(
                        submitter = signer.pubKey,
                        torrentUrl = " https://nostr.download/abc.torrent ",
                        name = "Qwen3-Next-80B-A3B ",
                        approximateSize = 17_179_869_184L,
                        huggingFaceUrl = "https://huggingface.co/Qwen/Qwen3-Next-80B-A3B",
                        fileClass = FileClassTag.BASE,
                        lab = "Qwen",
                        quantType = " ",
                        hfMatch = HfMatchTag.YES,
                    ).signAndParse()

            assertIs<ModelTorrentEvent>(submission)
            // The viewer's d: sha256 hex of "<url>:<name>" over the trimmed form fields.
            assertEquals("user:${signer.pubKey}:1e5c6175eb75c4b4595332d1533d25dbc7789657b506a7b5044681b957fd15d7", submission.dTag())
            assertEquals(ModelTorrentEvent.userSubmissionDTag(signer.pubKey, "https://nostr.download/abc.torrent", "Qwen3-Next-80B-A3B"), submission.dTag())
            assertFalse(submission.isCatalogListing())
            assertNull(submission.infoHash())
            assertNull(submission.magnetLink())
            assertEquals(listOf("https://nostr.download/abc.torrent"), submission.torrentFileUrls())
            assertEquals("Qwen3-Next-80B-A3B", submission.name())
            assertEquals(17_179_869_184L, submission.totalSize())
            assertEquals("huggingface.co", submission.source())
            assertEquals("Qwen/Qwen3-Next-80B-A3B", submission.repoId())
            assertEquals("https://huggingface.co/Qwen/Qwen3-Next-80B-A3B", submission.huggingFaceUrl())
            assertEquals(FileClassTag.BASE, submission.fileClass())
            assertEquals(ModelKindTag.NOT_SAID, submission.modelKind())
            assertEquals(ModelKindTag.NOT_SAID, submission.effectiveModelKind())
            assertEquals("Qwen", submission.lab())
            assertNull(submission.quantType())
            assertEquals(HfMatchTag.YES, submission.hfMatch())
        }

    @Test
    fun buildsRequestsThatParseBack() =
        runTest {
            val listing = Event.fromJson(kimiListingJson) as ModelTorrentEvent
            val relay = RelayUrlNormalizer.normalizeOrNull("wss://nostr.mom/")
            val seeders = SeederRequestEvent.build(EventHintBundle(listing, relay), signer.pubKey).signAndParse()
            assertIs<SeederRequestEvent>(seeders)
            assertEquals(listing.id, seeders.torrentListingId())
            assertEquals(listOf(listing.id), seeders.linkedEventIds())
            assertEquals(listOf(relay), seeders.eventHints().map { it.relay })
            assertEquals("9d990f92c6dcb8743cd9df1487327cbb787f5c8f", seeders.infoHash())
            assertEquals("${signer.pubKey}:9d990f92c6dcb8743cd9df1487327cbb787f5c8f", seeders.dTag())
            assertEquals(listing.title(), seeders.name())

            val request =
                ModelRequestEvent
                    .build(signer.pubKey, "Qwen3-Next-80B-A3B", listOf(" https://huggingface.co/Qwen/Qwen3-Next-80B-A3B ", ""), RequestTypeTag.QUANT, 24)
                    .signAndParse()
            assertIs<ModelRequestEvent>(request)
            assertEquals(signer.pubKey, request.dTag())
            assertEquals("Qwen3-Next-80B-A3B", request.name())
            assertEquals(listOf("https://huggingface.co/Qwen/Qwen3-Next-80B-A3B"), request.links())
            assertEquals(RequestTypeTag.QUANT, request.requestType())
            assertEquals(24, request.vramGb())

            val approval = LlamaGardenCuration.buildApproval(EventHintBundle(listing, relay)).signAndParse()
            assertIs<LabelEvent>(approval)
            assertEquals(LlamaGardenCuration.Verdict.APPROVE, LlamaGardenCuration.verdict(approval))
            assertEquals(listOf(listing.id), LlamaGardenCuration.approvedListingIds(approval))
            val rejection = assertIs<LabelEvent>(LlamaGardenCuration.buildRejection(EventHintBundle(listing, relay)).signAndParse())
            assertEquals(LlamaGardenCuration.Verdict.REJECT, LlamaGardenCuration.verdict(rejection))
            assertEquals(listOf(listing.id), LlamaGardenCuration.rejectedListingIds(rejection))
            assertEquals(emptyList(), LlamaGardenCuration.approvedListingIds(rejection))
        }

    @Test
    fun buildsAClientReleaseThatParsesBack() =
        runTest {
            val release =
                ClientReleaseEvent
                    .build("22", "aa".repeat(32), 180_000L, listOf("https://blossom.primal.net/" + "aa".repeat(32)))
                    .signAndParse()
            assertIs<ClientReleaseEvent>(release)
            assertEquals(ClientReleaseEvent.WAIFU_MAGNET, release.clientId())
            assertEquals("22", release.version())
            assertEquals(22L, release.versionNumber())
            assertEquals("aa".repeat(32), release.sha256())
            assertEquals(180_000L, release.fileSize())
            assertEquals(listOf("https://blossom.primal.net/" + "aa".repeat(32)), release.downloadUrls())
        }

    @Test
    fun searchRanksTitlesAboveTheModelsAndLinks() {
        val listing = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(Event.fromJson(qwenQuantListingJson)))
        assertEquals(listOf("Qwen_Qwen3.6-27B \u00b7 Q6_K \u00b7 bartowski", "Qwen_Qwen3.6-27B---Q6_K---bartowski-4612927-1761683"), listing.primary)
        assertEquals(listOf("Qwen_Qwen3.6-27B", "bartowski/Qwen_Qwen3.6-27B-GGUF", "Qwen/Qwen3.6-27B", "bartowski", "Q6_K"), listing.secondary)
        assertNull(listing.text)

        val request = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(Event.fromJson(requestWithVramJson)))
        assertEquals(listOf("DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF"), request.primary)
        assertEquals(listOf("https://huggingface.co/DavidAU/Qwen3.8-27B-TURBO-Fable-Cold-Fusion-735-882-Heretic-Uncensored-NEO-CODER-MAX-MTP-GGUF"), request.websites)

        assertEquals(IndexableFields.None, SearchFieldExtractor.extract(Event.fromJson(seederRequestJson)))

        // The other apps' shapes and the release carry nothing to search.
        assertEquals(IndexableFields.None, SearchFieldExtractor.extract(Event.fromJson(usageCounterJson)))
        val release = EventFactory.create<Event>("11".repeat(32), me, 1L, 30100, arrayOf(arrayOf("d", "waifu-magnet"), arrayOf("version", "22"), arrayOf("sha256", "aa".repeat(32))), "", "00".repeat(64))
        assertIs<ClientReleaseEvent>(release)
        assertEquals(IndexableFields.None, SearchFieldExtractor.extract(release))
    }

    @Test
    fun theSignedHashDecidesWhatIsDownloaded() {
        val hash = "17616834d44b4e2d1b35494d0b80ee61da3cea79"
        val other = "ffffffffffffffffffffffffffffffffffffffff"

        fun listing(vararg extra: Array<String>) = EventFactory.create<ModelTorrentEvent>("11".repeat(32), me, 1L, 30099, arrayOf(arrayOf("d", hash), arrayOf("name", "m"), *extra), "", "00".repeat(64))

        assertEquals("magnet:?xt=urn:btih:$hash&dn=x", listing(arrayOf("magnet", "magnet:?xt=urn:btih:$hash&dn=x")).magnetLink())
        // A magnet for another torrent, or something that is no magnet, gives way to one built from `d`.
        assertEquals("magnet:?xt=urn:btih:$hash&dn=m", listing(arrayOf("magnet", "magnet:?xt=urn:btih:$other")).magnetLink())
        assertEquals("magnet:?xt=urn:btih:$hash&dn=m", listing(arrayOf("magnet", "javascript:alert(1)")).magnetLink())
        // v2 (64 hex) is not a btih: not taken as this listing's infohash.
        assertNull(EventFactory.create<ModelTorrentEvent>("11".repeat(32), me, 1L, 30099, arrayOf(arrayOf("d", "ab".repeat(32)), arrayOf("name", "m"), arrayOf("url", "https://a/b")), "", "00".repeat(64)).infoHash())
    }

    @Test
    fun readsOnlyHuggingFaceModelRepositories() {
        assertEquals("Qwen/Qwen3", ModelTorrentEvent.repoIdFromUrl("huggingface.co/Qwen/Qwen3"))
        assertEquals("Qwen/Qwen3", ModelTorrentEvent.repoIdFromUrl("https://www.huggingface.co/Qwen/Qwen3/tree/main?x=1"))
        assertNull(ModelTorrentEvent.repoIdFromUrl("https://huggingface.co/datasets/org/x"))
        assertNull(ModelTorrentEvent.repoIdFromUrl("https://huggingface.co/spaces/org/x"))
        assertNull(ModelTorrentEvent.repoIdFromUrl("https://evilhuggingface.co/a/b"))
        assertNull(ModelTorrentEvent.repoIdFromUrl("https://huggingface.co/Qwen"))
    }

    @Test
    fun classifiersNeedTheWholeShape() {
        // An `i` alone (NIP-73) is not a seeder request; a `type` outside the viewer's is not a request.
        assertIs<UnrecognizedKind30102Event>(EventFactory.create<Event>("11".repeat(32), me, 1L, 30102, arrayOf(arrayOf("d", "x"), arrayOf("i", "isbn:9780"), arrayOf("name", "n")), "", "00".repeat(64)))
        assertIs<UnrecognizedKind30103Event>(EventFactory.create<Event>("11".repeat(32), me, 1L, 30103, arrayOf(arrayOf("d", "x"), arrayOf("name", "n"), arrayOf("type", "note")), "", "00".repeat(64)))
    }

    @Test
    fun aSeederRequestForASubmissionKeepsTheListingKey() =
        runTest {
            val submission = ModelTorrentEvent.buildSubmission(signer.pubKey, "https://a.example/x.torrent", "m").signAndParse() as ModelTorrentEvent
            val request = SeederRequestEvent.build(EventHintBundle(submission, null), signer.pubKey).signAndParse()
            assertIs<SeederRequestEvent>(request)
            assertEquals(submission.dTag(), request.listingKey())
            assertNull(request.infoHash())
        }

    @Test
    fun readsTheReleaseVersionLikeParseInt() {
        fun release(version: String) = EventFactory.create<ClientReleaseEvent>("11".repeat(32), me, 1L, 30100, arrayOf(arrayOf("d", "waifu-magnet"), arrayOf("version", version), arrayOf("sha256", "aa")), "", "00".repeat(64))
        assertEquals(22L, release("22").versionNumber())
        assertEquals(22L, release("22.1").versionNumber())
        assertEquals(22L, release("22a").versionNumber())
        assertNull(release("v22").versionNumber())
    }

    @Test
    fun aSubmissionIsSearchedByItsNameOnce() =
        runTest {
            val submission = ModelTorrentEvent.buildSubmission(signer.pubKey, "https://a.example/x.torrent", "Qwen3", lab = "Qwen").signAndParse()
            val fields = assertIs<IndexableFields.Tiered>(SearchFieldExtractor.extract(submission))
            assertEquals(listOf("Qwen3"), fields.primary)
            assertEquals(listOf("Qwen"), fields.secondary)
            assertEquals("Qwen3\nQwen", (submission as ModelTorrentEvent).indexableContent())
        }
}
