package com.mobius.momo.data

/**
 * OTA 网络/解析错误统一类型（§6 + §10.5.5 失败兜底）。
 *
 * 设计意图：
 * - 调用方 [com.mobius.momo.viewmodel.OtaCheckUseCase] 通过 try/catch 捕获，
 *   按错误子类型映射到 UI 层 Toast / 静默期延长。
 * - 抛错而非返回 Result 是为了不污染正常返回值签名，调用方在 `runCatching { ... }` 里直接捕获。
 * - `NetworkError.Timeout` 单独标记，便于调用方执行 §6 的 24h 静默策略。
 */
sealed class OtaError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** 网络超时（>10s）。§10.5.4 触发后客户端延长静默期至 24h。 */
    class Timeout(cause: Throwable? = null) : OtaError("ota request timeout", cause) {
        override fun toString(): String = "OtaError.Timeout"
    }

    /** 资源不存在（HTTP 404）。例如 release tag 未找到 / manifest.json 路径错。 */
    class NotFound(cause: Throwable? = null) : OtaError("ota resource not found", cause) {
        override fun toString(): String = "OtaError.NotFound"
    }

    /** 其他网络错误（连接失败 / DNS / 5xx / 限流 429 / TLS）。 */
    class Other(message: String, cause: Throwable? = null) : OtaError(message, cause)

    /** 响应解析失败（HTTP 200 但 body 不是合法 JSON / 缺 §5.5 强校验字段）。 */
    class ParseError(message: String, cause: Throwable? = null) : OtaError(message, cause)
}