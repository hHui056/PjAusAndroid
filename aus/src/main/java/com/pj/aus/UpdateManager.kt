package com.pj.aus

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.alibaba.fastjson.JSON
import com.pj.aus.entity.VersionInfo
import com.pj.aus.listener.UpdateListener
import com.pj.aus.util.InstallPermissionHelper
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat

/**
 * Create By hHui on 2026/5/29 14:24
 *
 * @description
 */
class UpdateManager private constructor(private val context: Context) {
    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UpdateManager? = null

        fun init(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val updateScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentListener: UpdateListener? = null
    private var fileProviderAuthority: String = ""
    private var checkUrl: String = ""
    private var packageName: String = ""
    private var extParams: Map<String, String>? = null

    // 进度对话框相关（内置默认UI）
    private var progressDialog: AlertDialog? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadPercentText: TextView? = null
    private var pendingApkFile: File? = null   // 用于权限授权后继续安装

    /**
     * 设置服务器检查更新地址
     */
    fun setCheckUrl(url: String): UpdateManager {
        this.checkUrl = url
        return this
    }

    /**
     * 设置升级包名
     */
    fun setPackageName(name: String): UpdateManager {
        this.packageName = name
        return this
    }

    /**
     * 设置文件提供者
     */
    fun setFileProviderAuthority(authority: String): UpdateManager {
        this.fileProviderAuthority = authority
        return this
    }

    /**
     * 设置扩展参数
     */
    fun setExtParams(params: Map<String, String>): UpdateManager {
        this.extParams = params
        return this
    }

    /**
     * 开始检查更新
     * @param listener 回调监听
     * @param showDefaultProgressUI 是否使用库内置的下载进度对话框（默认true）
     */
    fun checkUpdate(activity: FragmentActivity, listener: UpdateListener, showDefaultProgressUI: Boolean = true) {
        this.currentListener = listener
        if (this.packageName.isEmpty()) {
            listener.onCheckFailed("未设置升级包名 请调用 setPackageName()")
            return
        }
        if (checkUrl.isEmpty()) {
            listener.onCheckFailed("检查更新地址未设置，请调用 setCheckUrl()")
            return
        }
        updateScope.launch {
            val updateInfo = fetchUpdateInfo(extParams)
            if (updateInfo == null) {
                listener.onCheckFailed("获取更新信息失败，请检查网络或服务器返回格式")
                return@launch
            }
            val localVersionCode = getLocalVersionCode()
            if (updateInfo.versionCode > localVersionCode) {
                listener.onNewVersionFound(updateInfo)
                if (showDefaultProgressUI) {
                    showUpdateConfirmDialog(activity, updateInfo)
                }
            } else {
                listener.onAlreadyLatestVersion()
            }
        }
    }

    private suspend fun fetchUpdateInfo(extParams: Map<String, String>? = null): VersionInfo? = withContext(Dispatchers.IO) {
        var realRequestUrl = "${checkUrl}/aus/check/${packageName}/${getLocalVersionCode()}"
        if (extParams != null) { //有请求参数
            val sb = StringBuffer(realRequestUrl)
            sb.append("?")
            extParams.forEach {
                sb.append("&${it.key}=${it.value}")
            }
            realRequestUrl = sb.toString().replaceFirst("&", "")
        }
        val request = Request.Builder().url(realRequestUrl).get().build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = response.body?.string() ?: return@withContext null
            JSON.parseObject(json, VersionInfo::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getLocalVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
    }

    private fun showUpdateConfirmDialog(activity: FragmentActivity, updateInfo: VersionInfo) {
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${updateInfo.versionCode}")
            .setMessage(updateInfo.changeLog)
            .setPositiveButton("立即更新") { _, _ ->
                if (updateInfo.ApkUrl != null) {
                    startDownload(activity, updateInfo.ApkUrl!!)
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(activity: FragmentActivity, apkUrl: String) {
        // 显示内置进度对话框
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.downloadProgressBar)
        downloadPercentText = dialogView.findViewById(R.id.tvDownloadPercent)

        progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在下载更新")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                currentListener?.onDownloadFailed("用户取消下载")
            }
            .show()

        updateScope.launch {
            val downloader = ApkDownloader(context)
            val result = downloader.downloadApk(apkUrl) { downloaded, total, done ->
                if (done) {
                    // 下载完成，关闭对话框（安装放在外面）
                    progressDialog?.dismiss()
                    currentListener?.onDownloadComplete()
                } else {
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    downloadProgressBar?.progress = percent
                    downloadPercentText?.text = "$percent% (${formatSize(downloaded)}/${formatSize(total)})"
                    currentListener?.onDownloadProgress(percent, downloaded, total)
                }
            }
            if (result != null) {
                // 下载成功，进行权限检查和安装
                installApkWithPermissionCheck(activity, result)
            } else {
                progressDialog?.dismiss()
                currentListener?.onDownloadFailed("下载失败，请重试")
            }
        }
    }


    private fun installApkWithPermissionCheck(activity: FragmentActivity, apkFile: File?) {
        if (apkFile == null || !apkFile.exists()) {
            currentListener?.onDownloadFailed("APK 文件不存在")
            return
        }
        pendingApkFile = apkFile
        val permissionHelper = InstallPermissionHelper(activity)
        permissionHelper.checkAndRequestPermission { granted ->
            if (granted) {
                val installed = performInstallApk(activity, pendingApkFile!!)
                if (!installed) {
                    currentListener?.onDownloadFailed("安装失败")
                } else {
                    currentListener?.onInstallPermissionResult(true)
                }
            } else {
                currentListener?.onInstallPermissionResult(false)
                currentListener?.onDownloadFailed("缺少安装未知来源应用的权限，请手动授权后重试")
            }
            pendingApkFile = null
        }
    }

    private fun performInstallApk(activity: FragmentActivity, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                return false
            }
        }
        val authority = if (fileProviderAuthority.isNotEmpty()) fileProviderAuthority else "${activity.packageName}.fileprovider"
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(activity, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        return true
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#.##").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    // 内部下载器
    private inner class ApkDownloader(private val context: Context) {
        suspend fun downloadApk(url: String, onProgress: (downloaded: Long, total: Long, done: Boolean) -> Unit): File? = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()
            val fileName = "app_update_${System.currentTimeMillis()}.apk"
            val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            destFile.parentFile?.mkdirs()

            val source = body.source()
            val fos = FileOutputStream(destFile)
            val buffer = ByteArray(8192)
            var totalRead = 0L
            var bytesRead: Int
            while (source.read(buffer).also { bytesRead = it } != -1) {
                fos.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                withContext(Dispatchers.Main) {
                    onProgress(totalRead, contentLength, totalRead == contentLength)
                }
            }
            fos.close()
            source.close()
            return@withContext destFile
        }
    }

    fun release() {
        updateScope.cancel()
        progressDialog?.dismiss()
        currentListener = null
    }
}