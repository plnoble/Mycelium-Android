package com.xavierxia.mycelium.update

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机走查里**不需要用户手点**的那几条（简报 §5.3 的 D1–D4 其余部分要人工看界面）。
 *
 * 这里只钉死两件在模拟器/真机上就能一眼看出对错的事：
 *  * 「安装未知应用」带路带到的是**本应用**的设置页（D3 的跳转那一半）；
 *  * 结果回执用的 `PendingIntent` 是**显式组件 + FLAG_MUTABLE**（C5 第 1 条的定稿方案，D2 的对照项）。
 *
 * 会话能建、系统确认界面能被拉起、`EXTRA_STATUS`/`EXTRA_INTENT` 到不到（D1/D2/D4）必须在真机上
 * 由人看着系统界面走一遍，脚本只能到「把 Intent 交出去」这一层。
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallDeviceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unknownSourcesIntentPointsAtThisAppsSettingsPage() {
        val gateway = AndroidInstallSessionGateway(context, UpdatePreferences(context))

        val intent = gateway.unknownSourcesIntent()

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun receiptPendingIntentIsExplicitAndMutable() {
        val gateway = AndroidInstallSessionGateway(context, UpdatePreferences(context))

        val receipt = gateway.receiptIntent(1)
        assertEquals(InstallResultReceiver::class.java.name, receipt.component?.className)
        // 显式 Intent：不解析任何隐式匹配，所以不会撞 lint 的 MutableImplicitPendingIntent。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue((AndroidInstallSessionGateway.RECEIPT_FLAGS and PendingIntent.FLAG_MUTABLE) != 0)
        } else {
            assertTrue((AndroidInstallSessionGateway.RECEIPT_FLAGS and PendingIntent.FLAG_UPDATE_CURRENT) != 0)
        }
        assertNotNull(gateway.receiptPendingIntent(1).intentSender)
    }

    @Test
    fun canRequestPackageInstallsAnswersWithoutThrowing() {
        // 值本身取决于用户的开关，这里只证明这一问在真机上真的答得出来（没权限时不能崩）。
        val gateway = AndroidInstallSessionGateway(context, UpdatePreferences(context))
        gateway.canRequestPackageInstalls()
        assertTrue(gateway.isAvailable)
    }
}