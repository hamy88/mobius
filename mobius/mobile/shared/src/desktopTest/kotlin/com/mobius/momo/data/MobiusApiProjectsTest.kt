package com.mobius.momo.data

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 项目钻取第二层(listIssues)的 wire 契约守护：钉死实际发出的方法、路径
 * (GET /api/projects/{id}/issues) 与 Authorization: Bearer 头，并验证返回的 Issue
 * JSON 数组被正确反序列化(含 project_id snake_case 映射)，防止后续重构悄悄改了格式。
 */
class MobiusApiProjectsTest {

    @Test
    fun listIssuesGetsProjectIssuesWithBearerAuth() = runBlocking {
        CaptureServer(
            response = """
                [{"id":"iss-1","project_id":"proj-9","title":"修登录Bug","description":"","status":"active","session_count":2,"last_active":"2026-07-14T08:00:00Z"},
                 {"id":"iss-2","project_id":"proj-9","title":"加项目tab","description":"新需求","status":"completed","session_count":0}]
            """.trimIndent(),
        ).use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            val issues = api.listIssues("proj-9")
            val request = server.awaitRequest()
            api.close()

            assertEquals("GET", request.method)
            assertEquals("/api/projects/proj-9/issues", request.path)
            assertEquals("Bearer bearer-abc", request.headers["authorization"])
            assertEquals(2, issues.size)
            assertEquals("iss-1", issues[0].id)
            assertEquals("proj-9", issues[0].projectId)
            assertEquals("修登录Bug", issues[0].title)
            assertEquals("active", issues[0].status)
            assertEquals(2, issues[0].sessionCount)
            assertEquals("iss-2", issues[1].id)
            assertEquals("completed", issues[1].status)
        }
    }

    @Test
    fun projectsGetsProjectListWithBearerAuth() = runBlocking {
        CaptureServer(
            response = """[{"id":"proj-9","name":"小莫助理","description":"移动端","issue_count":3,"last_active":"2026-07-14T09:00:00Z","starred":true}]""",
        ).use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-xyz"),
                onUnauthorized = {},
            )
            val projects = api.projects()
            val request = server.awaitRequest()
            api.close()

            assertEquals("GET", request.method)
            assertEquals("/api/projects/", request.path)
            assertEquals("Bearer bearer-xyz", request.headers["authorization"])
            assertEquals(1, projects.size)
            assertEquals("proj-9", projects[0].id)
            assertEquals("小莫助理", projects[0].name)
            assertEquals(3, projects[0].issueCount)
            assertTrue(projects[0].starred == true)
        }
    }

    // 用真实后端响应(含 pinned/use_worktree/starred 等 0/1 整数 + access 对象 + 缺 assistant_role)
    // 验证 Issue/Session 反序列化不抛异常。曾因 Issue.pinned 声明为 Boolean? 而后端返回整数 0,
    // 导致整列表反序列化失败、issue 列表为空(进而 session 也进不去)。此测试守护该回归。
    @Test
    fun listIssuesParsesRealBackendShapeWithIntegerFlags() = runBlocking {
        CaptureServer(
            // 后端 GET /api/projects/:id/issues 真实结构节选:
            response = """
                [{"id":"549a148c","project_id":"d78c6e39","title":"0714功能开发","description":"0714功能开发","status":"active","created_by":"mengxiaofei","created_at":"2026-07-14T07:31:27.360Z","last_active":"2026-07-14T07:32:28.233Z","message_count":1,"completed_at":null,"pinned":0,"selected_skills":[],"excluded_skills":[],"use_worktree":1,"worktree_branch":"549a148c","visibility":"inherit","is_planning":0,"created_by_name":"mengxiaofei","starred":0,"session_count":1,"access":{"visibility":"inherit","allow_user_ids":[],"allow_group_ids":[]},"can_manage":true}]
            """.trimIndent(),
        ).use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            val issues = api.listIssues("d78c6e39")
            api.close()

            assertEquals(1, issues.size)
            assertEquals("549a148c", issues[0].id)
            assertEquals("d78c6e39", issues[0].projectId)
            assertEquals("0714功能开发", issues[0].title)
            assertEquals("active", issues[0].status)
            assertEquals(1, issues[0].sessionCount)
            assertEquals(1, issues[0].messageCount)
            assertEquals("mengxiaofei", issues[0].createdByName)
        }
    }

    @Test
    fun issueSessionsParsesRealBackendShape() = runBlocking {
        CaptureServer(
            // 后端 GET /api/issues/:id/sessions/ 真实结构节选(无 assistant_role; job_* 为布尔):
            response = """
                [{"session_id":"9b80172e","issue_id":"6a388790","project_id":"d78c6e39","scope_type":"issue","name":"优化 2026-06-27","description":"描述","model":"glm-5.2","use_proxy":0,"status":"active","agent_status":"idle","created_at":"2026-06-27T06:02:48.458Z","last_active":"2026-06-27T08:50:57.110Z","message_count":24,"turn_count":0,"user_display_name":"mengxiaofei","raw_entry_count":30,"job_accomplished":false,"job_failed":false}]
            """.trimIndent(),
        ).use { server ->
            val api = MobiusApi(
                baseUrl = server.baseUrl,
                storage = TestTokenStorage("bearer-abc"),
                onUnauthorized = {},
            )
            val sessions = api.issueSessions("6a388790")
            api.close()

            assertEquals(1, sessions.size)
            assertEquals("9b80172e", sessions[0].sessionId)
            assertEquals("6a388790", sessions[0].issueId)
            assertEquals("idle", sessions[0].agentStatus)
            assertEquals(24, sessions[0].messageCount)
            assertEquals(30, sessions[0].rawEntryCount)
            assertEquals(false, sessions[0].jobFailed)
        }
    }

    private class TestTokenStorage(private val token: String?) : SecureStorage {
        override fun saveToken(token: String) = Unit
        override fun getToken(): String? = token
        override fun savePreference(key: String, value: String) = Unit
        override fun getPreference(key: String): String? = null
        override fun clear() = Unit
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class CaptureServer(private val response: String) : Closeable {
        private val server = ServerSocket(0)
        private val requests = ArrayBlockingQueue<CapturedRequest>(1)
        private val worker = thread(start = true, name = "projects-capture") {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val requestLine = readAsciiLinePr(input) ?: return@use
                val headers = buildMap {
                    while (true) {
                        val line = readAsciiLinePr(input) ?: break
                        if (line.isEmpty()) break
                        val sep = line.indexOf(':')
                        if (sep > 0) put(line.substring(0, sep).lowercase(), line.substring(sep + 1).trim())
                    }
                }
                val body = headers["content-length"]?.toIntOrNull()?.let { readExactlyPr(input, it) } ?: ByteArray(0)
                val parts = requestLine.split(" ")
                requests.put(CapturedRequest(parts[0], parts[1], headers, body))
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(resp.toByteArray(StandardCharsets.ISO_8859_1) + bytes)
            }
        }

        val baseUrl: String = "http://127.0.0.1:${server.localPort}"

        fun awaitRequest(): CapturedRequest =
            requests.poll(10, TimeUnit.SECONDS) ?: fail("projects request was not captured")

        override fun close() {
            runCatching { server.close() }
            runCatching { worker.join(1_000L) }
        }

        private fun readAsciiLinePr(input: java.io.InputStream): String? {
            val out = ByteArrayOutputStream()
            var sawCr = false
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (out.size() == 0 && !sawCr) return null
                    if (sawCr) out.write('\r'.code)
                    return out.toString(StandardCharsets.ISO_8859_1)
                }
                if (sawCr) {
                    if (b == '\n'.code) break
                    out.write('\r'.code)
                    sawCr = false
                }
                if (b == '\r'.code) sawCr = true else out.write(b)
            }
            return out.toString(StandardCharsets.ISO_8859_1)
        }

        private fun readExactlyPr(input: java.io.InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read == -1) error("unexpected end of stream")
                offset += read
            }
            return bytes
        }
    }
}
