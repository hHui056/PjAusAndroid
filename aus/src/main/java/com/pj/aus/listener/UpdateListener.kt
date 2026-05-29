package com.pj.aus.listener

import com.pj.aus.entity.VersionInfo

/**
 * Create By hHui on 2026/5/29 14:23
 *
 * @description
 */
interface UpdateListener {
    /**
     * 检查完成，有新版本
     * @param updateInfo 最新版本信息
     */
    fun onNewVersionFound(updateInfo: VersionInfo)

    /**
     * 当前已是最新版本
     */
    fun onAlreadyLatestVersion()

    /**
     * 检查更新失败（网络错误、解析错误等）
     * @param error 错误信息
     */
    fun onCheckFailed(error: String)

    /**
     * 下载进度更新
     * @param percent 0-100
     * @param downloaded 已下载字节数
     * @param total 总字节数
     */
    fun onDownloadProgress(percent: Int, downloaded: Long, total: Long)

    /**
     * 下载完成，准备安装（可选，库内部会自动调用安装）
     */
    fun onDownloadComplete()

    /**
     * 下载失败
     */
    fun onDownloadFailed(error: String)

    /**
     * 安装权限请求结果（Android 8.0+ 未知来源安装权限）
     * @param granted 是否已授权
     */
    fun onInstallPermissionResult(granted: Boolean)
}