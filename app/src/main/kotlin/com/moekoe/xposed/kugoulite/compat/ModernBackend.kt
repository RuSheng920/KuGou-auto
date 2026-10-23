package com.moekoe.xposed.kugoulite.compat

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * libxposed API 100+ 现代后端：把拦截器链模型还原成旧版 before / after 语义。
 */
class ModernBackend(private val module: XposedModule) : HookBackend {

    override fun kind(): Int = HookBackend.KIND_MODERN

    override fun hook(executable: Executable, callback: XC_MethodHook, priority: Int) {
        try {
            executable.isAccessible = true
        } catch (_: Throwable) {
        }
        module.hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(BridgeHooker(callback))
    }

    override fun log(message: String, throwable: Throwable?) {
        try {
            if (throwable == null) {
                module.log(Log.INFO, XposedBridge.LOG_TAG, message)
            } else {
                module.log(Log.ERROR, XposedBridge.LOG_TAG, message, throwable)
            }
        } catch (_: Throwable) {
        }
    }

    private class BridgeHooker(private val callback: XC_MethodHook) : XposedInterface.Hooker {

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val argList = chain.args
            val param = MethodHookParam(
                chain.thisObject,
                chain.executable,
                argList?.toTypedArray() ?: emptyArray()
            )

            try {
                callback.beforeHookedMethod(param)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }

            // before 里 setResult 过 → 短路，原方法不执行
            if (param.isReturnEarly()) {
                return param.getResult()
            }

            try {
                param.setFrameworkResult(chain.proceed(param.args))
            } catch (t: Throwable) {
                param.setFrameworkThrowable(t)
            }

            try {
                callback.afterHookedMethod(param)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }

            if (param.isReturnEarly()) {
                return param.getResult()
            }
            param.getThrowable()?.let { throw it }
            return param.getResult()
        }
    }
}
