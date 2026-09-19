package com.mobius.momo.ota

import com.mobius.momo.data.OtaManifest
import com.mobius.momo.data.SecureStorage
import com.mobius.momo.viewmodel.OtaCheckUseCase
import com.mobius.momo.viewmodel.ThresholdEvaluator
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * In-memory SecureStorage stub。用于 desktopTest 隔离 SharedPreferences / Keychain 等
 * 平台实现, 不污染真实持久化, 也无需 mock 框架(保持测试零依赖)。
 */
private class InMemorySecureStorage : SecureStorage {
    private val prefs = mutableMapOf<String, String>()
    override fun saveToken(token: String) { prefs["token"] = token }
    override fun getToken(): String? = prefs["token"]
    override fun saveTokenMetadata(metadata: com.mobius.momo.data.StoredTokenMetadata) {
        prefs["token_meta_base"] = metadata.baseUrl
        prefs["token_meta_saved"] = metadata.savedAtEpochMillis.toString()
        prefs["token_meta_ver"] = metadata.storageVersion.toString()
    }
    override fun getTokenMetadata(): com.mobius.momo.data.StoredTokenMetadata? = null
    override fun savePreference(key: String, value: String) { prefs[key] = value }
    override fun getPreference(key: String): String? = prefs[key]
    override fun clear() { prefs.clear() }
}

/**
 * §3.2 启动 5s 后静默检查 + 设置页"检查更新"按钮触发的同一 useCase 单元测试。
 *
 * 验证三件事：
 * 1. triggerOtaCheck 路径：manifestProvider 注入新版本 → OtaCheckResult.Show(level=Normal)
 * 2. 远端 ≤ 本地（已在最新版本）→ OtaCheckResult.NoUpdate
 * 3. 用户已忽略当前版本 → OtaCheckResult.NoUpdate（即便远端 > 本地）
 *
 * 集成测试关注点：MomoAppViewModel.triggerOtaCheck 把 OtaCheckUseCase.run() 的结果
 * 原样写回 UiState.otaCheckResult；这里直接测 useCase(同一 contract), 间接覆盖 VM 的状态机。
 */
class MomoAppViewModelOtaTest {

    private val storage = InMemorySecureStorage()

    @Test
    fun `manifest with new version returns Show_Normal`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        val result = useCase.run {
            OtaManifest(version = "0.4.0", versionCode = 24)
        }
        val show = assertIs<OtaCheckUseCase.OtaCheckResult.Show>(result)
        assertEquals(ThresholdEvaluator.Level.Normal, show.level)
        assertEquals("0.4.0", show.manifest.version)
    }

    @Test
    fun `remote version equal to local yields Invalid`() = runBlocking<Unit> {
        // 注意：OtaCheckUseCase.run 把 ThresholdEvaluator.Level.NoUpdate 映射为 OtaCheckResult.Invalid
        // (即"无更新或元数据异常"分支); UI 层看到 Invalid 不弹窗, 设置页提示"无法验证"。
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.4.0",
        )
        val result = useCase.run {
            OtaManifest(version = "0.4.0", versionCode = 24)
        }
        assertEquals(OtaCheckUseCase.OtaCheckResult.Invalid, result)
    }

    @Test
    fun `remote version older than local yields Invalid`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.4.0",
        )
        val result = useCase.run {
            OtaManifest(version = "0.3.0", versionCode = 22)
        }
        assertEquals(OtaCheckUseCase.OtaCheckResult.Invalid, result)
    }

    @Test
    fun `user ignored this version returns NoUpdate`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        // 模拟用户在 0.3.0 时点过"忽略此版本 0.4.0"
        useCase.markIgnored("0.4.0")
        val result = useCase.run {
            OtaManifest(version = "0.4.0", versionCode = 24)
        }
        assertEquals(OtaCheckUseCase.OtaCheckResult.NoUpdate, result)
    }

    @Test
    fun `invalid manifest returns Invalid`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        // version 空白 → §5.5 强校验失败
        val result = useCase.run {
            OtaManifest(version = "", versionCode = 0)
        }
        assertEquals(OtaCheckUseCase.OtaCheckResult.Invalid, result)
    }

    @Test
    fun `null manifest provider yields NetworkError`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        // manifestProvider=null 走真实网络路径, 没注入 mock repo → NetworkError
        val result = useCase.run(manifestProvider = null)
        assertIs<OtaCheckUseCase.OtaCheckResult.NetworkError>(result)
    }

    @Test
    fun `markDismissed writes silent until timestamp`() {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        useCase.markDismissed(ThresholdEvaluator.Level.Normal)
        // silent until 应当被持久化到 ota_silent_until_millis, 值 > 0
        val raw = storage.getPreference("ota_silent_until_millis")
        assertNotNull(raw)
        val until = raw!!.toLong()
        assertTrue(until > 0L, "silent until 应当被设置为未来的 timestamp")
    }

    @Test
    fun `clearIgnored clears ignored versions`() {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        useCase.markIgnored("0.4.0")
        useCase.markIgnored("0.4.1")
        assertEquals(setOf("0.4.0", "0.4.1"), useCase.readIgnored())
        useCase.clearIgnored()
        assertEquals(emptySet(), useCase.readIgnored())
    }

    @Test
    fun `manifestProvider parameter takes precedence over network`() = runBlocking<Unit> {
        val useCase = OtaCheckUseCase(
            storage = storage,
            localVersion = "0.3.0",
        )
        // 注入 provider 直接返回指定 manifest, 不依赖 repo
        val provider: suspend () -> OtaManifest? = { OtaManifest(version = "0.5.0", versionCode = 25) }
        val result = useCase.run(manifestProvider = provider)
        val show = assertIs<OtaCheckUseCase.OtaCheckResult.Show>(result)
        assertEquals("0.5.0", show.manifest.version)
    }
}
