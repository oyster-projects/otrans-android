package com.otrans.android.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OTransApi(
    private val baseUrl: String,
    private val token: String,
    private val client: OkHttpClient,
) {
    fun serverInfo(timeoutMs: Long): Pair<String, Long> {
        val response = execute(
            Request.Builder().url("$baseUrl/api/v1/server-info").build(),
            client.newBuilder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).build(),
        )
        val json = JSONObject(response)
        return json.getString("serverId") to json.optLong("protocolVersion")
    }

    fun pair(code: String, name: String): Pair<Device, String> {
        val json = postJson(
            "/api/v1/pair",
            JSONObject().put("code", code.trim()).put("name", name.trim()).put("platform", "android"),
            authenticated = false,
        )
        return parseDevice(json.getJSONObject("device")) to json.getString("token")
    }

    fun device(): Device = parseDevice(requestJson("/api/v1/devices/me"))

    fun transfers(): List<Transfer> {
        val array = requestJson("/api/v1/transfers?limit=100").getJSONArray("transfers")
        return buildList { for (index in 0 until array.length()) add(parseTransfer(array.getJSONObject(index))) }
    }

    fun deleteTransfer(id: String) {
        execute(authenticated(Request.Builder().url("$baseUrl/api/v1/transfers/$id").delete()).build())
    }

    fun createTransfer(text: String, files: List<PreparedFile>): Pair<String, Set<String>> {
        val items = JSONArray()
        if (text.isNotBlank()) {
            items.put(JSONObject().put("kind", "text").put("text", text.trim()).put("mimeType", "text/plain"))
        }
        files.forEach { file ->
            items.put(
                JSONObject().put("kind", "file")
                    .put("name", file.local.name)
                    .put("mimeType", file.local.mimeType)
                    .put("size", file.local.size)
                    .put("sha256", file.sha256),
            )
        }
        val title = when {
            files.isEmpty() -> "来自 Android 的文本"
            text.isNotBlank() -> "来自 Android：文本和 ${files.size} 个文件"
            files.size == 1 -> "来自 Android：${files.first().local.name}"
            else -> "来自 Android 的 ${files.size} 个文件"
        }
        val json = postJson(
            "/api/v1/transfers",
            JSONObject().put("clientTransferId", java.util.UUID.randomUUID().toString())
                .put("title", title).put("items", items),
        )
        val missing = json.getJSONArray("missingBlobs")
        val digests = buildSet { for (index in 0 until missing.length()) add(missing.getString(index)) }
        return json.getJSONObject("transfer").getString("id") to digests
    }

    fun uploadBlob(file: PreparedFile, body: RequestBody) {
        val request = authenticated(
            Request.Builder().url("$baseUrl/api/v1/blobs/${file.sha256}")
                .put(body)
                .header("Content-Type", file.local.mimeType),
        ).build()
        execute(request)
    }

    fun commitTransfer(id: String): Transfer = parseTransfer(postJson("/api/v1/transfers/$id/commit", null))

    fun openBlob(item: TransferItem): Response {
        val digest = requireNotNull(item.sha256)
        val encodedName = java.net.URLEncoder.encode(item.name ?: "file", Charsets.UTF_8.name())
        val request = authenticated(
            Request.Builder().url("$baseUrl/api/v1/blobs/$digest?name=$encodedName"),
        ).build()
        return client.newCall(request).execute().also { response ->
            if (!response.isSuccessful) {
                val message = responseError(response)
                response.close()
                error(message)
            }
        }
    }

    fun webSocketRequest(): Request {
        val ticket = postJson("/api/v1/ws-ticket", null).getString("ticket")
        val httpUrl = baseUrl.toHttpUrl()
        val wsScheme = if (httpUrl.isHttps) "wss" else "ws"
        val wsUrl = httpUrl.newBuilder().scheme(wsScheme).addPathSegments("api/v1/ws")
            .addQueryParameter("ticket", ticket).build()
        return Request.Builder().url(wsUrl).build()
    }

    private fun requestJson(path: String): JSONObject = JSONObject(
        execute(authenticated(Request.Builder().url("$baseUrl$path")).build()),
    )

    private fun postJson(path: String, body: JSONObject?, authenticated: Boolean = true): JSONObject {
        val content = body?.toString().orEmpty().toRequestBody("application/json".toMediaTypeOrNull())
        var builder = Request.Builder().url("$baseUrl$path").post(content)
        if (authenticated) builder = authenticated(builder)
        return JSONObject(execute(builder.build()))
    }

    private fun authenticated(builder: Request.Builder): Request.Builder {
        check(token.isNotBlank()) { "尚未配对，请先在设置中连接飞牛" }
        return builder.header("Authorization", "Bearer $token")
    }

    private fun execute(request: Request, using: OkHttpClient = client): String =
        using.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(responseError(response))
            response.body?.string().orEmpty()
        }

    private fun responseError(response: Response): String {
        if (response.code == 401) return "设备令牌无效，请重新配对"
        val raw = response.body?.string().orEmpty()
        return runCatching { JSONObject(raw).optString("error") }.getOrNull().takeUnless { it.isNullOrBlank() }
            ?: "服务返回 ${response.code}"
    }

    private fun parseDevice(json: JSONObject): Device = Device(
        id = json.getString("id"),
        name = json.optString("name"),
        cursor = json.optLong("cursor"),
    )

    private fun parseTransfer(json: JSONObject): Transfer {
        val sourceId = json.optString("sourceDeviceId")
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    TransferItem(
                        id = item.optString("id", "$index"),
                        kind = item.getString("kind"),
                        text = item.optNullableString("text"),
                        name = item.optNullableString("name"),
                        mimeType = item.optNullableString("mimeType"),
                        size = if (item.has("size") && !item.isNull("size")) item.getLong("size") else null,
                        sha256 = item.optNullableString("sha256"),
                    ),
                )
            }
        }
        return Transfer(
            id = json.getString("id"),
            sourceDeviceId = sourceId,
            title = json.optString("title", "未命名中转内容"),
            createdAt = json.optString("createdAt"),
            committedAt = json.optNullableString("committedAt"),
            items = items,
        )
    }
}

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
