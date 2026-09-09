package com.mobius.momo.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证 Desktop 端静音检测：用于在上传前拦截「录到字节但实际是静音」的情况（macOS 默认 mixer
 * 选错 / app 未获麦克风权限时录到的全零 buffer），避免发后端干等 422 ASR_EMPTY_TEXT。
 *
 * 阈值与 computeVolumeLevel 的 rms<4（音量条最低档）一致——即「音量条不跳」即判静音。
 */
class SpeechSilenceDetectionTest {
    @Test
    fun allZeroPcmIsSilent() {
        assertTrue(isPcmEffectivelySilent(ByteArray(2000)))
    }

    @Test
    fun tinyAmplitudeIsSilent() {
        // sample 振幅 ±100（远低于阈值对应的 ~±512）→ 音量条不会跳 → 静音
        assertTrue(isPcmEffectivelySilent(pcmFromSamples { i -> if (i % 2 == 0) 100 else -100 }))
    }

    @Test
    fun normalSpeechIsNotSilent() {
        // sample 振幅 ±8000（正常说话电平）→ 音量条会跳 → 非静音
        assertFalse(isPcmEffectivelySilent(pcmFromSamples { i -> if (i % 2 == 0) 8000 else -8000 }))
    }

    @Test
    fun emptyOrTooShortIsSilent() {
        assertTrue(isPcmEffectivelySilent(ByteArray(0)))
        assertTrue(isPcmEffectivelySilent(ByteArray(1)))
    }

    private fun pcmFromSamples(sample: (Int) -> Int): ByteArray {
        val n = 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = sample(i)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
