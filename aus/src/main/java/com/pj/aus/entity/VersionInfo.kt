package com.pj.aus.entity

import com.alibaba.fastjson.JSON

/**
 * Create By hHui on 2026/5/29 11:27
 *
 * @description 版本升级信息
 */
class VersionInfo {
    /** 补丁包名称 **/
    var patchName: String? = null

    /** 补丁包下载地址 **/
    var downloadUrl: String? = null

    /** 完整的安装包下载地址 **/
    var ApkUrl = ""

    /** 版本号 **/
    var versionCode: Int = -1

    /** 修改日志 **/
    var changeLog: String? = null

    /** 是否强制更新 **/
    var mustUpdate = false

    /**
     * 0-表示服务器端没有该APK的登记信息，即包名称不存在
     * 1-表示当前版本已经是服务器的最新版本
     * 2-表示服务器端有该APK 但当前程序的老版本未能找到，无法生成差分包，此时返回的地址为最新版本的下载地址
     * 3-表示服务器正在生成差分包，请大约1分钟之后在检测
     * 4-表示一切正常，可以使用下载地址下载差分包
     */
    var code = -1

    /**
     * APK文件md5值
     */
    var Md5Hash = ""

    override fun toString(): String {
        return JSON.toJSONString(this)
    }
}