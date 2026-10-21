package com.xavierxia.mycelium

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xavierxia.mycelium.roundtable.ChatService
import com.xavierxia.mycelium.ui.MyceliumApp
import com.xavierxia.mycelium.update.AndroidInstallSessionGateway
import com.xavierxia.mycelium.update.CurrentAppFacts
import com.xavierxia.mycelium.update.GatewayApkDownloader
import com.xavierxia.mycelium.update.LocalApkVerifier
import com.xavierxia.mycelium.update.PackageManagerApkFactsReader
import com.xavierxia.mycelium.update.UpdateCenter
import com.xavierxia.mycelium.update.UpdateFiles
import com.xavierxia.mycelium.update.UpdateManager
import com.xavierxia.mycelium.update.UpdatePreferences
import java.io.File

/**
 * 谁能造 [UpdateCenter] 收成一个可替换点（方案审 Q5）。测试必须在 Activity **启动之前**换掉它，
 * 跑完还原：见 `MainActivityTest` / `MainActivityAutoCheckTest` 里的 `UpdateCenterFactoryRule`，
 * 它用 `RuleChain.outerRule(...).around(composeRule)` 包在 Activity 规则外面。
 */
internal var updateCenterFactory: (Context) -> UpdateCenter = ::defaultUpdateCenter

class MainActivity : ComponentActivity() {
    // 状态挂在 Activity 级的 ViewModel 上：切底栏、重建都不会把卡片打回原形。
    private val center: UpdateCenter by viewModels { UpdateCenterFactory(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyceliumApp(center)
        }
    }

    /**
     * 回到前台就让 [UpdateCenter] 复查一次：系统安装界面弹过之后（成功、取消都在这里回来）
     * 靠它把落盘的结果认回来，并用 `longVersionCode` 做权威判定（C5 第 3 条）。
     */
    override fun onResume() {
        super.onResume()
        center.onResume()
    }
}

private class UpdateCenterFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return updateCenterFactory(context) as T
    }
}

/**
 * 生产装配。调试包在这里算一次（`applicationIdSuffix = ".debug"`），业务逻辑不直接读
 * `BuildConfig.APPLICATION_ID`：Robolectric 下 applicationId 与真机不同，逻辑里读它测不出东西。
 *
 * 下载、四项校验、安装会话三件都在这里接线。会话状态与发布元数据共用同一份 [UpdatePreferences]：
 * [InstallResultReceiver] 在新进程里也读它，成功安装终结旧进程之后结果才不会丢（C5 第 3 条）。
 */
private fun defaultUpdateCenter(context: Context): UpdateCenter {
    val app = context.applicationContext
    val files = UpdateFiles(File(app.cacheDir, "updates"))
    val transport = ChatService(app).updateTransport()
    val prefs = UpdatePreferences(app)
    return UpdateCenter(
        updates = UpdateManager(ChatService(app).releaseSource()),
        prefs = prefs,
        isDebugPackage = BuildConfig.APPLICATION_ID.endsWith(".debug"),
        files = files,
        downloader = GatewayApkDownloader(files, transport),
        verifier = LocalApkVerifier(
            transport = transport,
            archive = PackageManagerApkFactsReader(app),
            installed = CurrentAppFacts(app),
            selfPackageName = BuildConfig.APPLICATION_ID,
        ),
        installer = AndroidInstallSessionGateway(app, prefs),
        sessionState = prefs,
    )
}