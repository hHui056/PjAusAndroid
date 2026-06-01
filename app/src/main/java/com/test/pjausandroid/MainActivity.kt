package com.test.pjausandroid

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pj.aus.UpdateManager
import com.pj.aus.entity.VersionInfo
import com.pj.aus.listener.UpdateListener

class MainActivity : AppCompatActivity() {
    private val tag = this.javaClass.simpleName

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Toast.makeText(this, "这是新的apk", Toast.LENGTH_LONG).show()
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            val updateManager = UpdateManager.init(applicationContext)
            updateManager.setCheckUrl("http://192.168.0.31:8754")
            updateManager.setPackageName("fais6_update_test")
            updateManager.setFileProviderAuthority("${this.packageName}.fileprovider")
            updateManager.checkUpdate(this, object : UpdateListener {
                override fun onNewVersionFound(updateInfo: VersionInfo) {
                    Log.d(tag, "检测到新版本 ${updateInfo}")
                }

                override fun onAlreadyLatestVersion() {
                }

                override fun onCheckFailed(error: String) {
                }

                override fun onDownloadProgress(percent: Int, downloaded: Long, total: Long) {
                    Log.d("MainActivity", "文件下载中，进度: ${percent}")
                }

                override fun onDownloadComplete() {
                }

                override fun onDownloadFailed(error: String) {
                }

                override fun onInstallPermissionResult(granted: Boolean) {
                }
            })
        }
    }
}
