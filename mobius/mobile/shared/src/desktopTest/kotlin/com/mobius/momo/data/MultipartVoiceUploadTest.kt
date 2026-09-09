package com.mobius.momo.data

import io.ktor.http.content.OutgoingContent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 验证客户端语音上传 multipart body 是标准的 multipart/form-data，且含后端 multer.single('audio')
 * 据以解析的关键标记。
 *
 * 背景：Android/Desktop 两端录音 API 不同却都报后端 ASR_AUDIO_EMPTY（multer 找不到 audio 字段）。
 * multipartFileContent 已改为手动构造 ByteArrayContent（一次性、Content-Length 精确、RFC 标准），
 * 绕过 Ktor MultiPartFormDataContent(WriteChannelContent 流式)+OkHttp 的兼容性问题。
 */
class MultipartVoiceUploadTest {
    @Test
    fun voiceMultipartBodyIsStandardFormWithData() {
        val payload = ByteArray(8192) { (it and 0xFF).toByte() }
        payload[0] = 'R'.code.toByte()
        payload[1] = 'I'.code.toByte()
        payload[2] = 'F'.code.toByte()
        payload[3] = 'F'.code.toByte()

        val content = multipartFileContent(
            fieldName = "audio",
            fileName = "momo-voice.wav",
            mimeType = "audio/wav",
            bytes = payload,
            boundaryPrefix = "test",
            fallbackName = "momo-voice.m4a",
        )
        assertTrue(
            content.contentType.toString().contains("multipart/form-data"),
            "Content-Type 应为 multipart/form-data，实际: ${content.contentType}",
        )
        assertTrue(content is OutgoingContent.ByteArrayContent, "应为 ByteArrayContent，实际: ${content::class.simpleName}")

        val body = (content as OutgoingContent.ByteArrayContent).bytes()
        val asString = body.toString(Charsets.ISO_8859_1)

        assertTrue(asString.contains("name=\"audio\""), "body 缺少 name=\"audio\"\n${asString.take(500)}")
        assertTrue(asString.contains("filename="), "body 缺少 filename\n${asString.take(500)}")
        assertTrue(asString.contains("audio/wav"), "body 缺少 audio/wav\n${asString.take(500)}")
        assertTrue(asString.contains("RIFF"), "body 未包含音频 payload\n${asString.take(500)}")
        assertTrue(body.size >= payload.size + 100, "body 大小 ${body.size} 未含完整 payload ${payload.size}")
    }
}
