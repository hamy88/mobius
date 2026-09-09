package com.mobius.momo.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证豆包 TTS 的 (voice, text) 客户端音频缓存。
 *
 * 背景（Bug3）：豆包是云端合成，每次播放都要一次网络往返，是「切换音色后点击播放/自动播报滞后」的主因。
 * 修复=按 (voice, text) 缓存合成音频 + 切换音色后预取(prefetch)最新消息在该音色下的音频，使随后的播放直接命中缓存。
 *
 * 这里只覆盖 prefetch 的缓存/去重逻辑（不触发真实音频播放，避免依赖音频硬件）：
 * - 相同 (voice, text) 第二次 prefetch 命中缓存，不再请求网络；
 * - 不同 voice 视为不同条目，需各自取一次。
 */
class DoubaoTtsCacheTest {
    @Test
    fun prefetchCachesSoRepeatDoesNotRefetch() = runBlocking {
        var fetchCalls = 0
        val engine = DoubaoTtsEngine(fetchAudio = { _, _ ->
            fetchCalls++
            "audio".toByteArray()
        })

        engine.prefetch("你好，世界", "voiceA")
        engine.prefetch("你好，世界", "voiceA") // 命中缓存

        assertEquals(1, fetchCalls)
    }

    @Test
    fun differentVoiceOrTextRefetches() = runBlocking {
        var fetchCalls = 0
        val engine = DoubaoTtsEngine(fetchAudio = { _, _ ->
            fetchCalls++
            "audio".toByteArray()
        })

        engine.prefetch("你好", "voiceA")
        engine.prefetch("你好", "voiceB") // 换音色 → 重新合成
        engine.prefetch("再见", "voiceA") // 换文本 → 重新合成

        assertEquals(3, fetchCalls)
    }

    @Test
    fun concurrentSameKeySharesOneInFlightFetch() = runBlocking {
        // 预取与播放(或两个预取)同时命中未缓存项时, 应共享同一次在途请求, 不重复发起(避免重复的连接超时)。
        var fetchCalls = 0
        val engine = DoubaoTtsEngine(fetchAudio = { _, _ ->
            fetchCalls++
            delay(200) // 模拟慢合成, 确保两次 prefetch 重叠在同一个在途请求上
            "audio".toByteArray()
        })
        val a = launch { engine.prefetch("同一条消息", "voiceA") }
        val b = launch { engine.prefetch("同一条消息", "voiceA") }
        a.join(); b.join()

        assertEquals(1, fetchCalls)
    }
}
