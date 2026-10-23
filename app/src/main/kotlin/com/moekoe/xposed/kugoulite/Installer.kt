package com.moekoe.xposed.kugoulite

import android.content.Context
import com.moekoe.xposed.kugoulite.compat.MethodHookParam
import com.moekoe.xposed.kugoulite.compat.XC_MethodHook
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import com.moekoe.xposed.kugoulite.compat.XposedHelpers

/**
 * 统一的注入流程。
 * 现代入口（MainHook）与旧版入口（LegacyEntry）都调它，静态标志保证只装一次。
 *
 * v1.5.0：新增 Application.attachBaseContext 钩子 —— 这是进程内最早能拿到
 * Context 的时机（早于 onCreate），凭证缓存命中时领取可以在应用启动后
 * 1~2 秒内发出，不用等界面就绪。
 */
object Installer {

    const val TARGET_PACKAGE = "com.kugou.android.lite"

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(classLoader: ClassLoader?) {
        if (classLoader == null || installed.getAndSet(true)) {
            return
        }

        ModuleLog.log("========== 模块已注入目标应用 ==========")
        ModuleLog.log("Hook 后端: " + if (XposedBridge.isModern()) "libxposed 现代 API" else "旧版 de.robv API")

        // 1. OkHttp 凭证捕获
        NetCapture.install(classLoader)
        // 2. 领取弹窗阻止
        PopupBlocker.install(classLoader)
        // 3. 自动点击兜底
        AutoClicker.install(classLoader)
        // 4. Application.attachBaseContext → 最早触发点（v1.5.0 新增）
        hookApplicationAttach(classLoader)
        // 5. Application.onCreate → 触发领取工作器（兜底，内部幂等）
        hookApplicationOnCreate(classLoader)
        // 6. Activity.onResume → 兜底触发（凭证往往这会儿才齐）
        hookActivityResume(classLoader)
    }

    /**
     * 最早的触发点：Application.attachBaseContext。
     * 此时 base context 已挂载，SP / 广播 / ContentProvider 均可用，
     * 而酷狗自己的登录态多数情况在上次退出时就写好了，凭证缓存直接命中。
     */
    private fun hookApplicationAttach(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attachBaseContext",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.thisObject as? Context ?: return
                            ModuleLog.init(context)
                            ModuleLog.log("Application.attachBaseContext 触发（最早时机）")
                            ClaimWorker.start(context)
                        } catch (e: Exception) {
                            ModuleLog.log("attachBaseContext 启动失败: $e")
                        }
                    }
                }
            )
            XposedBridge.log("[KuGouLiteVip] Application.attachBaseContext Hook 安装成功")
        } catch (e: Exception) {
            // 个别 ROM 反射受限时退回 onCreate 触发，不影响功能
            XposedBridge.log("[KuGouLiteVip] Application.attachBaseContext Hook 安装失败（将改用 onCreate）: $e")
        }
    }

    private fun hookApplicationOnCreate(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.thisObject as? Context ?: return
                            ModuleLog.init(context)
                            ModuleLog.log("Application.onCreate 触发")
                            ModuleLog.log("Application 类: " + context.javaClass.name)
                            ClaimWorker.start(context)
                        } catch (e: Exception) {
                            ModuleLog.log("启动失败: $e")
                        }
                    }
                }
            )
            XposedBridge.log("[KuGouLiteVip] Application.onCreate Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log("[KuGouLiteVip] Application.onCreate Hook 安装失败: $e")
        }
    }

    /**
     * 兜底触发点。
     *
     * attachBaseContext 时刻若凭证还没就绪（全新安装/凭证过期），
     * 用户把酷狗界面划到前台时登录态早已准备好，这时再检查一次。
     * ClaimWorker 内部有 3 秒节流 + "今日已完成"判断，不会刷请求。
     */
    private fun hookActivityResume(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                classLoader,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.thisObject as? Context ?: return
                            if (context.packageName != TARGET_PACKAGE) return
                            if (ClaimWorker.isAllDone()) return
                            val kickTag = "Activity.onResume: " + context.javaClass.simpleName
                            Thread({ ClaimWorker.kick(kickTag) }, "KuGouLiteVip-Resume").start()
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
            XposedBridge.log("[KuGouLiteVip] Activity.onResume Hook 安装成功（兜底触发）")
        } catch (t: Throwable) {
            XposedBridge.log("[KuGouLiteVip] Activity.onResume Hook 安装失败: $t")
        }
    }
}
