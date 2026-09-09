package com.mobius.momo.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MobiusApiTest {
    @Test
    fun stripEmptyVoiceMarkerLine() {
        val raw = "你好\nPushVoiceToUser(\"\")\n世界"

        assertEquals("你好\n世界", stripVoiceMarkers(raw))
    }

    @Test
    fun stripInlineVoiceMarker() {
        val raw = "回答如下：PushVoiceToUser(\"关键结论\")后续说明"

        assertEquals("回答如下：后续说明", stripVoiceMarkers(raw))
    }

    @Test
    fun stripLineOnlyVoiceMarkerWithSemicolon() {
        val raw = "前文\nPushVoiceToUser(\"语音内容\");\n后文"

        assertEquals("前文\n后文", stripVoiceMarkers(raw))
    }

    @Test
    fun stripSingleQuotedEmptyVoiceMarkerInline() {
        val raw = "前文 PushVoiceToUser('') 后文"

        assertEquals("前文 后文", stripVoiceMarkers(raw))
    }

    @Test
    fun stripMixedEmptyAndContentVoiceMarkersInline() {
        val raw = "你好 PushVoiceToUser(\"\") 下一步 PushVoiceToUser(\"播报内容\")"

        assertEquals("你好 下一步", stripVoiceMarkers(raw))
    }
}
