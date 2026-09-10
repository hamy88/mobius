package com.mobius.momo.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.prefs.Preferences
import javax.swing.JFileChooser

actual fun createSecureStorage(): SecureStorage = DesktopSecureStorage()

actual fun createFilePicker(): FilePicker = object : FilePicker {
    override fun pickFiles(
        maxFiles: Int,
        onResult: (List<PickedFile>) -> Unit,
        onError: (String) -> Unit,
    ) {
        runCatching {
            val chooser = JFileChooser().apply {
                isMultiSelectionEnabled = maxFiles > 1
                fileSelectionMode = JFileChooser.FILES_ONLY
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                onResult(emptyList())
                return@runCatching
            }
            val files = if (chooser.isMultiSelectionEnabled) {
                chooser.selectedFiles.toList()
            } else {
                listOfNotNull(chooser.selectedFile)
            }
            onResult(
                files.take(maxFiles).map { file ->
                    PickedFile(
                        name = file.name,
                        mimeType = Files.probeContentType(file.toPath()).orEmpty().ifBlank {
                            inferMimeTypeFromExtension(file.name)
                        },
                        bytes = file.readBytes(),
                    )
                },
            )
        }.onFailure { onError(it.message ?: "读取附件失败") }
    }
}

private fun inferMimeTypeFromExtension(name: String): String =
    when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }

private class DesktopSecureStorage : SecureStorage {
    private val prefs = Preferences.userRoot().node("com.mobius.momo")
    private val tokenStoreFile = desktopTokenStoreFile()

    override fun saveToken(token: String) {
        updateTokenProperties { it.setProperty(TOKEN_KEY, token) }
        prefs.remove(TOKEN_KEY)
    }

    override fun getToken(): String? =
        readTokenProperties().getProperty(TOKEN_KEY)

    override fun saveTokenMetadata(metadata: StoredTokenMetadata) {
        updateTokenProperties {
            it.setProperty(TOKEN_BASE_URL_KEY, metadata.baseUrl)
            it.setProperty(TOKEN_SAVED_AT_KEY, metadata.savedAtEpochMillis.toString())
            it.setProperty(TOKEN_STORAGE_VERSION_KEY, metadata.storageVersion.toString())
        }
    }

    override fun getTokenMetadata(): StoredTokenMetadata? {
        val props = readTokenProperties()
        return storedTokenMetadataFromStrings(
            baseUrl = props.getProperty(TOKEN_BASE_URL_KEY),
            savedAtEpochMillis = props.getProperty(TOKEN_SAVED_AT_KEY),
            storageVersion = props.getProperty(TOKEN_STORAGE_VERSION_KEY),
        )
    }

    override fun savePreference(key: String, value: String) {
        prefs.put("pref.$key", value)
    }

    override fun getPreference(key: String): String? = prefs.get("pref.$key", null)

    override fun clear() {
        updateTokenProperties { props ->
            TOKEN_PROPERTY_KEYS.forEach { props.remove(it) }
        }
        TOKEN_PROPERTY_KEYS.forEach { prefs.remove(it) }
    }

    @Synchronized
    private fun updateTokenProperties(update: (Properties) -> Unit) {
        val props = readTokenProperties()
        update(props)
        writeTokenProperties(props)
    }

    private fun readTokenProperties(): Properties {
        val props = Properties()
        if (Files.exists(tokenStoreFile)) {
            Files.newInputStream(tokenStoreFile).use { props.load(it) }
        }
        return props
    }

    private fun writeTokenProperties(props: Properties) {
        if (props.isEmpty()) {
            Files.deleteIfExists(tokenStoreFile)
            return
        }
        Files.createDirectories(tokenStoreFile.parent)
        Files.newOutputStream(tokenStoreFile).use { props.store(it, "Momo auth storage") }
    }

    private fun desktopTokenStoreFile(): Path {
        val home = Paths.get(System.getProperty("user.home") ?: ".")
        val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
        val dir = when {
            osName.contains("mac") -> home.resolve("Library").resolve("Application Support").resolve("MomoAssistant")
            osName.contains("win") -> {
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                (appData ?: home.resolve("AppData").resolve("Roaming")).resolve("MomoAssistant")
            }
            else -> {
                val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                (configHome ?: home.resolve(".config")).resolve("momo-assistant")
            }
        }
        return dir.resolve("auth.properties")
    }

    private companion object {
        const val TOKEN_KEY = "token"
        const val TOKEN_BASE_URL_KEY = "token.baseUrl"
        const val TOKEN_SAVED_AT_KEY = "token.savedAtEpochMillis"
        const val TOKEN_STORAGE_VERSION_KEY = "token.storageVersion"
        val TOKEN_PROPERTY_KEYS = listOf(
            TOKEN_KEY,
            TOKEN_BASE_URL_KEY,
            TOKEN_SAVED_AT_KEY,
            TOKEN_STORAGE_VERSION_KEY,
        )
    }
}

actual fun createMobiusHttpClient(
    onUnauthorized: suspend () -> Unit,
): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true })
    }
    install(Logging) {
        level = LogLevel.INFO
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000L
        requestTimeoutMillis = 120_000L
        socketTimeoutMillis = 120_000L
    }
    engine {
        config {
            retryOnConnectionFailure(true)
        }
    }
    defaultRequest {
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
    HttpResponseValidator {
        validateResponse { response ->
            if (response.status.value == 401) onUnauthorized()
        }
    }
}

actual fun platformBuildBaseUrl(): String =
    System.getProperty("momo.base.url")?.takeIf { it.isNotBlank() }
        ?: System.getenv("MOMO_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://cloud-17.agent-matrix.com"

// Desktop: 无系统导航栏, 无需额外 padding.
actual fun platformBottomTabPaddingDp(): Float = 0f

// Desktop 版本: 可由 -Dmomo.app.version 注入, 否则读不到就返回空(UI 显示 "v")。
actual fun platformAppVersion(): String =
    System.getProperty("momo.app.version")?.takeIf { it.isNotBlank() }
        ?: System.getenv("MOMO_APP_VERSION")?.takeIf { it.isNotBlank() }
        ?: "0.1.4"

actual fun nowShortTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

actual fun nowIsoTime(): String = java.time.Instant.now().toString()

actual fun parseBackendTimeMillis(value: String?): Long? {
    val raw = value?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
}

actual fun formatBackendTime(value: String?): String {
    val raw = value?.takeIf { it.isNotBlank() } ?: return ""
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull() ?: return ""
    // 全局统一北京时间（CST, UTC+8）+ 24 小时制 (Issue 8f64748a)
    val zone = java.time.ZoneId.of("Asia/Shanghai")
    val zdt = instant.atZone(zone)
    val now = java.time.ZonedDateTime.now(zone)
    val time = "${zdt.hour.toString().padStart(2, '0')}:${zdt.minute.toString().padStart(2, '0')}"
    val today = now.toLocalDate()
    val date = zdt.toLocalDate()
    return when {
        date.isEqual(today) -> time
        date.isEqual(today.minusDays(1)) -> "昨天 $time"
        else -> "${zdt.monthValue}月${zdt.dayOfMonth}日 $time"
    }
}
