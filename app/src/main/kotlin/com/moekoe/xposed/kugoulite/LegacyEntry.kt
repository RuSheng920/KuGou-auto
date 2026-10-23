package com.moekoe.xposed.kugoulite

import com.moekoe.xposed.kugoulite.compat.LegacyBackend
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 旧版模块入口。
 *
 * 这个类**只在**框架不认识现代模块格式（META-INF/xposed 三件套）时被加载：
 * 框架读取 assets/xposed_init 拿到类名，再按 IXposedHookLoadPackage 回调。
 *
 * 注意：本类不能引用 io.github.libxposed.* 里的任何东西，
 * 否则在老框架上会因为找不到类而直接崩掉。
 */
class LegacyEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam == null || lpparam.packageName != Installer.TARGET_PACKAGE) {
            return
        }
        // 现代入口如果已经接管，就不要再安装一遍
        if (XposedBridge.isModern()) {
            return
        }
        XposedBridge.attach(LegacyBackend())
        Installer.install(lpparam.classLoader)
    }
}
