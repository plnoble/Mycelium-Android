package com.xavierxia.mycelium

import android.app.Application
import android.content.ComponentCallbacks2
import com.xavierxia.mycelium.llm.LocalModelEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程级回调（全量审 P2-6，2026-10-24）：把系统的 `onTrimMemory` 接到检查点清理上。
 *
 * 之前引擎有 `requestCheckpointTrim()` 但没有任何生产代码调它——而且那个只置标记、等下一轮生成
 * 开始才消费，用户之后不再发消息的话检查点就一直占着内存。现在：系统报内存紧张时在**后台协程**
 * 里调 `trimCheckpointsNow()`（拿引擎锁清仓库并释放原生句柄），主线程不等推理锁。
 *
 * 级别取 `TRIM_MEMORY_RUNNING_LOW` 及以上（含后台档）：UI_HIDDEN/以下的小抖动不清——清了会让
 * 用户下一轮退回整段重读，省下的内存不值得。
 */
class MyceliumApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            // onLowMemory 与 TRIM_MEMORY_RUNNING_LOW 在新 API 上标记了弃用（onTrimMemory 取代），
            // 但旧设备的低内存回调仍走它们：这里显式保留并压掉弃用告警。
            @Deprecated("旧设备仍走这里", ReplaceWith("onTrimMemory"))
            override fun onLowMemory() = trim(LocalModelEngine.shared)

            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                // **级别必须挑对**：`TRIM_MEMORY_UI_HIDDEN`(20) 的意思是「界面不可见」，不是内存紧张。
                // 用 `level >= RUNNING_LOW` 会把它一起算进来，于是用户切一次后台、检查点就被清空，
                // 回来还得整段重读——比占那几十 MB 难受得多（真机十轮验收就这样被反复清到 0 份）。
                // 现在只在「明确的内存压力」下清理：
                //   RUNNING_MODERATE(5) 不动；RUNNING_LOW(10)/RUNNING_CRITICAL(15) 清；
                //   UI_HIDDEN(20) 不动；BACKGROUND(40) 及以上清。
                val pressure = (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                if (pressure) trim(LocalModelEngine.shared)
            }
            private fun trim(engine: LocalModelEngine) {
                // 全量审 R2 P2-3：**先同步打标记，再异步等锁清理**。只 launch 的话，生成持锁期间的
                // 通知根本传不进去——收尾的入库准入看不到它，这一轮照样把新快照收进仓库。
                engine.requestCheckpointTrim()
                scope.launch { runCatching { engine.trimCheckpointsNow() } }
            }
        })
    }
}
