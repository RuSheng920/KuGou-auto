package com.moekoe.xposed.kugoulite.compat

/**
 * 兼容旧版 Xposed 的 XC_MethodHook。
 * 在 libxposed API 102 的拦截器链模型上还原 before / after 两段式回调。
 *
 * 注意：Kotlin 的 protected 不含包内可见性，而 ModernBackend 的 BridgeHooker
 * 不是本类的子类，需要跨类调用这两个回调，所以用 internal（模块内可见）。
 */
abstract class XC_MethodHook {

    internal open fun beforeHookedMethod(param: MethodHookParam) {
    }

    internal open fun afterHookedMethod(param: MethodHookParam) {
    }
}
