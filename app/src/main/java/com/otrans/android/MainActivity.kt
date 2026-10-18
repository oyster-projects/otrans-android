package com.otrans.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.otrans.android.data.AppSettings
import com.otrans.android.data.LocalFile
import com.otrans.android.data.Transfer
import com.otrans.android.data.TransferItem
import com.otrans.android.ui.OTransUiState
import com.otrans.android.ui.OTransViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: OTransViewModel by viewModels { OTransViewModel.Factory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OTransTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                AppLifecycle(viewModel)
                OTransApp(state, viewModel)
            }
        }
    }
}

@Composable
private fun AppLifecycle(viewModel: OTransViewModel) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.refresh()
                    viewModel.startRealtime()
                }
                Lifecycle.Event.ON_STOP -> viewModel.stopRealtime()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun OTransTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF2563EB),
            background = Color(0xFFF5F7FB),
            surface = Color.White,
        ),
        content = content,
    )
}

@Composable
private fun OTransApp(state: OTransUiState, viewModel: OTransViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsScreen(state, viewModel, onBack = { showSettings = false })
    } else {
        HomeScreen(state, viewModel, onSettings = { showSettings = true })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: OTransUiState, viewModel: OTransViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        viewModel.addFiles(uris.mapNotNull { context.localFile(it) })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("OTrans", fontWeight = FontWeight.Bold); Text("自己的 NAS，就是跨设备中转站", style = MaterialTheme.typography.labelSmall) } },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StatusCard(state)
            }
            item {
                ComposerCard(
                    state = state,
                    onText = viewModel::setDraftText,
                    onClipboard = { readClipboard(context, state, viewModel) },
                    onFiles = { picker.launch(arrayOf("*/*")) },
                    onRemove = viewModel::removeFile,
                    onClear = viewModel::clearDraft,
                    onSend = viewModel::send,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("中转站记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("进入应用只拉取列表，文件由你点击后保存", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    IconButton(onClick = viewModel::refresh, enabled = state.paired && !state.busy) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                }
            }
            if (state.transfers.isEmpty()) {
                item { Text(if (state.paired) "暂时没有中转记录" else "请先打开设置并配对", color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(24.dp)) }
            } else {
                items(state.transfers, key = { it.id }) { transfer ->
                    TransferCard(transfer, state.busy, viewModel)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: OTransUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.settings.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            val label = when (state.route) { "lan" -> "内网"; "wan" -> "外网"; else -> if (state.paired) "离线" else "未配对" }
            Text(label, color = if (state.route == "lan") Color(0xFF15803D) else MaterialTheme.colorScheme.primary)
            if (state.busy) CircularProgressIndicator(Modifier.padding(start = 10.dp).size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ComposerCard(
    state: OTransUiState,
    onText: (String) -> Unit,
    onClipboard: () -> Unit,
    onFiles: () -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("待发送内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear, enabled = state.draftText.isNotBlank() || state.draftFiles.isNotEmpty()) { Text("清空") }
            }
            OutlinedTextField(
                value = state.draftText,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("输入文本，或读取一次剪贴板") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClipboard, modifier = Modifier.weight(1f)) { Icon(Icons.Default.ContentCopy, null); Text("读取剪贴板") }
                OutlinedButton(onClick = onFiles, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Text("选择文件") }
            }
            state.draftFiles.forEachIndexed { index, file ->
                Row(Modifier.fillMaxWidth().background(Color(0xFFF5F7FB), RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(file.size), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    IconButton(onClick = { onRemove(index) }) { Icon(Icons.Default.Delete, "移除") }
                }
            }
            Button(onClick = onSend, modifier = Modifier.fillMaxWidth().height(48.dp), enabled = state.paired && !state.busy && (state.draftText.isNotBlank() || state.draftFiles.isNotEmpty())) {
                Text("发送到中转站")
            }
        }
    }
}

@Composable
private fun TransferCard(transfer: Transfer, busy: Boolean, viewModel: OTransViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(transfer.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(transfer.committedAt ?: transfer.createdAt), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                IconButton(onClick = { confirmDelete = true }, enabled = !busy) { Icon(Icons.Default.Delete, "删除", tint = Color(0xFFDC2626)) }
            }
            transfer.items.forEach { item -> TransferItemRow(item, busy, viewModel) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条中转记录？") },
            text = { Text("删除会同步到全部已配对设备；需要保留的文件请先另存。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(transfer) }) { Text("删除", color = Color.Red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TransferItemRow(item: TransferItem, busy: Boolean, viewModel: OTransViewModel) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF5F7FB), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (item.kind == "text") item.text.orEmpty() else item.name ?: "未命名文件", maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.kind == "file") Text(formatBytes(item.size ?: 0), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
        IconButton(
            onClick = {
                if (item.kind == "text") {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("OTrans", item.text.orEmpty()))
                } else viewModel.download(item)
            },
            enabled = !busy,
        ) { Icon(if (item.kind == "text") Icons.Default.ContentCopy else Icons.Default.Download, if (item.kind == "text") "复制" else "保存") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(state: OTransUiState, viewModel: OTransViewModel, onBack: () -> Unit) {
    var lanUrl by remember(state.settings) { mutableStateOf(state.settings.lanServerUrl) }
    var wanUrl by remember(state.settings) { mutableStateOf(state.settings.wanServerUrl) }
    var deviceName by remember(state.settings) { mutableStateOf(state.settings.deviceName) }
    var pairCode by remember { mutableStateOf("") }
    val settings = AppSettings(lanUrl, wanUrl, state.settings.serverId, deviceName)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("连接地址", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            item { Text(state.status, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            item { OutlinedTextField(lanUrl, { lanUrl = it }, Modifier.fillMaxWidth(), label = { Text("内网地址") }, placeholder = { Text("http://192.168.1.10:18990") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { OutlinedTextField(wanUrl, { wanUrl = it }, Modifier.fillMaxWidth(), label = { Text("外网地址（可选）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(), label = { Text("设备名称") }) }
            item { Button(onClick = { viewModel.saveSettings(settings) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("保存设置") } }
            item { Spacer(Modifier.height(8.dp)); Text("设备配对", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            item { OutlinedTextField(pairCode, { pairCode = it }, Modifier.fillMaxWidth(), label = { Text("10 分钟临时配对码") }, visualTransformation = PasswordVisualTransformation()) }
            item {
                if (state.paired) {
                    OutlinedButton(onClick = viewModel::disconnect, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("断开当前配对", color = Color.Red) }
                } else {
                    Button(onClick = { viewModel.pair(settings, pairCode, onBack) }, Modifier.fillMaxWidth(), enabled = !state.busy && pairCode.isNotBlank()) { Text("使用配对码连接") }
                }
            }
            item { Text("令牌使用 Android Keystore 加密保存。内网可用时优先内网，失败后自动切换外网。", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
        }
    }
}

private fun readClipboard(context: Context, state: OTransUiState, viewModel: OTransViewModel) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = clipboard.primaryClip ?: return
    val texts = mutableListOf<String>()
    val files = mutableListOf<LocalFile>()
    for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        item.uri?.let { uri -> context.localFile(uri)?.let(files::add) }
            ?: item.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
    }
    if (texts.isNotEmpty()) viewModel.setDraftText(listOf(state.draftText, texts.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n"))
    viewModel.addFiles(files)
}

private fun Context.localFile(uri: Uri): LocalFile? = runCatching {
    var name = uri.lastPathSegment ?: "file"
    var size = 0L
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor: Cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    if (size <= 0) size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0
    LocalFile(uri, name, contentResolver.getType(uri) ?: "application/octet-stream", size)
}.getOrNull()

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun formatTime(value: String): String = runCatching {
    OffsetDateTime.parse(value).toLocalDateTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}.getOrDefault(value)
