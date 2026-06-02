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
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.alibaba.fastjson.JSON
import com.pj.aus.entity.VersionInfo
import com.pj.aus.listener.UpdateListener
import com.pj.aus.util.InstallPermissionHelper
import com.pj.aus.util.Md5Util
import com.pj.aus.util.PatchUtils
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
        private const val TAG = "UpdateManager"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UpdateManager? = null

        fun init(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }

        // 日志开关，可根据 BuildConfig.DEBUG 设置
        var isDebug = true
    }

    private val client = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
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
    private var pendingApkFile: File? = null
    private var currentDownloadJob: Job? = null

    private fun logD(msg: String) {
        if (isDebug) Log.d(TAG, msg)
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        if (isDebug) {
            if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
        }
    }

    /**
     * 设置服务器检查更新地址
     */
    fun setCheckUrl(url: String): UpdateManager {
        this.checkUrl = url
        logD("setCheckUrl: $url")
        return this
    }

    /**
     * 设置升级包名
     */
    fun setPackageName(name: String): UpdateManager {
        this.packageName = name
        logD("setPackageName: $name")
        return this
    }

    /**
     * 设置文件提供者
     */
    fun setFileProviderAuthority(authority: String): UpdateManager {
        this.fileProviderAuthority = authority
        logD("setFileProviderAuthority: $authority")
        return this
    }

    /**
     * 设置扩展参数
     */
    fun setExtParams(params: Map<String, String>): UpdateManager {
        this.extParams = params
        logD("setExtParams: $params")
        return this
    }

    /**
     * 开始检查更新
     * @param listener 回调监听
     * @param showDefaultProgressUI 是否使用库内置的下载进度对话框（默认true）
     */
    fun checkUpdate(activity: FragmentActivity, listener: UpdateListener?, showDefaultProgressUI: Boolean = true) {
        this.currentListener = listener
        logD("checkUpdate called, packageName=$packageName, checkUrl=$checkUrl")
        if (this.packageName.isEmpty()) {
            logE("未设置升级包名")
            listener?.onCheckFailed("未设置升级包名 请调用 setPackageName()")
            return
        }
        if (checkUrl.isEmpty()) {
            logE("检查更新地址未设置")
            listener?.onCheckFailed("检查更新地址未设置，请调用 setCheckUrl()")
            return
        }
        updateScope.launch {
            val updateInfo = fetchUpdateInfo(extParams)
            if (updateInfo == null) {
                logE("获取更新信息失败")
                listener?.onCheckFailed("获取更新信息失败，请检查网络或服务器返回格式")
                return@launch
            }
            logD("获取更新信息成功: versionCode=${updateInfo.versionCode}, code=${updateInfo.code}, mustUpdate=${updateInfo.mustUpdate}, ApkUrl=${updateInfo.ApkUrl}, downloadUrl=${updateInfo.downloadUrl}")
            if (updateInfo.code == 1) {
                if (showDefaultProgressUI) {
                    Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
                listener?.onAlreadyLatestVersion()
                return@launch
            }
            if (updateInfo.code == 3) {
                if (showDefaultProgressUI) {
                    Toast.makeText(context, "正在生成升级文件,请稍后再试", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val localVersionCode = getLocalVersionCode()
            logD("本地版本号: $localVersionCode, 服务器版本号: ${updateInfo.versionCode}")
            if (updateInfo.versionCode > localVersionCode) {
                listener?.onNewVersionFound(updateInfo)
                if (showDefaultProgressUI) {
                    val supportPatch = (updateInfo.code == 4 && !updateInfo.downloadUrl.isNullOrEmpty())
                    logD("是否支持增量更新: $supportPatch")
                    AlertDialog.Builder(activity).setTitle("发现新版本 v${updateInfo.versionCode}").setMessage(updateInfo.changeLog).apply {
                        if (supportPatch) {
                            setPositiveButton("增量更新") { _, _ ->
                                startDownloadPatch(activity, updateInfo)
                            }
                            setNegativeButton("全量更新") { _, _ ->
                                logD("用户点击全量更新按钮")
                                if (updateInfo.ApkUrl!!.isNotEmpty()) {
                                    startDownloadFullApk(activity, updateInfo)
                                } else {
                                    logE("全量包地址无效")
                                    currentListener?.onDownloadFailed("全量包地址无效")
                                }
                            }
                            if (!updateInfo.mustUpdate) {
                                setNeutralButton("稍后", null)
                            }
                        } else {
                            setPositiveButton("立即更新") { _, _ ->
                                logD("用户点击立即更新（全量）")
                                if (updateInfo.ApkUrl!!.isNotEmpty()) {
                                    startDownloadFullApk(activity, updateInfo)
                                } else {
                                    logE("全量包地址无效")
                                    currentListener?.onDownloadFailed("全量包地址无效")
                                }
                            }
                            if (!updateInfo.mustUpdate) {
                                setNegativeButton("稍后", null)
                            }
                        }
                    }.show()
                }
            } else {
                logD("当前已是最新版本")
                listener?.onAlreadyLatestVersion()
            }
        }
    }

    private suspend fun fetchUpdateInfo(extParams: Map<String, String>? = null): VersionInfo? = withContext(Dispatchers.IO) {
        var realRequestUrl = "${checkUrl}/aus/check/${packageName}/${getLocalVersionCode()}"
        if (extParams != null) {
            val sb = StringBuffer(realRequestUrl)
            sb.append("?")
            extParams.forEach {
                sb.append("&${it.key}=${it.value}")
            }
            realRequestUrl = sb.toString().replaceFirst("&", "")
        }
        logD("请求URL: $realRequestUrl")
        val request = Request.Builder().url(realRequestUrl).get().build()
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                logE("请求失败: code=${response.code}")
                return@withContext null
            }
            val json = response.body?.string()
            logD("服务器返回JSON: $json")
            if (json.isNullOrEmpty()) {
                logE("返回JSON为空")
                return@withContext null
            }
            JSON.parseObject(json, VersionInfo::class.java)
        } catch (e: Exception) {
            logE("解析更新信息异常", e)
            null
        }
    }

    private fun getLocalVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
            logD("本地版本号: $version")
            version
        } catch (e: PackageManager.NameNotFoundException) {
            logE("获取本地版本号失败", e)
            1
        }
    }

    // ======================= 完整 APK 下载 =======================

    private fun startDownloadFullApk(activity: FragmentActivity, versionInfo: VersionInfo) {
        val versionCode = versionInfo.versionCode
        logD("开始全量下载, 版本号=$versionCode, url=${versionInfo.ApkUrl}")
        val apkFile = getApkFile(versionCode)
        if (apkFile.exists() && apkFile.length() > 0) {
            logD("APK文件已存在，验证MD5: ${apkFile.absolutePath}")
            if (Md5Util.verifyMd5(apkFile, versionInfo.Md5Hash)) {
                logD("MD5校验通过，直接安装")
                installApkWithPermissionCheck(activity, apkFile)
                return
            } else {
                logD("MD5校验失败，删除旧文件")
                apkFile.delete()
            }
        }

        val tempFile = getTempApkFile(versionCode)
        val info = readDownloadInfo(versionCode, versionInfo.ApkUrl!!)

        var startOffset = 0L
        var totalSize = -1L

        if (info != null && tempFile.exists()) {
            startOffset = tempFile.length()
            totalSize = info.first
            logD("断点续传: 已下载 ${formatSize(startOffset)} / ${formatSize(totalSize)}")
        } else {
            cleanPartialDownload(versionCode)
            tempFile.delete()
            logD("全新下载，清理临时文件")
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.downloadProgressBar)
        downloadPercentText = dialogView.findViewById(R.id.tvDownloadPercent)

        progressDialog = AlertDialog.Builder(activity).setTitle("正在下载更新").setView(dialogView).setCancelable(false).setNegativeButton("取消") { dialog, _ ->
            logD("用户取消下载")
            currentDownloadJob?.cancel()
            cleanPartialDownload(versionCode)
            dialog.dismiss()
            currentListener?.onDownloadFailed("用户取消下载")
        }.show()

        if (startOffset > 0 && totalSize > 0) {
            val percent = (startOffset * 100 / totalSize).toInt()
            downloadProgressBar?.progress = percent
            downloadPercentText?.text = "$percent% (${formatSize(startOffset)}/${formatSize(totalSize)})"
            currentListener?.onDownloadProgress(percent, startOffset, totalSize)
        }

        currentDownloadJob = updateScope.launch {
            val downloader = ApkDownloader(context)
            val result = downloader.downloadFile(downloadUrl = versionInfo.ApkUrl!!, targetFile = tempFile, startOffset = startOffset, expectedTotalSize = if (totalSize > 0) totalSize else null, onProgress = { downloaded, total, done ->
                if (done) {
                    logD("全量包下载完成: $downloaded / $total")
                    progressDialog?.dismiss()
                    currentListener?.onDownloadComplete()
                } else {
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    downloadProgressBar?.progress = percent
                    downloadPercentText?.text = "$percent% (${formatSize(downloaded)}/${formatSize(total)})"
                    currentListener?.onDownloadProgress(percent, downloaded, total)
                    saveDownloadInfo(versionCode, versionInfo.ApkUrl!!, total, downloaded)
                }
            })

            if (result != null) {
                logD("全量包下载成功，开始MD5校验")
                if (Md5Util.verifyMd5(result, versionInfo.Md5Hash)) {
                    logD("MD5校验通过，重命名为正式APK")
                    val targetFile = getApkFile(versionCode)
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = result.renameTo(targetFile)
                    if (renamed) {
                        cleanPartialDownload(versionCode)
                        logD("重命名成功，准备安装")
                        installApkWithPermissionCheck(activity, targetFile)
                    } else {
                        logE("文件重命名失败")
                        currentListener?.onDownloadFailed("文件重命名失败")
                    }
                } else {
                    logE("MD5校验失败")
                    progressDialog?.dismiss()
                    result.delete()
                    cleanPartialDownload(versionCode)
                    currentListener?.onDownloadFailed("文件校验失败，安装包已损坏")
                    showMd5MismatchDialog(activity)
                }
            } else {
                logE("全量包下载失败")
                progressDialog?.dismiss()
                if (!currentDownloadJob?.isCancelled!!) {
                    currentListener?.onDownloadFailed("下载失败，请重试")
                }
            }
            currentDownloadJob = null
        }
    }

    // ======================= 差分包更新 =======================

    private fun startDownloadPatch(activity: FragmentActivity, versionInfo: VersionInfo) {
        val versionCode = versionInfo.versionCode
        val patchUrl = versionInfo.downloadUrl
        logD("开始增量更新，版本号=$versionCode, patchUrl=$patchUrl")
        if (patchUrl.isNullOrEmpty()) {
            logE("差分包地址为空")
            currentListener?.onDownloadFailed("差分包地址为空")
            return
        }
        val targetApkFile = getApkFile(versionCode)
        if (targetApkFile.exists() && targetApkFile.length() > 0 && Md5Util.verifyMd5(targetApkFile, versionInfo.Md5Hash)) {
            logD("目标APK已存在且MD5正确，直接安装")
            installApkWithPermissionCheck(activity, targetApkFile)
            return
        }

        val patchFile = getPatchFile(versionCode)
        val patchInfoFile = getPatchInfoFile(versionCode)
        val savedInfo = readDownloadInfo(versionCode, patchUrl, patchInfoFile)

        var startOffset = 0L
        var totalSize = -1L

        if (savedInfo != null && patchFile.exists() && patchFile.length() == savedInfo.second) {
            startOffset = savedInfo.second
            totalSize = savedInfo.first
            logD("差分包断点续传: 已下载 ${formatSize(startOffset)} / ${formatSize(totalSize)}")
        } else {
            cleanPatchDownload(versionCode)
            logD("全新下载差分包，清理旧文件")
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_download_progress, null)
        downloadProgressBar = dialogView.findViewById(R.id.downloadProgressBar)
        downloadPercentText = dialogView.findViewById(R.id.tvDownloadPercent)

        progressDialog = AlertDialog.Builder(activity).setTitle("正在下载增量更新包").setView(dialogView).setCancelable(false).setNegativeButton("取消") { dialog, _ ->
            logD("用户取消差分包下载")
            currentDownloadJob?.cancel()
            cleanPatchDownload(versionCode)
            dialog.dismiss()
            currentListener?.onDownloadFailed("用户取消下载")
        }.show()

        if (startOffset > 0 && totalSize > 0) {
            val percent = (startOffset * 100 / totalSize).toInt()
            downloadProgressBar?.progress = percent
            downloadPercentText?.text = "$percent% (${formatSize(startOffset)}/${formatSize(totalSize)})"
            currentListener?.onDownloadProgress(percent, startOffset, totalSize)
        }

        currentDownloadJob = updateScope.launch {
            val downloader = ApkDownloader(context)
            val downloadedPatchFile = downloader.downloadFile(downloadUrl = patchUrl, targetFile = patchFile, startOffset = startOffset, expectedTotalSize = if (totalSize > 0) totalSize else null, onProgress = { downloaded, total, done ->
                if (done) {
                    downloadPercentText?.text = "正在合成 APK..."
                    downloadProgressBar?.isIndeterminate = true
                    currentListener?.onDownloadProgress(100, downloaded, total)
                } else {
                    val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                    downloadProgressBar?.progress = percent
                    downloadPercentText?.text = "$percent% (${formatSize(downloaded)}/${formatSize(total)})"
                    currentListener?.onDownloadProgress(percent, downloaded, total)
                    saveDownloadInfo(versionCode, patchUrl, total, downloaded, patchInfoFile)
                }
            })

            if (downloadedPatchFile == null) {
                logE("差分包下载失败，回退全量更新")
                progressDialog?.dismiss()
                cleanPatchDownload(versionCode)
                currentListener?.onDownloadFailed("差分包下载失败，尝试完整包更新")
                startDownloadFullApk(activity, versionInfo)
                return@launch
            }
            val oldApkPath = getInstalledApkPath()
            if (oldApkPath == null || !File(oldApkPath).exists()) {
                logE("无法获取当前安装包路径: $oldApkPath")
                progressDialog?.dismiss()
                cleanPatchDownload(versionCode)
                currentListener?.onDownloadFailed("无法获取当前应用安装包，请使用完整包更新")
                startDownloadFullApk(activity, versionInfo)
                return@launch
            }
            val newApkFile = File(targetApkFile.absolutePath)
            logD("开始合成APK: 旧APK=$oldApkPath, 差分包=${patchFile.absolutePath}, 输出=${newApkFile.absolutePath}")
            val patchResult = withContext(Dispatchers.IO) {
                try {
                    PatchUtils.getInstance().patch(oldApkPath, newApkFile.absolutePath, patchFile.absolutePath)
                } catch (e: Exception) {
                    logE("合成过程异常", e)
                    -1
                }
            }
            logD("合成结果: $patchResult (0表示成功)")
            if (patchResult == -1) {
                logE("差分包合成失败")
                progressDialog?.dismiss()
                cleanPatchDownload(versionCode)
                newApkFile.delete()
                currentListener?.onDownloadFailed("差分包合成失败，请使用完整包更新")
                startDownloadFullApk(activity, versionInfo)
                return@launch
            }
            logD("合成成功，校验MD5")
            if (!Md5Util.verifyMd5(newApkFile, versionInfo.Md5Hash)) {
                logE("合成后APK的MD5校验失败")
                progressDialog?.dismiss()
                cleanPatchDownload(versionCode)
                newApkFile.delete()
                currentListener?.onDownloadFailed("合成后的 APK 校验失败")
                showMd5MismatchDialog(activity)
                return@launch
            }
            cleanPatchDownload(versionCode)
            progressDialog?.dismiss()
            logD("差分包更新成功，准备安装APK: ${targetApkFile.absolutePath}")
            currentListener?.onDownloadComplete()
            installApkWithPermissionCheck(activity, targetApkFile)
            currentDownloadJob = null
        }
    }

    private fun getInstalledApkPath(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val path = packageInfo.applicationInfo.sourceDir
            logD("已安装APK路径: $path")
            path
        } catch (e: Exception) {
            logE("获取已安装APK路径失败", e)
            null
        }
    }

    // ======================= 文件管理 =======================

    private fun getApkFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode.apk"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    private fun getTempApkFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode.apk.tmp"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    private fun getInfoFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode.apk.info"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    private fun getPatchFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode-${getLocalVersionCode()}.patch"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    private fun getPatchInfoFile(versionCode: Int): File {
        val fileName = "$packageName.$versionCode.patch.info"
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
    }

    private fun cleanPartialDownload(versionCode: Int) {
        getTempApkFile(versionCode).delete()
        getInfoFile(versionCode).delete()
        logD("清理全量包临时文件: versionCode=$versionCode")
    }

    private fun cleanPatchDownload(versionCode: Int) {
        getPatchFile(versionCode).delete()
        getPatchInfoFile(versionCode).delete()
        logD("清理差分包临时文件: versionCode=$versionCode")
    }

    private fun saveDownloadInfo(versionCode: Int, url: String, totalSize: Long, downloadedSize: Long, infoFile: File = getInfoFile(versionCode)) {
        val info = mapOf(
            "url" to url, "totalSize" to totalSize, "downloadedSize" to downloadedSize, "versionCode" to versionCode
        )
        infoFile.writeText(JSON.toJSONString(info))
    }

    private fun readDownloadInfo(versionCode: Int, expectedUrl: String, infoFile: File = getInfoFile(versionCode)): Pair<Long, Long>? {
        if (!infoFile.exists()) return null
        return try {
            val json = infoFile.readText()
            val map = JSON.parseObject(json, Map::class.java) as Map<*, *>
            val url = map["url"] as? String
            val totalSize = (map["totalSize"] as? Number)?.toLong() ?: -1
            val downloadedSize = (map["downloadedSize"] as? Number)?.toLong() ?: -1
            val savedVersion = (map["versionCode"] as? Number)?.toInt() ?: -1
            if (url == expectedUrl && savedVersion == versionCode && totalSize > 0 && downloadedSize >= 0 && downloadedSize <= totalSize) {
                Pair(totalSize, downloadedSize)
            } else {
                logD("读取下载信息失败: 不匹配 (url=$url, expectedUrl=$expectedUrl, savedVersion=$savedVersion, expectedVersion=$versionCode)")
                null
            }
        } catch (e: Exception) {
            logE("解析下载信息文件异常", e)
            null
        }
    }

    // ======================= 通用下载器 =======================

    private inner class ApkDownloader(private val context: Context) {
        suspend fun downloadFile(
            downloadUrl: String, targetFile: File, startOffset: Long, expectedTotalSize: Long?, onProgress: (downloaded: Long, total: Long, done: Boolean) -> Unit
        ): File? = suspendCancellableCoroutine { continuation ->
            targetFile.parentFile?.mkdirs()
            logD("开始下载: url=$downloadUrl, 目标文件=${targetFile.absolutePath}, startOffset=$startOffset, expectedTotalSize=$expectedTotalSize")
            val requestBuilder = Request.Builder().url(downloadUrl)
            if (startOffset > 0 && expectedTotalSize != null) {
                val rangeHeader = "bytes=$startOffset-${expectedTotalSize - 1}"
                requestBuilder.addHeader("Range", rangeHeader)
                logD("添加Range头: $rangeHeader")
            }
            val request = requestBuilder.build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                logD("下载任务被取消")
                call.cancel()
            }
            val mainHandler = Handler(Looper.getMainLooper())
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    logE("下载请求失败", e)
                    if (!continuation.isCancelled) {
                        continuation.resume(null)
                    }
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    logD("下载响应码: ${response.code}")
                    if (!response.isSuccessful) {
                        if (startOffset > 0 && response.code == 416) {
                            logE("Range不满足(416)，清理文件重新下载")
                            targetFile.delete()
                        } else {
                            logE("响应失败: ${response.code}")
                        }
                        if (!continuation.isCancelled) continuation.resume(null)
                        return
                    }
                    val body = response.body ?: run {
                        logE("响应body为空")
                        if (!continuation.isCancelled) continuation.resume(null)
                        return
                    }
                    var totalSize = expectedTotalSize ?: -1L
                    val contentRange = response.header("Content-Range")
                    if (contentRange != null) {
                        val match = Regex("bytes \\d+-(\\d+)/(\\d+)").find(contentRange)
                        if (match != null) {
                            totalSize = match.groupValues[2].toLong()
                            logD("从Content-Range解析总大小: $totalSize")
                        }
                    }
                    if (totalSize <= 0) {
                        totalSize = body.contentLength()
                        logD("使用Content-Length作为总大小: $totalSize")
                    }
                    if (totalSize <= 0 && startOffset > 0) {
                        logE("无法获取总大小，放弃续传")
                        targetFile.delete()
                        if (!continuation.isCancelled) continuation.resume(null)
                        return
                    }

                    var success = false
                    try {
                        val source = body.source()
                        val fos = FileOutputStream(targetFile, true)
                        val buffer = ByteArray(8192)
                        var totalRead = startOffset
                        var bytesRead: Int
                        while (source.read(buffer).also { bytesRead = it } != -1) {
                            if (!continuation.isActive) {
                                logD("协程已取消，停止写入")
                                fos.close()
                                source.close()
                                return
                            }
                            fos.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            mainHandler.post {
                                onProgress(totalRead, totalSize, totalRead == totalSize)
                            }
                        }
                        fos.close()
                        source.close()
                        success = true
                        logD("下载完成，总下载字节: $totalRead")
                    } catch (e: Exception) {
                        logE("下载写入异常", e)
                        targetFile.delete()
                    } finally {
                        if (success && !continuation.isCancelled) {
                            continuation.resume(targetFile)
                        } else if (!continuation.isCancelled) {
                            continuation.resume(null)
                        }
                    }
                }
            })
        }
    }

    // ======================= 安装与辅助 =======================

    private fun installApkWithPermissionCheck(activity: FragmentActivity, apkFile: File?) {
        if (apkFile == null || !apkFile.exists()) {
            logE("安装失败: APK文件不存在")
            currentListener?.onDownloadFailed("APK 文件不存在")
            return
        }
        logD("开始安装APK: ${apkFile.absolutePath}, 大小=${apkFile.length()}")
        pendingApkFile = apkFile
        val permissionHelper = InstallPermissionHelper(activity)
        permissionHelper.checkAndRequestPermission { granted ->
            if (granted) {
                logD("安装权限已授予")
                val installed = performInstallApk(activity, pendingApkFile!!)
                if (!installed) {
                    logE("安装启动失败")
                    currentListener?.onDownloadFailed("安装失败")
                } else {
                    logD("安装意图已发送")
                    currentListener?.onInstallPermissionResult(true)
                }
            } else {
                logE("用户拒绝安装权限")
                currentListener?.onInstallPermissionResult(false)
                currentListener?.onDownloadFailed("缺少安装未知来源应用的权限，请手动授权后重试")
            }
            pendingApkFile = null
        }
    }

    private fun performInstallApk(activity: FragmentActivity, apkFile: File): Boolean {
        if (!apkFile.exists()) {
            logE("安装时APK文件不存在: ${apkFile.absolutePath}")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                logE("Android O+ 未允许安装未知来源应用")
                return false
            }
        }
        val authority = fileProviderAuthority.ifEmpty { "${activity.packageName}.fileprovider" }
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(activity, authority, apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        logD("安装URI: $apkUri, authority=$authority")
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
        AlertDialog.Builder(activity).setTitle("文件校验失败").setMessage("下载的安装包校验不通过，可能文件已损坏，请重新下载。").setPositiveButton("确定", null).show()
    }

    fun release() {
        logD("释放资源，取消下载任务")
        currentDownloadJob?.cancel()
        updateScope.cancel()
        progressDialog?.dismiss()
        currentListener = null
    }
}