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
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) trim(LocalModelEngine.shared)
            }
            private fun trim(engine: LocalModelEngine) {
                scope.launch { runCatching { engine.trimCheckpointsNow() } }
            }
        })
    }
}
