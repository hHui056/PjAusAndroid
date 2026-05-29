package com.pj.aus.util

import java.io.File
import java.security.MessageDigest
import java.io.FileInputStream

/**
 * Create By hHui on 2026/5/29 16:18
 *
 * @description
 */
object Md5Util {

    fun verifyMd5(file: File, expectedMd5: String?): Boolean {
        if (expectedMd5.isNullOrEmpty()) return true // 服务器未提供MD5时跳过校验
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val md5Hex = digest.digest().joinToString("") { "%02x".format(it) }
            md5Hex.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}