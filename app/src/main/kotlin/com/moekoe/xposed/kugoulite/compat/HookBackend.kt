package com.moekoe.xposed.kugoulite.compat

import java.lang.reflect.Executable

/**
 * Hook 后端抽象。
 *
 * 业务层只认这一层皮，具体框架能力由实现决定：
 *  - [ModernBackend]  → libxposed API 100+（LSPosed 新框架）
 *  - [LegacyBackend]  → de.robv.android.xposed API 82（EdXposed / 旧 Xposed）
 */
interface HookBackend {

    fun kind(): Int

    fun log(message: String, throwable: Throwable? = null)

    fun hook(executable: Executable, callback: XC_MethodHook, priority: Int = XposedBridge.DEFAULT_PRIORITY)

    companion object {
        const val KIND_MODERN = 1
        const val KIND_LEGACY = 2
    }
}
