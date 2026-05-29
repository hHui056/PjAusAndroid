package com.pj.aus

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.alibaba.fastjson.JSON
import com.pj.aus.entity.VersionInfo
import com.pj.aus.listener.UpdateListener
import com.pj.aus.util.InstallPermissionHelper
import com.pj.aus.util.Md5Util
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import kotlin.coroutines.resume

/**
 * Create By hHui on 2026/5/29 14:24
 *
 * @description 应用更新管理器
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
    private var currentDownloadJob: Job? = null // 当前下载任务的协程Job

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
                if (updateInfo.ApkUrl.isNotEmpty()) {
                    startDownload(activity, updateInfo)
                }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(activity: FragmentActivity, versionInfo: VersionInfo) {
        val apkFile = getApkFile(versionInfo.versionCode)
        if (apkFile.exists() && apkFile.length() > 0) {
            if (Md5Util.verifyMd5(apkFile, versionInfo.Md5Hash)) {
                installApkWithPermissionCheck(activity, apkFile)
                return
            } else {
                apkFile.delete()
            }
        }
        // 显示内置进度对话框
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.downloadProgressBar)
        downloadPercentText = dialogView.findViewById(R.id.tvDownloadPercent)

        progressDialog = AlertDialog.Builder(activity)
            .setTitle("正在下载更新")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { dialog, _ ->
                // 取消下载任务
                currentDownloadJob?.cancel()
                dialog.dismiss()
                currentListener?.onDownloadFailed("用户取消下载")
            }
            .show()

        // 启动下载任务并保存Job引用
        currentDownloadJob = updateScope.launch {
            val downloader = ApkDownloader(context)
            val result = downloader.downloadApk(versionInfo) { downloaded, total, done ->
                if (done) {
                    // 下载完成，关闭对话框
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
                // 下载成功 → 校验MD5
                if (Md5Util.verifyMd5(result, versionInfo.Md5Hash)) {
                    installApkWithPermissionCheck(activity, result)
                } else {
                    progressDialog?.dismiss()
                    result.delete() // 删除损坏文件
                    currentListener?.onDownloadFailed("文件校验失败，安装包已损坏")
                    showMd5MismatchDialog(activity)
                }
            } else {
                // 下载失败或已被取消
                progressDialog?.dismiss()
                if (!currentDownloadJob?.isCancelled!!) {
                    currentListener?.onDownloadFailed("下载失败，请重试")
                }
            }
            currentDownloadJob = null
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

    private fun showMd5MismatchDialog(activity: FragmentActivity) {
        AlertDialog.Builder(activity)
            .setTitle("文件校验失败")
            .setMessage("下载的安装包校验不通过，可能文件已损坏，请重新下载。")
            .setPositiveButton("确定", null)
            .show()
    }

    // 可取消的下载器
    private inner class ApkDownloader(private val context: Context) {
        suspend fun downloadApk(versionInfo: VersionInfo, onProgress: (downloaded: Long, total: Long, done: Boolean) -> Unit): File? =
            suspendCancellableCoroutine { continuation ->
                val request = Request.Builder().url(versionInfo.ApkUrl).get().build()
                val call = client.newCall(request)
                // 协程取消时中断网络请求
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                val mainHandler = Handler(Looper.getMainLooper())
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) {
                            continuation.resume(null)
                        }
                    }

                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        if (!response.isSuccessful) {
                            if (!continuation.isCancelled) continuation.resume(null)
                            return
                        }
                        val body = response.body ?: run {
                            if (!continuation.isCancelled) continuation.resume(null)
                            return
                        }
                        val contentLength = body.contentLength()
                        val fileName = "$packageName.${versionInfo.versionCode}.apk"
                        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                        destFile.parentFile?.mkdirs()
                        var success = false
                        try {
                            val source = body.source()
                            val fos = FileOutputStream(destFile)
                            val buffer = ByteArray(8192)
                            var totalRead = 0L
                            var bytesRead: Int
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                // 如果协程已被取消，中止下载并删除文件
                                if (!continuation.isActive) {
                                    fos.close()
                                    source.close()
                                    destFile.delete()
                                    return
                                }
                                fos.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                // 进度回调在主线程
                                mainHandler.post {
                                    onProgress(totalRead, contentLength, totalRead == contentLength)
                                }
                            }
                            fos.close()
                            source.close()
                            success = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            destFile.delete()
                        } finally {
                            if (success && !continuation.isCancelled) {
                                continuation.resume(destFile)
                            } else if (!continuation.isCancelled) {
                                continuation.resume(null)
                            }
                        }
                    }
                })
            }
    }

    private fun getApkFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode.apk"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    fun release() {
        currentDownloadJob?.cancel()
        updateScope.cancel()
        progressDialog?.dismiss()
        currentListener = null
    }
}