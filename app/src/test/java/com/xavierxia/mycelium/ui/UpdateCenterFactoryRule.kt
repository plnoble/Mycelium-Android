package com.xavierxia.mycelium.ui

import android.content.Context
import com.xavierxia.mycelium.update.UpdateCenter
import com.xavierxia.mycelium.updateCenterFactory
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 在 Activity 启动**之前**替换 [updateCenterFactory]，跑完还原（方案审 C3 第 5 条）。
 *
 * 必须这样包在外面用：`RuleChain.outerRule(UpdateCenterFactoryRule { ... }).around(composeRule)`。
 * 只写 `@Before` 不行 —— Activity 已经先起来了，工厂换晚了。
 */
internal class UpdateCenterFactoryRule(private val factory: (Context) -> UpdateCenter) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val restore = updateCenterFactory
            updateCenterFactory = factory
            try {
                base.evaluate()
            } finally {
                updateCenterFactory = restore
            }
        }
    }
}