package com.moekoe.xposed.kugoulite

import android.util.Log
import com.moekoe.xposed.kugoulite.compat.ModernBackend
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（现代格式：libxposed API 100+）。
 *
 * 与旧版 IXposedHookLoadPackage 的对应关系：
 *   handleLoadPackage(lpparam)  → onPackageReady(param)
 *   lpparam.packageName         → param.getPackageName()
 *   lpparam.classLoader         → param.getClassLoader()
 *
 * 旧版框架不会加载这个类，而是走 assets/xposed_init 里的 LegacyEntry。
 */
class MainHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedBridge.attach(ModernBackend(this))
        log(Log.INFO, TAG, "========== 模块已加载 / API $apiVersion / "
                + "$frameworkName $frameworkVersion ==========")
        log(Log.INFO, TAG, "进程: " + param.processName + " (systemServer=" + param.isSystemServer + ")")
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (TARGET_PACKAGE == param.packageName) {
            log(Log.INFO, TAG, "onPackageLoaded: " + param.packageName
                    + " first=" + param.isFirstPackage)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (TARGET_PACKAGE != param.packageName) {
            return
        }
        Installer.install(param.classLoader)
    }

    companion object {
        private const val TARGET_PACKAGE = "com.kugou.android.lite"
        private const val TAG = "[KuGouLiteVip]"
    }
}
