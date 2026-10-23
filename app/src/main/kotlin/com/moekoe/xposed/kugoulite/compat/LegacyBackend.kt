package com.moekoe.xposed.kugoulite.compat

import de.robv.android.xposed.XC_MethodHook as LegacyXC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam as LegacyMethodHookParam
import de.robv.android.xposed.XposedBridge as LegacyXposedBridge
import java.lang.reflect.Executable

/**
 * 旧版 de.robv.android.xposed 后端。
 *
 * 只有在框架不认识现代模块格式（META-INF/xposed 三件套）时才会走到这里，
 * 此时框架已经把 de.robv.android.xposed.* 注入进来了，直接用它自己的 before/after。
 *
 * 注意：旧版 XC_MethodHook 的 MethodHookParam 是嵌套类，与本包的
 * [MethodHookParam] 同名，上面用 import 别名区分，避免混用。
 */
class LegacyBackend : HookBackend {

    override fun kind(): Int = HookBackend.KIND_LEGACY

    override fun hook(executable: Executable, callback: XC_MethodHook, priority: Int) {
        try {
            executable.isAccessible = true
        } catch (_: Throwable) {
        }
        LegacyXposedBridge.hookMethod(executable, object : LegacyXC_MethodHook(priority) {

            override fun beforeHookedMethod(lp: LegacyMethodHookParam) {
                val param = MethodHookParam(
                    lp.thisObject,
                    lp.method as? Executable,
                    lp.args
                )
                try {
                    callback.beforeHookedMethod(param)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
                if (param.isReturnEarly()) {
                    lp.setResult(param.getResult())
                    return
                }
                lp.args = param.args
            }

            override fun afterHookedMethod(lp: LegacyMethodHookParam) {
                val param = MethodHookParam(
                    lp.thisObject,
                    lp.method as? Executable,
                    lp.args
                )
                param.setFrameworkResult(lp.result)
                param.setFrameworkThrowable(lp.throwable)
                try {
                    callback.afterHookedMethod(param)
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
                if (param.isReturnEarly()) {
                    lp.setResult(param.getResult())
                } else if (param.hasThrowable()) {
                    lp.setThrowable(param.getThrowable())
                }
            }
        })
    }

    override fun log(message: String, throwable: Throwable?) {
        try {
            if (throwable == null) {
                LegacyXposedBridge.log(message)
            } else {
                LegacyXposedBridge.log(throwable)
            }
        } catch (_: Throwable) {
        }
    }
}
