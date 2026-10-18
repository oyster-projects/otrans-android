package com.otrans.android.data

import android.net.Uri

data class AppSettings(
    val lanServerUrl: String = "http://192.168.1.10:18990",
    val wanServerUrl: String = "",
    val serverId: String = "",
    val deviceName: String = "Android 手机",
)

data class Device(
    val id: String,
    val name: String,
    val cursor: Long,
)

data class TransferItem(
    val id: String,
    val kind: String,
    val text: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val sha256: String? = null,
)

data class Transfer(
    val id: String,
    val sourceDeviceId: String,
    val title: String,
    val createdAt: String,
    val committedAt: String?,
    val items: List<TransferItem>,
)

data class LocalFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
)

data class PreparedFile(
    val local: LocalFile,
    val sha256: String,
)

data class ResolvedEndpoint(
    val baseUrl: String,
    val serverId: String,
    val route: String,
)
