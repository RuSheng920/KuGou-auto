package com.moekoe.xposed.kugoulite

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.moekoe.xposed.kugoulite.compat.MethodHookParam
import com.moekoe.xposed.kugoulite.compat.XC_MethodHook
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import com.moekoe.xposed.kugoulite.compat.XposedHelpers
import java.util.WeakHashMap

/**
 * 弹窗阻止器（v1.5.0 Kotlin 重写）
 *
 * 核心思路：在弹窗显示前拦截，阻止其出现。
 * 同时 ClaimWorker 通过 API 独立完成领取，不依赖弹窗。
 *
 * 三层拦截防线：
 * 1. Dialog.setContentView — 检查内容，标记领取弹窗
 * 2. Dialog.show() — before 阻止已标记弹窗；after 强制关闭漏网弹窗
 * 3. WindowManagerImpl.addView — 底层拦截，阻止任何领取浮层添加到窗口
 * 4. Activity.startActivity — 阻止活动页 Activity 启动
 *
 * v1.5.0：关键词收紧。1.4.0 的列表里有"活动/福利/赠送/充值/续费"这类宽泛词，
 * 会误伤酷狗正常对话框（如续费提醒、活动说明），本版只保留精准的
 * "领取/签到/VIP" 语义组合词，误伤面显著下降。
 */
object PopupBlocker {

    private const val TAG = "[KuGouLiteVip]"

    // 阻止关键词：Dialog/View 内容含这些词则阻止（精准组合，避免误伤）
    private val BLOCK_KEYWORDS = arrayOf(
        "立即领取", "去领取", "马上领取", "一键签到", "一键领取",
        "领取会员", "领取VIP", "领VIP", "听歌会员", "概念版VIP",
        "免费领取", "点击领取", "每日签到", "每日领取",
        "领取成功", "签到成功", "升级VIP", "免费VIP",
        "领取", "签到",
        "receive_vip", "listen_song"
    )

    // 活动 Activity 类名关键词
    private val ACTIVITY_CLASS_KEYWORDS = arrayOf(
        "Sign", "Vip", "Welfare", "Receive", "Recharge",
        "WebActivity", "H5Activity", "BrowserActivity", "AdActivity"
    )

    // 活动 URL 关键词
    private val ACTIVITY_URL_KEYWORDS = arrayOf(
        "getvips", "receive_vip", "signin", "sign_in",
        "welfare", "listen_song", "recharge"
    )

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 标记已识别为领取弹窗的 Dialog
    private val flaggedDialogs = WeakHashMap<Dialog, Boolean>()

    fun install(classLoader: ClassLoader) {
        if (installed.getAndSet(true)) return

        hookDialogSetContentView()
        hookDialogShow()
        hookWindowManagerAddView(classLoader)
        hookActivityStartActivity()
        hookPopupWindow(classLoader)

        XposedBridge.log(TAG + " PopupBlocker 安装完成")
    }

    // ===== 1. Hook Dialog.setContentView — 标记领取弹窗 =====

    private fun hookDialogSetContentView() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val dialog = param.thisObject as? Dialog ?: return
                    if (containsClaimKeyword(dialog)) {
                        flaggedDialogs[dialog] = true
                        XposedBridge.log(TAG + " 标记领取弹窗(setContentView)")
                    }
                } catch (_: Exception) {
                    // 静默
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(Dialog::class.java, "setContentView", Int::class.java, hook)
            XposedHelpers.findAndHookMethod(Dialog::class.java, "setContentView", View::class.java, hook)
            XposedHelpers.findAndHookMethod(
                Dialog::class.java, "setContentView",
                View::class.java, ViewGroup.LayoutParams::class.java, hook
            )
            XposedBridge.log(TAG + " Dialog.setContentView Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " Dialog.setContentView Hook 失败: $e")
        }
    }

    // ===== 2. Hook Dialog.show() — 阻止领取弹窗 =====

    private fun hookDialogShow() {
        try {
            XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val dialog = param.thisObject as? Dialog ?: return

                        // 检查1：是否已标记
                        if (java.lang.Boolean.TRUE == flaggedDialogs[dialog]) {
                            XposedBridge.log(TAG + " 阻止领取弹窗显示(标记)")
                            param.setResult(null)
                            return
                        }

                        // 检查2：DecorView 是否含领取关键词
                        if (containsClaimKeyword(dialog)) {
                            XposedBridge.log(TAG + " 阻止领取弹窗显示(内容)")
                            flaggedDialogs[dialog] = true
                            param.setResult(null)
                            return
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // 防线2：如果 show() 已执行（未被 before 阻止），强制关闭
                    try {
                        val dialog = param.thisObject as? Dialog ?: return
                        if (containsClaimKeyword(dialog) && dialog.isShowing) {
                            XposedBridge.log(TAG + " 强制关闭漏网领取弹窗")
                            forceCloseDialog(dialog)
                        }
                    } catch (_: Exception) {
                        // 静默
                    }
                }
            })
            XposedBridge.log(TAG + " Dialog.show Hook(PopupBlocker) 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " Dialog.show Hook(PopupBlocker) 失败: $e")
        }
    }

    /**
     * 强制关闭 Dialog（多种方式）
     */
    private fun forceCloseDialog(dialog: Dialog) {
        mainHandler.post {
            try {
                if (dialog.isShowing) dialog.dismiss()
            } catch (_: Exception) {
            }
            try {
                if (dialog.isShowing) dialog.cancel()
            } catch (_: Exception) {
            }
            try {
                val window = dialog.window
                val decor = window?.decorView
                decor?.visibility = View.GONE
            } catch (_: Exception) {
            }
        }
    }

    // ===== 3. Hook WindowManagerImpl.addView — 底层拦截 =====

    private fun hookWindowManagerAddView(classLoader: ClassLoader) {
        try {
            val wmClass = XposedHelpers.findClass("android.view.WindowManagerImpl", classLoader)
            XposedHelpers.findAndHookMethod(
                wmClass, "addView",
                View::class.java, ViewGroup.LayoutParams::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.args[0] as? View ?: return
                            if (containsClaimKeyword(view)) {
                                XposedBridge.log(TAG + " 底层拦截领取浮层(WindowManager)")
                                param.setResult(null)
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                    }
                }
            )
            XposedBridge.log(TAG + " WindowManagerImpl.addView Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " WindowManagerImpl.addView Hook 安装失败: $e")
        }
    }

    // ===== 4. Hook Activity.startActivity — 阻止活动页 =====

    private fun hookActivityStartActivity() {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "startActivity", Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[0] as? Intent ?: return

                            val cn = intent.component ?: return

                            val className = cn.className
                            for (kw in ACTIVITY_CLASS_KEYWORDS) {
                                if (className.contains(kw)) {
                                    XposedBridge.log(TAG + " 阻止活动Activity启动: $className")
                                    param.setResult(null)
                                    return
                                }
                            }

                            // 检查 Intent 的 data URL
                            val data = intent.data
                            if (data != null) {
                                val url = data.toString().lowercase()
                                for (kw in ACTIVITY_URL_KEYWORDS) {
                                    if (url.contains(kw.lowercase())) {
                                        XposedBridge.log(TAG + " 阻止活动URL跳转: $url")
                                        param.setResult(null)
                                        return
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                    }
                }
            )
            XposedBridge.log(TAG + " Activity.startActivity Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " Activity.startActivity Hook 安装失败: $e")
        }
    }

    // ===== 5. Hook PopupWindow — 阻止 PopupWindow 弹窗 =====

    private fun hookPopupWindow(classLoader: ClassLoader) {
        try {
            // Hook showAtLocation
            XposedHelpers.findAndHookMethod(
                "android.widget.PopupWindow", classLoader,
                "showAtLocation", View::class.java, Int::class.java, Int::class.java, Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val pw = param.thisObject as? android.widget.PopupWindow ?: return
                            val contentView = pw.contentView
                            if (contentView != null && containsClaimKeyword(contentView)) {
                                XposedBridge.log(TAG + " 阻止 PopupWindow 领取弹窗")
                                param.setResult(null)
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                    }
                }
            )

            // Hook showAsDropDown
            XposedHelpers.findAndHookMethod(
                "android.widget.PopupWindow", classLoader,
                "showAsDropDown", View::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val pw = param.thisObject as? android.widget.PopupWindow ?: return
                            val contentView = pw.contentView
                            if (contentView != null && containsClaimKeyword(contentView)) {
                                XposedBridge.log(TAG + " 阻止 PopupWindow(showAsDropDown) 领取弹窗")
                                param.setResult(null)
                            }
                        } catch (_: Exception) {
                            // 静默
                        }
                    }
                }
            )

            XposedBridge.log(TAG + " PopupWindow Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " PopupWindow Hook 安装失败: $e")
        }
    }

    // ===== 工具方法 =====

    /**
     * 检查 Dialog 是否含领取关键词
     */
    private fun containsClaimKeyword(dialog: Dialog): Boolean {
        return try {
            val window = dialog.window ?: return false
            val decorView = window.decorView ?: return false
            containsClaimKeyword(decorView)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 递归遍历 View 树，检查是否含领取关键词
     */
    private fun containsClaimKeyword(view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false

        if (view is TextView) {
            val text = view.text
            if (text != null) {
                val s = text.toString()
                for (kw in BLOCK_KEYWORDS) {
                    if (s.contains(kw)) return true
                }
            }
            // 检查 hint
            val hint = view.hint
            if (hint != null) {
                val s = hint.toString()
                for (kw in BLOCK_KEYWORDS) {
                    if (s.contains(kw)) return true
                }
            }
        }

        // 检查 contentDescription
        val desc = view.contentDescription
        if (desc != null) {
            val s = desc.toString()
            for (kw in BLOCK_KEYWORDS) {
                if (s.contains(kw)) return true
            }
        }

        if (view is ViewGroup) {
            val group = view
            for (i in 0 until group.childCount) {
                if (containsClaimKeyword(group.getChildAt(i))) return true
            }
        }

        return false
    }
}
