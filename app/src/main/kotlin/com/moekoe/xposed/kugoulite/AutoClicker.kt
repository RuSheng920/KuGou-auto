package com.moekoe.xposed.kugoulite

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import com.moekoe.xposed.kugoulite.compat.MethodHookParam
import com.moekoe.xposed.kugoulite.compat.XC_MethodHook
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import com.moekoe.xposed.kugoulite.compat.XposedHelpers

/**
 * 自动点击器（v1.5.0 Kotlin 重写）
 *
 * 酷狗概念版启动时会弹出活动领取弹窗，需要用户手动点击"领取"按钮。
 * 本模块自动识别并点击弹窗中的领取按钮，实现全自动领取。
 *
 * 覆盖三种弹窗类型：
 * 1. 原生 Dialog / AlertDialog（含 MaterialDialog 等第三方库）
 * 2. WebView 加载的 H5 活动页面
 * 3. Activity 形式的活动页面
 *
 * v1.5.0：注入/点击节奏加快 —— Dialog 500/1500ms → 250/750ms，
 * WebView 2000/4000/6000ms → 800/1600/2400ms（onPageFinished 已能抓住
 * 真实加载完成时机，loadUrl 的三连兜底相应提前）。
 */
object AutoClicker {

    private const val TAG = "[KuGouLiteVip]"

    // 自动点击关键词（匹配到则 performClick）
    private val CLICK_KEYWORDS = arrayOf(
        "领取", "签到", "立即领取", "去领取", "马上领取",
        "一键签到", "立即签到", "领取会员", "领取VIP", "领VIP",
        "点击领取", "免费领取"
    )

    // 活动 URL 关键词
    private val ACTIVITY_URL_KEYWORDS = arrayOf(
        "getvips", "receive_vip", "vip", "activity", "signin", "sign_in",
        "welfare", "listen_song", "recharge", "concept"
    )

    // 活动 Activity 类名关键词
    private val ACTIVITY_CLASS_KEYWORDS = arrayOf(
        "Sign", "Vip", "Welfare", "Receive", "Recharge", "WebActivity",
        "H5Activity", "BrowserActivity"
    )

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 防止重复点击：记录5秒内已点击的按钮文本
    private val clickedSet = HashSet<String>()
    private var lastCleanTime = 0L

    fun install(classLoader: ClassLoader) {
        if (installed.getAndSet(true)) return

        hookDialogShow()
        hookWebView(classLoader)
        hookActivityOnResume()
        hookWebViewClientOnPageFinished(classLoader)

        XposedBridge.log(TAG + " AutoClicker 安装完成")
    }

    // ===== 1. Hook Dialog.show() — 自动点击原生弹窗 =====

    private fun hookDialogShow() {
        try {
            XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val dialog = param.thisObject as? Dialog ?: return
                    // 延迟 250ms 等待布局加载完成
                    mainHandler.postDelayed({ tryAutoClickDialog(dialog) }, 250L)
                    // 二次尝试，防止布局延迟加载
                    mainHandler.postDelayed({ tryAutoClickDialog(dialog) }, 750L)
                }
            })
            XposedBridge.log(TAG + " Dialog.show Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " Dialog.show Hook 失败: $e")
        }
    }

    /**
     * 自动点击 Dialog 中的领取按钮，点击后关闭弹窗
     */
    private fun tryAutoClickDialog(dialog: Dialog) {
        try {
            if (!dialog.isShowing) return

            val window: Window = dialog.window ?: return
            val decorView = window.decorView ?: return

            val clickableViews = ArrayList<View>()
            findClickableViews(decorView, clickableViews)

            for (view in clickableViews) {
                if (view is TextView) {
                    val text = view.text ?: continue
                    val buttonText = text.toString().trim()

                    for (keyword in CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                XposedBridge.log(TAG + " 自动点击弹窗按钮: $buttonText")
                                view.performClick()
                                // 点击后延迟关闭弹窗
                                mainHandler.postDelayed({
                                    try {
                                        if (dialog.isShowing) {
                                            dialog.dismiss()
                                            XposedBridge.log(TAG + " 弹窗已关闭(dismiss)")
                                        }
                                    } catch (_: Exception) {
                                        try {
                                            dialog.cancel()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }, 500L)
                            }
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 静默处理
        }
    }

    // ===== 2. Hook WebView — 注入JS自动点击H5活动页面 =====

    private fun hookWebView(classLoader: ClassLoader) {
        // Hook loadUrl(String)
        try {
            XposedHelpers.findAndHookMethod(
                WebView::class.java, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        if (url.startsWith("javascript:")) return
                        if (isActivityUrl(url)) {
                            XposedBridge.log(TAG + " 检测到活动WebView: $url")
                            val webView = param.thisObject as? WebView ?: return
                            // 多次延迟注入，确保页面已加载完成
                            webView.postDelayed({ injectClickJs(webView) }, 800L)
                            webView.postDelayed({ injectClickJs(webView) }, 1600L)
                            webView.postDelayed({ injectClickJs(webView) }, 2400L)
                        }
                    }
                }
            )
            XposedBridge.log(TAG + " WebView.loadUrl Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " WebView.loadUrl Hook 失败: $e")
        }

        // Hook loadUrl(String, Map) 重载
        try {
            XposedHelpers.findAndHookMethod(
                WebView::class.java, "loadUrl",
                String::class.java, java.util.Map::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        if (url.startsWith("javascript:")) return
                        if (isActivityUrl(url)) {
                            val webView = param.thisObject as? WebView ?: return
                            webView.postDelayed({ injectClickJs(webView) }, 800L)
                            webView.postDelayed({ injectClickJs(webView) }, 1600L)
                        }
                    }
                }
            )
        } catch (_: Exception) {
            // 某些 Android 版本可能没有此重载
        }
    }

    /**
     * Hook WebViewClient.onPageFinished — 页面加载完成后注入JS
     */
    private fun hookWebViewClientOnPageFinished(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.webkit.WebViewClient", classLoader,
                "onPageFinished", WebView::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val webView = param.args[0] as? WebView ?: return
                        val url = param.args[1] as? String
                        if (url != null && isActivityUrl(url)) {
                            XposedBridge.log(TAG + " 活动页面加载完成: $url")
                            webView.postDelayed({ injectClickJs(webView) }, 600L)
                            webView.postDelayed({ injectClickJs(webView) }, 1400L)
                        }
                    }
                }
            )
            XposedBridge.log(TAG + " WebViewClient.onPageFinished Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " WebViewClient.onPageFinished Hook 安装失败: $e")
        }
    }

    /**
     * 判断 URL 是否为活动页面
     */
    private fun isActivityUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        for (keyword in ACTIVITY_URL_KEYWORDS) {
            if (lowerUrl.contains(keyword.lowercase())) return true
        }
        return false
    }

    /**
     * 注入 JS 自动点击页面上的领取按钮
     *
     * 注意：Kotlin 中行首的 "+" 会被解析为一元加（新语句），
     * 字符串拼接必须把 "+" 放在行尾。
     */
    private fun injectClickJs(webView: WebView) {
        try {
            val js = "javascript:(function(){" +
                "var kws=['领取','签到','立即领取','去领取','马上领取','一键签到','立即签到'," +
                "'领取会员','领取VIP','领VIP','点击领取','免费领取','确定','sign','receive','getVip'];" +
                "var sels='button,a,[class*=btn],[class*=submit],[class*=receive],[class*=sign]," +
                "[class*=get],[class*=vip],[role=button],input[type=button],input[type=submit],div[onclick]';" +
                "var els=document.querySelectorAll(sels);" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].innerText||els[i].textContent||els[i].value||'').trim();" +
                "  for(var j=0;j<kws.length;j++){" +
                "    if(t.indexOf(kws[j])>=0){" +
                "      els[i].click();" +
                "      return true;" +
                "    }" +
                "  }" +
                "}" +
                "})();"
            webView.loadUrl(js)
            XposedBridge.log(TAG + " 已注入自动点击JS")
        } catch (_: Exception) {
        }
    }

    // ===== 3. Hook Activity.onResume() — 自动点击Activity中的领取按钮 =====

    private fun hookActivityOnResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val className = activity.javaClass.name

                    var isActivityPage = false
                    for (keyword in ACTIVITY_CLASS_KEYWORDS) {
                        if (className.contains(keyword)) {
                            isActivityPage = true
                            break
                        }
                    }

                    if (isActivityPage) {
                        XposedBridge.log(TAG + " 检测到活动Activity: $className")
                        val decorView = activity.window?.decorView
                        mainHandler.postDelayed({
                            val clicked = tryAutoClickView(decorView)
                            if (clicked) {
                                // 点击成功后延迟关闭 Activity
                                mainHandler.postDelayed({
                                    try {
                                        activity.finish()
                                        XposedBridge.log(TAG + " 活动Activity已关闭")
                                    } catch (_: Exception) {
                                        // 静默
                                    }
                                }, 800L)
                            }
                        }, 500L)
                        mainHandler.postDelayed({ tryAutoClickView(decorView) }, 1200L)
                    }
                }
            })
            XposedBridge.log(TAG + " Activity.onResume Hook 安装成功")
        } catch (e: Exception) {
            XposedBridge.log(TAG + " Activity.onResume Hook 安装失败: $e")
        }
    }

    /**
     * 自动点击 View 树中的领取按钮
     * @return true 表示点击成功
     */
    private fun tryAutoClickView(rootView: View?): Boolean {
        return try {
            if (rootView == null) return false
            val clickableViews = ArrayList<View>()
            findClickableViews(rootView, clickableViews)

            for (view in clickableViews) {
                if (view is TextView) {
                    val text = view.text ?: continue
                    val buttonText = text.toString().trim()

                    for (keyword in CLICK_KEYWORDS) {
                        if (buttonText.contains(keyword)) {
                            if (shouldClick(buttonText)) {
                                XposedBridge.log(TAG + " 自动点击按钮: $buttonText")
                                view.performClick()
                            }
                            return true
                        }
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ===== 工具方法 =====

    /**
     * 递归查找所有可点击的 View（Button 或可点击的 TextView）
     */
    private fun findClickableViews(view: View?, out: MutableList<View>) {
        if (view == null || view.visibility != View.VISIBLE) return

        if (view is Button || (view is TextView && view.isClickable)) {
            out.add(view)
        }

        if (view is ViewGroup) {
            val group = view
            for (i in 0 until group.childCount) {
                findClickableViews(group.getChildAt(i), out)
            }
        }
    }

    /**
     * 检查是否应该点击（5秒内去重，防止重复点击）
     */
    private fun shouldClick(buttonText: String): Boolean {
        val now = System.currentTimeMillis()
        // 每30秒清理一次
        if (now - lastCleanTime > 30000) {
            clickedSet.clear()
            lastCleanTime = now
        }

        val key = buttonText + "_" + (now / 5000) // 5秒窗口
        if (clickedSet.contains(key)) {
            return false
        }
        clickedSet.add(key)
        return true
    }
}
