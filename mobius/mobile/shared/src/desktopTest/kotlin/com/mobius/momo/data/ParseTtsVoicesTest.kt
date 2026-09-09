package com.mobius.momo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseTtsVoicesTest {
    // cloud-21.agent-matrix.com 真实返回(用 test1 登录拉取), 10 项 label 全有效。
    // 用 \uXXXX 转义避免源码中文编码问题, kotlinx.serialization 会还原为中文。
    private val cloud21Raw = """
        {"ok":true,"default_voice":"zh_female_vv_uranus_bigtts","voices":[{"id":"zh_female_vv_uranus_bigtts","label":"vivi 2.0","language":"zh-CN","gender":"female","category":"general","description":"自然清亮的通用女声","default":true},{"id":"zh_female_xiaohe_uranus_bigtts","label":"小何","language":"zh-CN","gender":"female","category":"general","description":"温和耐听的通用女声","default":false},{"id":"zh_male_m191_uranus_bigtts","label":"云舟","language":"zh-CN","gender":"male","category":"general","description":"稳重清晰的通用男声","default":false},{"id":"zh_male_taocheng_uranus_bigtts","label":"小天","language":"zh-CN","gender":"male","category":"general","description":"明快自然的通用男声","default":false},{"id":"saturn_zh_female_cancan_tob","label":"知性灿灿","language":"zh-CN","gender":"female","category":"role","description":"偏知性表达的角色女声","default":false},{"id":"saturn_zh_female_keainvsheng_tob","label":"可爱女生","language":"zh-CN","gender":"female","category":"role","description":"轻快活泼的角色女声","default":false},{"id":"saturn_zh_female_tiaopigongzhu_tob","label":"调皮公主","language":"zh-CN","gender":"female","category":"role","description":"更有角色感的俏皮女声","default":false},{"id":"saturn_zh_male_shuanglangshaonian_tob","label":"爽朗少年","language":"zh-CN","gender":"male","category":"role","description":"明亮爽朗的少年音色","default":false},{"id":"saturn_zh_male_tiancaitongzhuo_tob","label":"天才同桌","language":"zh-CN","gender":"male","category":"role","description":"偏年轻化的角色男声","default":false},{"id":"en_male_tim_uranus_bigtts","label":"Tim","language":"en-US","gender":"male","category":"general","description":"英文通用男声","default":false}],"configured":true}
    """.trimIndent()

    @Test
    fun parses_cloud21_real_payload_into_ten_non_blank_voices() {
        val obj = Json.parseToJsonElement(cloud21Raw) as JsonObject
        val result = parseTtsVoices(obj)

        assertEquals(10, result.voices.size, "应为 10 个音色, 实际: ${result.voices.map { it.label }}")
        result.voices.forEach { v ->
            assertTrue(v.label.isNotBlank(), "label 不应为空: id=${v.id}")
        }
        assertTrue(result.voices.any { it.label == "天才同桌" }, "缺天才同桌")
        assertTrue(result.voices.any { it.label == "Tim" }, "缺 Tim")
    }

    @Test
    fun filters_out_blank_label_voice() {
        val raw = """
            {"voices":[{"id":"a","label":"有效"},{"id":"b","label":""},{"id":"c","label":"   "}],"configured":true}
        """.trimIndent()
        val result = parseTtsVoices(Json.parseToJsonElement(raw) as JsonObject)
        assertEquals(1, result.voices.size)
        assertEquals("有效", result.voices.first().label)
    }
}
