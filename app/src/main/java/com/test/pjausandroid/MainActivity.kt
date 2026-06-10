package com.test.pjausandroid

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pj.aus.UpdateManager
import com.pj.aus.entity.VersionInfo
import com.pj.aus.listener.UpdateListener
import com.pj.aus.log.IUpdateLog

class MainActivity : AppCompatActivity(), IUpdateLog {
    private val tag = this.javaClass.simpleName

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            UpdateManager.init(applicationContext).apply {
                setCheckUrl("http://192.168.0.31:8754")
                setPackageName("fais6_update_test")
                setLogImplementation(this@MainActivity)
                setFileProviderAuthority("${this@MainActivity.packageName}.fileprovider")
                checkUpdate(this@MainActivity, showLoadingDialog = true)
            }
        }
        findViewById<TextView>(R.id.txt_version_code).text = "当前版本号Code=${getLocalVersionCode()}"
    }

    override fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    private fun getLocalVersionCode(): Int {
        return try {
            val packageInfo = this.packageManager.getPackageInfo(this.packageName, 0)
            val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }
            version
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
    }
}
