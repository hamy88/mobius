package com.mobius.momo.data

import javazoom.jl.player.advanced.AdvancedPlayer
import javazoom.jl.player.advanced.PlaybackListener
import javazoom.jl.player.advanced.PlaybackEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import javax.swing.SwingUtilities
import java.util.Locale
import kotlin.concurrent.thread

actual fun createSpeechPermissionController(): SpeechPermissionController =
    object : SpeechPermissionController {
        override fun hasPermission(): Boolean = microphoneAvailable()
        override suspend fun requestPermission(): SpeechPermissionStatus =
            if (microphoneAvailable()) SpeechPermissionStatus.Granted else SpeechPermissionStatus.Denied
    }

actual fun createSpeechRecognizer(): SpeechRecognizer = DesktopRealSpeechRecognizer()

private class DesktopRealSpeechRecognizer : SpeechRecognizer {
    @Volatile private var active: Boolean = false
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var recordThread: Thread? = null
    private var buffer: ByteArrayOutputStream? = null
    private var onEventRef: ((SpeechRecognitionEvent) -> Unit)? = null

    override fun start(languageTag: String, onEvent: (SpeechRecognitionEvent) -> Unit) {
        cancel()
        onEventRef = onEvent
        if (!microphoneAvailable()) {
            emit { onEvent(SpeechRecognitionEvent.Error("未检测到麦克风，Desktop 端无法使用语音输入")) }
            return
        }
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        // macOS/Windows 上 AudioSystem.getLine 默认 mixer 常常不是真实输入设备——open 成功
        // 却录到全静音 PCM（表现为音量条不跳、后端 422 ASR_EMPTY_TEXT）。显式遍历 Mixer.Info，
        // 优先选「非默认、直接支持目标格式」的录音设备，并在 open 后提升增益。
        val opened = openRecordingLine(format, info)
        if (opened == null) {
            emit { onEvent(SpeechRecognitionEvent.Error("未检测到麦克风，Desktop 端无法使用语音输入")) }
            return
        }
        line = opened
        buffer = ByteArrayOutputStream()
        active = true
        emit { onEvent(SpeechRecognitionEvent.Ready) }
        recordThread = thread(name = "momo-desktop-asr", isDaemon = true) {
            val chunk = ByteArray(1600) // 50ms @ 16kHz mono 16bit
            while (active) {
                val read = try {
                    opened.read(chunk, 0, chunk.size)
                } catch (e: Throwable) {
                    break
                }
                if (read <= 0) continue
                buffer?.write(chunk, 0, read)
                val level = computeVolumeLevel(chunk, read)
                emit { onEvent(SpeechRecognitionEvent.Volume(level)) }
            }
        }
    }

    override fun stop() {
        if (!active) {
            cleanupLine()
            return
        }
        active = false
        try { recordThread?.join(1000L) } catch (_: InterruptedException) {}
        recordThread = null
        val captured = buffer?.toByteArray()
        cleanupLine()
        if (captured == null || captured.isEmpty()) {
            emit { onEventRef?.invoke(SpeechRecognitionEvent.Error("录音内容为空，请重新录制")) }
            return
        }
        // 静音检测兜底：录到了字节但 RMS 极低 = 实际没采到声音（典型于默认 mixer 选错，或
        // app 未获麦克风权限时 macOS 喂的静音 buffer）。直接提示，避免发后端干等 422
        // ASR_EMPTY_TEXT「没有识别到清晰语音」。
        if (isPcmEffectivelySilent(captured)) {
            emit {
                onEventRef?.invoke(
                    SpeechRecognitionEvent.Error("未检测到声音，请检查麦克风是否接入、系统默认输入设备，或是否已授予麦克风权限"),
                )
            }
            return
        }
        val wav = WavContainer.encode(captured, 16_000, 1, 16)
        emit {
            onEventRef?.invoke(SpeechRecognitionEvent.Audio(wav, "audio/wav", "momo-voice.wav"))
            onEventRef?.invoke(SpeechRecognitionEvent.End)
        }
    }

    override fun cancel() {
        active = false
        try { recordThread?.join(500L) } catch (_: InterruptedException) {}
        recordThread = null
        cleanupLine()
    }

    override fun dispose() {
        cancel()
        onEventRef = null
    }

    private fun cleanupLine() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        buffer = null
    }

    private fun emit(block: () -> Unit) {
        SwingUtilities.invokeLater(block)
    }

    private fun computeVolumeLevel(chunk: ByteArray, len: Int): Int {
        if (len < 2) return 1
        var sum = 0L
        var count = 0
        var i = 0
        while (i + 1 < len) {
            val lo = chunk[i].toInt()
            val hi = chunk[i + 1].toInt()
            val sample = (hi shl 8) or (lo and 0xFF)
            sum += (sample.toLong() * sample.toLong()) shr 16
            count++
            i += 2
        }
        if (count == 0) return 1
        val rms = (sum / count).toInt()
        return when {
            rms < 4 -> 1
            rms < 16 -> 2
            rms < 64 -> 3
            rms < 256 -> 4
            else -> 5
        }.coerceIn(1, 5)
    }
}

private object WavContainer {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        val chunkSize = 36 + dataLen
        val out = ByteArrayOutputStream(dataLen + 44)
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        out.write(intToLE(chunkSize))
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        out.write(intToLE(16))
        out.write(shortToLE(1)) // PCM
        out.write(shortToLE(channels))
        out.write(intToLE(sampleRate))
        out.write(intToLE(byteRate))
        out.write(shortToLE(blockAlign))
        out.write(shortToLE(bitsPerSample))
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        out.write(intToLE(dataLen))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortToLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}

private fun microphoneAvailable(): Boolean = try {
    val format = AudioFormat(16_000f, 16, 1, true, false)
    val info = DataLine.Info(TargetDataLine::class.java, format)
    AudioSystem.getLine(info) is TargetDataLine
} catch (_: Throwable) {
    false
}

/**
 * 打开一个能真正采到声音的 TargetDataLine。**优先用默认 line**（AudioSystem.getLine）——
 * MicTest 实测 macOS 上默认 line 在有麦克风权限时能正常录音（平均幅值 1277 / 最大 15111）。
 * 盲目「优先非默认 mixer」反而会选到支持目标格式却录不到声的设备（曾导致 mac 录到静音）。
 * 仅当默认 line open 失败时，才遍历其他 mixer 兜底。open 后尝试提升增益（line 不支持则跳过）。
 */
private fun openRecordingLine(format: AudioFormat, info: DataLine.Info): TargetDataLine? {
    openDefaultLine(info, format)?.let { return it }
    val defaultMixer = runCatching { AudioSystem.getMixer(null) }.getOrNull()
    val mixers = runCatching { AudioSystem.getMixerInfo() }.getOrNull().orEmpty()
    for (mixerInfo in mixers) {
        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: continue
        if (mixer === defaultMixer) continue
        openLineFromMixer(mixer, info, format)?.let { return it }
    }
    return null
}

private fun openLineFromMixer(mixer: Mixer, info: DataLine.Info, format: AudioFormat): TargetDataLine? {
    if (!runCatching { mixer.isLineSupported(info) }.getOrDefault(false)) return null
    val line = runCatching { mixer.getLine(info) as? TargetDataLine }.getOrNull() ?: return null
    return if (openAndBoost(line, format)) line else null
}

private fun openDefaultLine(info: DataLine.Info, format: AudioFormat): TargetDataLine? {
    val line = try {
        AudioSystem.getLine(info) as? TargetDataLine
    } catch (_: LineUnavailableException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    return if (openAndBoost(line, format)) line else null
}

private fun openAndBoost(line: TargetDataLine, format: AudioFormat): Boolean = try {
    line.open(format)
    applyInputGain(line)
    line.start()
    true
} catch (_: LineUnavailableException) {
    runCatching { line.close() }; false
} catch (_: IllegalArgumentException) {
    runCatching { line.close() }; false
}

/** 提升输入增益：部分平台默认电平极低，录到近乎静音。优先 VOLUME（推到约 80%），其次 MASTER_GAIN（推到 0dB）。 */
private fun applyInputGain(line: TargetDataLine) {
    runCatching {
        val controls = line.controls
        val volume = controls.firstOrNull { it is FloatControl && it.type == FloatControl.Type.VOLUME } as? FloatControl
        when {
            volume != null -> {
                val target = volume.maximum.coerceIn(volume.minimum, volume.maximum)
                volume.value = (volume.minimum + (target - volume.minimum) * 0.8f)
                    .coerceIn(volume.minimum, volume.maximum)
            }
            else -> {
                (controls.firstOrNull { it is FloatControl && it.type == FloatControl.Type.MASTER_GAIN } as? FloatControl)
                    ?.let { gain -> gain.value = 0f.coerceIn(gain.minimum, gain.maximum) }
            }
        }
    }
}

/** 静音判定阈值：与 computeVolumeLevel 的 rms<4（音量条最低档）一致——音量条不跳即判静音。 */
internal const val SILENCE_RMS_THRESHOLD = 4

/**
 * PCM 是否有效静音：按 16bit little-endian mono 计算 RMS（与 computeVolumeLevel 同算法），
 * 低于 [SILENCE_RMS_THRESHOLD] 视为没采到声音。用于上传前拦截「录到字节但是静音」的情况
 * （macOS 默认 mixer 选错 / app 未获麦克风权限时 macOS 喂的静音 buffer），避免发后端干等 422。
 */
internal fun isPcmEffectivelySilent(pcm: ByteArray, threshold: Int = SILENCE_RMS_THRESHOLD): Boolean {
    if (pcm.size < 2) return true
    var sum = 0L
    var count = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val lo = pcm[i].toInt() and 0xFF
        val hi = pcm[i + 1].toInt()
        val sample = (hi shl 8) or lo
        sum += (sample.toLong() * sample.toLong()) shr 16
        count++
        i += 2
    }
    if (count == 0) return true
    return (sum / count).toInt() < threshold
}

actual fun createTextToSpeech(): TextToSpeech = DesktopSystemTextToSpeech()

actual fun createSystemTtsEngine(): TtsEngine = SystemTtsEngine(createTextToSpeech())

private class DesktopSystemTextToSpeech : TextToSpeech {
    @Volatile private var process: Process? = null
    @Volatile private var active: Boolean = false

    override fun speak(text: String, languageTag: String, onError: (String) -> Unit, onComplete: () -> Unit) {
        if (text.isBlank()) {
            SwingUtilities.invokeLater { onComplete() }
            return
        }
        val command = buildSpeakCommand(text, languageTag)
        if (command == null) {
            SwingUtilities.invokeLater {
                onError("当前系统未找到可用的语音合成命令（macOS say / Windows SAPI / Linux espeak）")
            }
            return
        }
        stop()
        active = true
        thread(name = "momo-desktop-tts", isDaemon = true) {
            try {
                val proc = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()
                process = proc
                val exitCode = proc.waitFor()
                if (!active) return@thread
                if (exitCode == 0) {
                    SwingUtilities.invokeLater { onComplete() }
                } else {
                    SwingUtilities.invokeLater { onError("系统语音退出码 $exitCode") }
                }
            } catch (e: Throwable) {
                if (active) {
                    SwingUtilities.invokeLater { onError(e.message ?: "系统语音失败") }
                }
            } finally {
                process = null
                active = false
            }
        }
    }

    override fun stop() {
        active = false
        process?.runCatching { destroy() }
        process = null
    }

    override fun dispose() = stop()

    private fun buildSpeakCommand(text: String, languageTag: String): List<String>? {
        val osName = System.getProperty("os.name")?.lowercase(Locale.ROOT).orEmpty()
        return when {
            osName.contains("mac") || osName.contains("darwin") -> {
                val sayBin = locateExecutable("/usr/bin/say") ?: return null
                val rate = if (languageTag.startsWith("en", ignoreCase = true)) "200" else "180"
                listOf(sayBin, "-r", rate, text)
            }
            osName.contains("win") -> {
                val powershell = locateExecutable(
                    System.getenv("WINDIR")?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" }
                        ?: "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                ) ?: return null
                val payload = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                val script = buildString {
                    append("try { ")
                    append("\$ErrorActionPreference='Stop'; ")
                    append("Add-Type -AssemblyName System.Speech; ")
                    append("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ")
                    append("\$decoded = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String('")
                    append(payload)
                    append("')); ")
                    append("\$s.Speak(\$decoded); ")
                    append("exit 0 ")
                    append("} catch { Write-Error \$_.Exception.Message; exit 1 }")
                }
                listOf(powershell, "-NoProfile", "-NonInteractive", "-Command", script)
            }
            else -> {
                val espeak = locateExecutable("/usr/bin/espeak") ?: locateExecutable("/usr/local/bin/espeak")
                if (espeak != null) {
                    val voice = if (languageTag.startsWith("en", ignoreCase = true)) "en" else "zh"
                    listOf(espeak, "-v", voice, text)
                } else {
                    null
                }
            }
        }
    }

    private fun locateExecutable(path: String): String? =
        if (path.isNotBlank() && java.io.File(path).canExecute()) path else null
}

internal actual fun createPlatformAudioPlayer(): PlatformAudioPlayer = DesktopPlatformAudioPlayer()

private class DesktopPlatformAudioPlayer : PlatformAudioPlayer {
    @Volatile
    private var player: AdvancedPlayer? = null
    @Volatile
    private var playbackThread: Thread? = null
    @Volatile
    private var active: Boolean = false

    override fun play(bytes: ByteArray, mimeType: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        stop()
        active = true
        playbackThread = thread(name = "momo-tts-playback", isDaemon = true) {
            try {
                val stream = ByteArrayInputStream(bytes)
                val audioPlayer = AdvancedPlayer(stream)
                synchronized(this) { player = audioPlayer }
                audioPlayer.playBackListener = object : PlaybackListener() {
                    override fun playbackFinished(evt: PlaybackEvent?) {
                        if (active) SwingUtilities.invokeLater { onComplete() }
                        runCatching { audioPlayer.close() }
                    }
                }
                audioPlayer.play()
            } catch (e: Throwable) {
                if (active) SwingUtilities.invokeLater { onError(e.message ?: "音频播放失败") }
            } finally {
                synchronized(this) { player = null }
            }
        }
    }

    override fun stop() {
        active = false
        synchronized(this) {
            runCatching { player?.close() }
            player = null
        }
    }

    override fun dispose() = stop()
}
