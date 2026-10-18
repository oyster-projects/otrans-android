package com.otrans.android.ui

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.otrans.android.data.AppSettings
import com.otrans.android.data.LocalFile
import com.otrans.android.data.OTransApi
import com.otrans.android.data.PreparedFile
import com.otrans.android.data.ResolvedEndpoint
import com.otrans.android.data.SettingsStore
import com.otrans.android.data.Transfer
import com.otrans.android.data.TransferItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OTransUiState(
    val settings: AppSettings = AppSettings(),
    val paired: Boolean = false,
    val route: String = "offline",
    val status: String = "尚未连接",
    val transfers: List<Transfer> = emptyList(),
    val draftText: String = "",
    val draftFiles: List<LocalFile> = emptyList(),
    val busy: Boolean = false,
)

class OTransViewModel(context: Context) : ViewModel() {
    private val settingsStore = SettingsStore(context)
    private val resolver = context.contentResolver
    private val legacyDownloadsDirectory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "OTrans")
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(3, TimeUnit.MINUTES)
        .build()
    private val mutableState = MutableStateFlow(
        OTransUiState(settings = settingsStore.load(), paired = settingsStore.token().isNotBlank()),
    )
    val state: StateFlow<OTransUiState> = mutableState.asStateFlow()
    private var api: OTransApi? = null
    private var webSocket: WebSocket? = null
    private var socketWanted = false
    private var reconnectJob: Job? = null

    init {
        if (mutableState.value.paired) refresh()
    }

    fun setDraftText(value: String) = mutableState.update { it.copy(draftText = value) }

    fun addFiles(files: List<LocalFile>) = mutableState.update { current ->
        val known = current.draftFiles.map { it.uri }.toMutableSet()
        current.copy(draftFiles = current.draftFiles + files.filter { known.add(it.uri) })
    }

    fun removeFile(index: Int) = mutableState.update { current ->
        current.copy(draftFiles = current.draftFiles.filterIndexed { itemIndex, _ -> itemIndex != index })
    }

    fun clearDraft() = mutableState.update { it.copy(draftText = "", draftFiles = emptyList()) }

    fun saveSettings(settings: AppSettings) {
        val normalized = settings.copy(
            lanServerUrl = settings.lanServerUrl.trim().trimEnd('/'),
            wanServerUrl = settings.wanServerUrl.trim().trimEnd('/'),
            deviceName = settings.deviceName.trim().ifBlank { "Android 手机" },
        )
        settingsStore.save(normalized)
        api = null
        mutableState.update { it.copy(settings = normalized, status = "设置已保存") }
        if (mutableState.value.paired) refresh()
    }

    fun pair(settings: AppSettings, code: String, onSuccess: () -> Unit) = action("正在配对…") {
        require(code.isNotBlank()) { "请填写配对码" }
        val normalized = settings.copy(
            lanServerUrl = settings.lanServerUrl.trim().trimEnd('/'),
            wanServerUrl = settings.wanServerUrl.trim().trimEnd('/'),
            deviceName = settings.deviceName.trim().ifBlank { "Android 手机" },
            serverId = "",
        )
        val endpoint = resolve(normalized, "", authenticated = false)
        val pairApi = OTransApi(endpoint.baseUrl, "", client)
        val (device, token) = pairApi.pair(code, normalized.deviceName)
        val saved = normalized.copy(serverId = endpoint.serverId)
        settingsStore.save(saved)
        settingsStore.setToken(token)
        api = OTransApi(endpoint.baseUrl, token, client)
        mutableState.update {
            it.copy(settings = saved, paired = true, route = endpoint.route, status = "已配对：${device.name}")
        }
        withContext(Dispatchers.Main) { onSuccess() }
        refreshInternal()
        if (socketWanted) connectSocketInternal()
    }

    fun disconnect() {
        stopRealtime()
        settingsStore.setToken("")
        val settings = mutableState.value.settings.copy(serverId = "")
        settingsStore.save(settings)
        api = null
        mutableState.update {
            it.copy(settings = settings, paired = false, route = "offline", status = "已断开配对", transfers = emptyList())
        }
    }

    fun refresh() = action("正在拉取中转记录…") { refreshInternal() }

    fun send() = action("正在准备内容…") {
        val snapshot = mutableState.value
        require(snapshot.draftText.isNotBlank() || snapshot.draftFiles.isNotEmpty()) { "请先添加内容" }
        require(snapshot.draftFiles.size + (if (snapshot.draftText.isBlank()) 0 else 1) <= 64) {
            "一条中转记录最多包含 64 项"
        }
        val selectedApi = resolveApi()
        val prepared = snapshot.draftFiles.mapIndexed { index, file ->
            mutableState.update { it.copy(status = "正在校验 ${index + 1}/${snapshot.draftFiles.size}：${file.name}") }
            PreparedFile(file, sha256(file.uri))
        }
        val (transferId, missing) = selectedApi.createTransfer(snapshot.draftText, prepared)
        prepared.filter { it.sha256 in missing }.distinctBy { it.sha256 }.forEachIndexed { index, file ->
            mutableState.update { it.copy(status = "正在上传 ${index + 1}/${missing.size}：${file.local.name}") }
            selectedApi.uploadBlob(file, UriRequestBody(resolver, file.local))
        }
        selectedApi.commitTransfer(transferId)
        mutableState.update { it.copy(draftText = "", draftFiles = emptyList(), status = "已发送到中转站") }
        refreshInternal()
    }

    fun delete(transfer: Transfer) = action("正在删除…") {
        resolveApi().deleteTransfer(transfer.id)
        mutableState.update { it.copy(transfers = it.transfers.filterNot { value -> value.id == transfer.id }, status = "已同步删除") }
    }

    fun download(item: TransferItem) = action("正在保存 ${item.name ?: "文件"}…") {
        val name = safeFileName(item.name ?: "file")
        val expectedDigest = requireNotNull(item.sha256) { "文件记录缺少 SHA-256" }
        val expectedSize = requireNotNull(item.size) { "文件记录缺少大小" }
        val target = createDownloadTarget(name, item.mimeType ?: "application/octet-stream")
        try {
            resolveApi().openBlob(item).use { response ->
                val body = requireNotNull(response.body) { "服务没有返回文件内容" }
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                resolver.openOutputStream(target, "w")!!.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            total += read
                        }
                    }
                }
                check(total == expectedSize) { "文件大小校验失败" }
                check(digest.digest().toHex() == expectedDigest.lowercase()) { "文件完整性校验失败" }
            }
            publishDownload(target)
            mutableState.update { it.copy(status = "已保存到下载/OTrans：$name") }
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    fun startRealtime() {
        socketWanted = true
        viewModelScope.launch(Dispatchers.IO) { connectSocketInternal() }
    }

    fun stopRealtime() {
        socketWanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "app background")
        webSocket = null
    }

    private suspend fun refreshInternal() {
        val selectedApi = resolveApi()
        val transfers = selectedApi.transfers()
        mutableState.update { it.copy(transfers = transfers, status = "已拉取 ${transfers.size} 条中转记录") }
    }

    private suspend fun resolveApi(): OTransApi {
        api?.let { return it }
        val token = settingsStore.token()
        require(token.isNotBlank()) { "尚未配对，请先打开设置" }
        val endpoint = resolve(mutableState.value.settings, token, authenticated = true)
        val selected = OTransApi(endpoint.baseUrl, token, client)
        api = selected
        val updated = mutableState.value.settings.copy(serverId = endpoint.serverId)
        settingsStore.save(updated)
        mutableState.update { it.copy(settings = updated, route = endpoint.route) }
        return selected
    }

    private fun resolve(settings: AppSettings, token: String, authenticated: Boolean): ResolvedEndpoint {
        val candidates = listOf("lan" to settings.lanServerUrl, "wan" to settings.wanServerUrl)
            .filter { it.second.isNotBlank() }.distinctBy { it.second }
        require(candidates.isNotEmpty()) { "请至少填写一个飞牛服务地址" }
        val failures = mutableListOf<String>()
        candidates.forEach { (route, rawUrl) ->
            runCatching {
                val candidate = OTransApi(rawUrl.trim().trimEnd('/'), token, client)
                val (serverId, _) = candidate.serverInfo(if (route == "lan") 1_600 else 5_000)
                require(serverId.isNotBlank()) { "服务端未返回 serverId" }
                require(settings.serverId.isBlank() || settings.serverId == serverId) { "这不是已配对的飞牛服务器" }
                if (authenticated) candidate.device()
                return ResolvedEndpoint(rawUrl.trim().trimEnd('/'), serverId, route)
            }.onFailure { failures += "${if (route == "lan") "内网" else "外网"}：${it.message}" }
        }
        error("内外网均无法连接（${failures.joinToString("；")}）")
    }

    private fun connectSocketInternal() {
        if (!socketWanted || !mutableState.value.paired || webSocket != null) return
        runCatching {
            val selectedApi = kotlinx.coroutines.runBlocking { resolveApi() }
            webSocket = client.newWebSocket(selectedApi.webSocketRequest(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    viewModelScope.launch(Dispatchers.IO) { runCatching { refreshInternal() } }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    this@OTransViewModel.webSocket = null
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    this@OTransViewModel.webSocket = null
                    scheduleReconnect()
                }
            })
        }.onFailure { scheduleReconnect() }
    }

    private fun scheduleReconnect() {
        if (!socketWanted || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            delay(5_000)
            reconnectJob = null
            connectSocketInternal()
        }
    }

    private fun action(progress: String, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(busy = true, status = progress) }
            runCatching { block() }.onFailure { error ->
                mutableState.update { it.copy(status = error.message ?: "操作失败") }
            }
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun createDownloadTarget(name: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OTrans")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        }
        legacyDownloadsDirectory.mkdirs()
        return Uri.fromFile(File(legacyDownloadsDirectory, name))
    }

    private fun publishDownload(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    private fun safeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "file" }

    override fun onCleared() {
        stopRealtime()
        client.dispatcher.executorService.shutdown()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OTransViewModel(context.applicationContext) as T
    }
}

private class UriRequestBody(
    private val resolver: ContentResolver,
    private val file: LocalFile,
) : RequestBody() {
    override fun contentType() = file.mimeType.toMediaTypeOrNull()
    override fun contentLength(): Long = file.size
    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(file.uri)!!.source().use { source -> sink.writeAll(source) }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
