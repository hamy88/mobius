package com.mobius.momo.ota

import com.mobius.momo.data.ChangelogItem
import com.mobius.momo.data.ChangelogType
import com.mobius.momo.data.OtaManifest
import com.mobius.momo.viewmodel.OtaCheckUseCase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.4.3: 验证 [OtaCheckUseCase.OtaCheckResult.Show] 携带 [OtaCheckUseCase.OtaCheckResult.Show.changelogItems].
 *
 * 通过 manifestProvider 注入 fake manifestProvider(当前数据路径), 验证 changelogItems 默认值与透传.
 *
 * 真实 GitHub channel 的 changelogItems 透传(来自 release.changelogItems)由
 * [com.mobius.momo.data.parseLatestNonDraftRelease] 负责, 已在 [OtaRepositoryHttpTest] 覆盖.
 */
class OtaCheckUseCaseChangelogTest {

    @Test
    fun `Show defaults changelogItems to empty list`() = runBlocking<Unit> {
        val storage = OtaTestSecureStorage()
        val useCase = OtaCheckUseCase(
            storage = storage,
            localBaseUrl = "", // 跳过本服务器, 仅走 manifestProvider
            localVersion = "0.4.2",
        )
        val show = assertIs<OtaCheckUseCase.OtaCheckResult.Show>(
            useCase.run { OtaManifest(version = "0.4.3", versionCode = 27) },
        )
        assertEquals(emptyList(), show.changelogItems,
            "manifestProvider 注入路径默认 changelogItems 为空 list(客户端由 GitHub channel 解析填充)")
    }

    @Test
    fun `Show carries caller-provided changelogItems via default Show copy`() = runBlocking<Unit> {
        // 直接构造 Show 数据类, 验证它能携带 changelogItems 字段(给上层 UI 消费).
        val items = listOf(
            ChangelogItem(ChangelogType.Feature, "首条摘要"),
            ChangelogItem(ChangelogType.Fix, "某修复"),
        )
        val show = OtaCheckUseCase.OtaCheckResult.Show(
            level = com.mobius.momo.viewmodel.ThresholdEvaluator.Level.Normal,
            manifest = OtaManifest(version = "0.4.3", versionCode = 27),
            minSupportedVersion = null,
            reasonDisplay = null,
            advisoryId = null,
            hardBlockBypassable = true,
            changelogItems = items,
        )
        assertEquals(2, show.changelogItems.size)
        assertEquals("首条摘要", show.changelogItems.first().text)
        assertEquals(ChangelogType.Feature, show.changelogItems.first().type)
        assertEquals(ChangelogType.Fix, show.changelogItems[1].type)
        assertTrue(show.changelogItems.all { it.text.isNotBlank() })
    }
}