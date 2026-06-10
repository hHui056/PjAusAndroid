# PjAusAndroid
品杰升级服务Android端

## 接入指引

**最新版本** ：[![jitpack](https://jitpack.io/v/hHui056/PjAusAndroid.svg)](https://jitpack.io/#hHui056/PjAusAndroid)
（具体**历史版本号**参见 [更新日志](/ChangeLog.md)）

### 依赖配置

#### 在项目根目录下的`build.gradle`中

```
allprojects {
    repositories {
        // JitPack 远程仓库：https://jitpack.io
        maven { url 'https://jitpack.io' }
    }
}
```

#### 在项目模块下的`build.gradle`中

```
dependencies {
    implementation 'com.github.hHui056:PjAusAndroid:1.0.0-release'
    //如果你的项目已经导入了以下依赖则不需要额外导入
    implementation 'com.squareup.okhttp3:okhttp:4.2.2'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2'
    implementation 'com.alibaba:fastjson:1.2.70'
    implementation 'androidx.appcompat:appcompat:1.3.1'
}
```

### 用法示例

```kotlin
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
                    Log.d("Tag", "文件下载中，进度: ${percent}")
                }

                override fun onDownloadComplete() {
                }

                override fun onDownloadFailed(error: String) {
                }

                override fun onInstallPermissionResult(granted: Boolean) {
                }
            })
```

## 版本记录

### 1.0.4

- add setLogImplementation接口传入log的实现
