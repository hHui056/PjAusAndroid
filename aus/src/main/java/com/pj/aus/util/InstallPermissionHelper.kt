package com.pj.aus.util

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * Create By hHui on 2026/5/29 14:51
 *
 * @description
 */
internal class InstallPermissionHelper(private val activity: FragmentActivity) {
    private var pendingCallback: ((Boolean) -> Unit)? = null
    private var launcher: ActivityResultLauncher<Intent>? = null

    fun checkAndRequestPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onResult(true)
            return
        }
        if (activity.packageManager.canRequestPackageInstalls()) {
            onResult(true)
            return
        }
        // 未授权，请求权限
        pendingCallback = onResult
        // 使用 Fragment 来启动 launcher，因为需要生命周期
        val fragment = getRequestFragment()
        fragment.launchPermissionRequest { granted ->
            pendingCallback?.invoke(granted)
            pendingCallback = null
        }
    }

    private fun getRequestFragment(): PermissionRequestFragment {
        var fragment = activity.supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? PermissionRequestFragment
        if (fragment == null) {
            fragment = PermissionRequestFragment()
            activity.supportFragmentManager.beginTransaction()
                .add(fragment, FRAGMENT_TAG)
                .commitNow()
        }
        return fragment
    }

    internal class PermissionRequestFragment : Fragment() {
        private var callback: ((Boolean) -> Unit)? = null

        override fun onDestroy() {
            super.onDestroy()
            callback = null
        }

        fun launchPermissionRequest(onResult: (Boolean) -> Unit) {
            callback = onResult
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${requireActivity().packageName}")
            }
            startActivityForResult(intent, REQUEST_CODE)
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == REQUEST_CODE) {
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                        requireActivity().packageManager.canRequestPackageInstalls()
                callback?.invoke(granted)
                callback = null
                // 请求完成后移除自身
                requireActivity().supportFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
            }
        }

        companion object {
            private const val REQUEST_CODE = 10001
        }
    }

    companion object {
        private const val FRAGMENT_TAG = "com.updatesdk.permission_request"
    }
}