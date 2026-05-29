package com.test.pjausandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.pj.aus.AusTestUtil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AusTestUtil.showToast(this,"testtest")
    }
}
