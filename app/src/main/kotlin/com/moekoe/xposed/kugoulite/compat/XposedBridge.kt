package com.moekoe.xposed.kugoulite.compat

import android.util.Log
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 旧版 de.robv.android.xposed.XposedBridge 的兼容门面。
 *
 * 本身不引用任何框架类，具体 hook 由 [HookBackend] 决定：
 *  - ModernBackend → libxposed API 100+ 的拦截器链
 *  - LegacyBackend → de.robv.android.xposed 的 before/after
 *
 * 业务层（ClaimWorker / KugouApi / PopupBlocker …）只跟这一层打交道。
 */
object XposedBridge {

    const val LOG_TAG = "[KuGouLiteVip]"

    /** 旧版 XposedBridge.PRIORITY_DEFAULT 等价物（两者都是 50） */
    const val DEFAULT_PRIORITY = 50

    @Volatile
    private var sBackend: HookBackend? = null

    /** 绑定后端。现代入口优先：已经是现代后端时忽略旧版覆盖。 */
    @JvmStatic
    @Synchronized
    fun attach(backend: HookBackend?) {
        if (backend == null) return
        val current = sBackend
        if (current != null && current.kind() == HookBackend.KIND_MODERN
            && backend.kind() == HookBackend.KIND_LEGACY) {
            return
        }
        sBackend = backend
    }

    @JvmStatic
    fun kind(): Int = sBackend?.kind() ?: 0

    @JvmStatic
    fun isModern(): Boolean = kind() == HookBackend.KIND_MODERN

    // ------------------------------------------------------------------ 日志

    @JvmStatic
    fun log(text: String?) {
        val msg = text ?: "null"
        Log.i(LOG_TAG, msg)
        sBackend?.log(msg, null)
    }

    @JvmStatic
    fun log(throwable: Throwable) {
        Log.e(LOG_TAG, "hook 异常", throwable)
        sBackend?.log("hook 异常", throwable)
    }

    // ------------------------------------------------------------------ hook

    @JvmStatic
    fun hookMethod(
        executable: Executable,
        callback: XC_MethodHook,
        priority: Int = DEFAULT_PRIORITY
    ) {
        val backend = sBackend
            ?: throw IllegalStateException("Xposed 框架尚未挂载，无法 hook $executable")
        backend.hook(executable, callback, priority)
    }

    /** 按方法名 hook 整个继承链上所有同名方法 */
    @JvmStatic
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: XC_MethodHook) {
        var count = 0
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (method in c.declaredMethods) {
                if (method.name != methodName) continue
                try {
                    hookMethod(method, callback)
                    count++
                } catch (t: Throwable) {
                    log(t)
                }
            }
            c = c.superclass
        }
        if (count == 0) {
            throw NoSuchMethodError(clazz.name + "." + methodName)
        }
    }
}
