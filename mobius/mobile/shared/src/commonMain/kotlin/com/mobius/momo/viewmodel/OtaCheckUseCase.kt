package com.mobius.momo.viewmodel

import com.mobius.momo.data.ChangelogItem
import com.mobius.momo.data.OtaManifest
import com.mobius.momo.data.OtaRepository
import com.mobius.momo.data.SecureStorage
import com.mobius.momo.data.createOtaRepository
import com.mobius.momo.data.nowEpochMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §3.2 启动 5s 后静默检查 + §3.3 版本比对 + §10.5 档位判定的统一入口。
 *
 * 调用约定：
 * - App 启动 / 回前台时触发 [run]，返回 [OtaCheckResult]；UI 层根据 result 决定弹窗时机与文案。
 * - "忽略此版本" 仅 normal 档持久化（§8.3 + §10.5.4），advisory/strong_advisory/hard_block 不持久化忽略。
 * - 静默期持久化到 SecureStorage（§10.5.4 跨冷启动不被 task killer 重置）。
 *
 * 本类不联 GitHub API（D6/D7 才接）；当前仅消费 [OtaManifest] 输入，便于先于网络调通 UX 与档位判定。
 *
 * ## 升级渠道优先级（v0.4.2 起）
 *
 * 1. **本服务器优先** —— 客户端用当前登录用户的 `serverBaseUrl` 拼
 *    `{baseUrl}/api/mobile/ota/manifest.json`。本服务器失败/无新版本 → 进入下一渠道。
 * 2. **GitHub Releases 兜底** —— `https://api.github.com/repos/{DEFAULT_OTA_REPO}/releases?per_page=10`，
 *    过滤 `draft=false`，按 `published_at` 取最新一条。
 * 3. **都失败** → 返回 [OtaCheckResult.NetworkError]，UI 不弹窗。
 *
 * 空 `localBaseUrl`（用户尚未配置服务器）→ 直接跳过本服务器 channel，走 GitHub。
 */
class OtaCheckUseCase(
    private val storage: SecureStorage,
    private val repo: OtaRepository = createOtaRepository(),
    private val repoName: String = com.mobius.momo.data.DEFAULT_OTA_REPO,
    private val localBaseUrl: String = "",
    private val localVersion: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { nowEpochMillis() },
) {

    /** 静默期到期时间戳的持久化 key（epoch millis，0 表示无记录）。 */
    private val silentUntilKey = "ota_silent_until_millis"

    /** 用户已忽略的版本号集合（JSON 数组字符串，单 key 存取）。 */
    private val ignoredKey = "ota_ignored_versions"

    /** 检查结果枚举（与 ThresholdEvaluator.Level 一一对应 + 网络/解析错误分支）。 */
    sealed interface OtaCheckResult {
        /** 无更新 / 远端 ≤ 本地 / 用户已忽略此版本。 */
        data object NoUpdate : OtaCheckResult
        /** 弹窗 4 档之一。 */
        data class Show(
            val level: ThresholdEvaluator.Level,
            val manifest: OtaManifest,
            val minSupportedVersion: String?,
            val reasonDisplay: String?,
            val advisoryId: String?,
            val hardBlockBypassable: Boolean,
            /** 与 [manifest] 同源 release 的 changelog 条目列表（来自 GitHub body 解析或本服务器 manifest）。 */
            val changelogItems: List<ChangelogItem> = emptyList(),
        ) : OtaCheckResult
        /** 元数据解析失败 / manifest 缺关键字段。 */
        data object Invalid : OtaCheckResult
        /** 网络不可达 / HTTP 错误 / 解析抛错。 */
        data class NetworkError(val message: String) : OtaCheckResult
    }

    /**
     * 入口：先本服务器后 GitHub 双渠道拉 manifest → 比对版本 → 判定档位 → 返回 UX 层需要的最小信息。
     *
     * 真实网络联调在 D6，本期 mock：[manifestProvider] 由 desktopTest 注入；默认 null 表示联 GitHub。
     *
     * [manifestProvider] 仍优先于网络（用于单元测试注入 fake manifest，不依赖外网）。
     */
    suspend fun run(manifestProvider: (suspend () -> OtaManifest?)? = null): OtaCheckResult =
        withContext(dispatcher) {
            // 测试注入 provider 默认不携带 changelog, 给空 list; 真实网络分支由 fetchManifestDualChannel 返回 (manifest, items).
            val (manifest, changelogItems) = if (manifestProvider != null) {
                val m = manifestProvider.invoke()
                if (m == null) return@withContext OtaCheckResult.NetworkError("manifest unavailable")
                m to emptyList()
            } else {
                fetchManifestDualChannel() ?: return@withContext OtaCheckResult.NetworkError("manifest unavailable")
            }

            // §5.5 强校验字段
            if (manifest.version.isBlank() || manifest.versionCode <= 0) {
                return@withContext OtaCheckResult.Invalid
            }

            val local = localVersion.ifBlank { "0.0.0" }
            val result = ThresholdEvaluator.evaluate(ThresholdEvaluator.Input(local, manifest))

            // 用户已忽略此版 → NoUpdate（§8.3）
            if (result.level == ThresholdEvaluator.Level.Normal && isIgnored(manifest.version)) {
                return@withContext OtaCheckResult.NoUpdate
            }

            // 静默期未到期 → NoUpdate（hard_block 静默期=0，不走该分支）
            if (result.level != ThresholdEvaluator.Level.HardBlock && result.level != ThresholdEvaluator.Level.NoUpdate) {
                if (!isSilentPeriodExpired(result.level)) {
                    return@withContext OtaCheckResult.NoUpdate
                }
            }

            return@withContext when (result.level) {
                ThresholdEvaluator.Level.NoUpdate,
                ThresholdEvaluator.Level.Invalid -> OtaCheckResult.Invalid
                else -> OtaCheckResult.Show(
                    level = result.level,
                    manifest = manifest,
                    minSupportedVersion = result.minSupportedVersion,
                    reasonDisplay = result.reasonDisplay,
                    advisoryId = result.advisoryId,
                    hardBlockBypassable = result.hardBlockBypassable,
                    changelogItems = changelogItems,
                )
            }
        }

    /**
     * 双渠道拉取：先本服务器 (`serverBaseUrl`) 后 GitHub Releases (`DEFAULT_OTA_REPO`)。
     *
     * 返回首个解析成功的 (manifest, changelogItems) 元组；都失败返回 null（让上层映射为
     * [OtaCheckResult.NetworkError]）。本服务器 channel 任何抛错由 [OtaRepository.fetchLocalManifest]
     * 内部吞掉 → 返回 null → 自动 fallback。GitHub channel 的 changelogItems 来自 release body 解析
     * （[com.mobius.momo.data.ChangelogParser]）；本服务器 channel 暂未携带 changelog_items 字段,
     * 返回空 list (UI 仅显示首条摘要时降级为兜底文案)。
     */
    private suspend fun fetchManifestDualChannel(): Pair<OtaManifest, List<ChangelogItem>>? {
        if (localBaseUrl.isNotBlank()) {
            val local = runCatching { repo.fetchLocalManifest(localBaseUrl) }.getOrNull()
            if (local != null && local.version.isNotBlank() && local.versionCode > 0) {
                return local to emptyList<ChangelogItem>()
            }
        }
        val releaseResult: Result<Pair<OtaManifest, List<ChangelogItem>>> = runCatching {
            val release = repo.fetchLatestRelease(repoName)
            val manifest: OtaManifest = release.manifest
                ?: throw IllegalStateException("release manifest is null")
            if (manifest.version.isBlank() || manifest.versionCode <= 0) {
                throw IllegalStateException("release manifest missing version/version_code")
            }
            manifest to (release.changelogItems ?: emptyList())
        }
        return releaseResult.getOrNull()
    }

    /** 用户点 [稍后]：刷新静默期到期时间戳。 */
    fun markDismissed(level: ThresholdEvaluator.Level) {
        if (level == ThresholdEvaluator.Level.HardBlock) return // 无静默期
        val until = now() + ThresholdEvaluator.silentPeriodMillis(level)
        storage.savePreference(silentUntilKey, until.toString())
    }

    /** 用户点 [忽略此版本]：仅 normal 档调用（advisory/strong_advisory/hard_block UI 上无此按钮）。 */
    fun markIgnored(version: String) {
        val existing = readIgnored().toMutableSet()
        existing.add(version)
        storage.savePreference(ignoredKey, existing.joinToString(","))
    }

    /** 用户在设置页清空忽略列表（v1.2 提供 UI，本期留接口）。 */
    fun clearIgnored() {
        storage.savePreference(ignoredKey, "")
    }

    fun readIgnored(): Set<String> =
        storage.getPreference(ignoredKey)
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private fun isIgnored(version: String): Boolean = version in readIgnored()

    private fun isSilentPeriodExpired(level: ThresholdEvaluator.Level): Boolean {
        val raw = storage.getPreference(silentUntilKey)?.toLongOrNull() ?: return true
        return now() >= raw
    }
}