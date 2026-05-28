package com.pj.aus

import android.content.Context
import android.widget.Toast

/**
 * Create By hHui on 2026/5/28 13:46
 *
 * @description
 */
class AusTestUtil {

    companion object {
        fun showToast(context: Context, message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}