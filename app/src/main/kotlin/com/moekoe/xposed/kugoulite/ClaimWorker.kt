package com.moekoe.xposed.kugoulite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 领取编排器（v1.5.0 重写）
 *
 * ============ 1.4.0 的延迟账（本版逐条修掉） ============
 *  1. 触发太晚：只有 Application.onCreate + Activity.onResume（节流 10 秒）
 *     → 新增 attachBaseContext 最早触发；resume 节流 10s → 3s
 *  2. 轮询太慢：看门狗固定 5 秒一次，最长追 10 分钟
 *     → 渐进轮询 1s → 2s → 3s → 5s
 *  3. 重试太慢：领取失败固定等 8 秒 × 3 次
 *     → 2s / 4s 退避重试
 *  4. 重 IO：每次轮询全量遍历酷狗 SP 文件
 *     → 扫描成功后 5 分钟缓存、失败退避 10 秒
 *  5. 关键路径跨进程：每次判断都同步 ContentProvider 读配置、同步回写状态
 *     → 配置进程内秒读 + 异步刷新；状态回写全异步
 *
 * ============ 自动升级修复 ============
 *  1.4.0 升级失败一次即放弃，且跨进程读 auto_upgrade 失败时默认 false，
 *  导致"每次升级都要进模块手动点触发"。本版：
 *  - auto_upgrade 默认开启，配置读缓存化 + 镜像兜底，开关真实生效；
 *  - 升级独立重试（3 秒 × 2 次），与领取状态解耦：
 *    已领取未升级（如早上关着升级开关、晚上打开）也能单独补升级；
 *  - 手动触发广播会重置"升级完成"标记，重跑时若今日已领取会直接补升级。
 */
object ClaimWorker {

    const val ACTION_TRIGGER = "com.moekoe.xposed.kugoulite.ACTION_TRIGGER"
    const val ACTION_CONFIG_CHANGED = "com.moekoe.xposed.kugoulite.ACTION_CONFIG_CHANGED"

    private const val WATCH_TOTAL_MS = 10 * 60 * 1000L   // 最长追 10 分钟
    private const val CLAIM_MAX_TRY = 3                  // 领取最多试几次
    private const val CLAIM_RETRY_GAP_MS = 2000L         // 领取重试基数（第 n 次退避 n*2s）
    private const val KICK_THROTTLE_MS = 3000L           // 触发节流（1.4.0 为 10s）
    private const val UPGRADE_MAX_TRY = 2                // 升级最多试几次
    private const val UPGRADE_RETRY_GAP_MS = 3000L       // 升级重试间隔
    private const val CONFIG_REFRESH_GAP_MS = 60_000L    // 配置后台刷新周期

    private val started = AtomicBoolean(false)
    private val attempting = AtomicBoolean(false)
    private val watcherRunning = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCreds: KugouApi.Credentials? = null

    @Volatile
    private var claimedInThisProcess = false

    @Volatile
    private var lastKickAt = 0L

    /**
     * 启动领取工作器（由 Installer 在 attachBaseContext / onCreate 调用，幂等）
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        NetCapture.attachContext(context)

        if (!started.compareAndSet(false, true)) {
            // 已启动过：attachBaseContext 与 onCreate 只会命中一个主路径，
            // 但若先来的是 onCreate（attach 钩子失效），这里补一次触发
            kick("start-again")
            return
        }

        ModuleLog.log("ClaimWorker 启动")

        clearResetFlagIfNeeded()

        // 先装回调：NetCapture 内部会把"回调未就绪时已经捕到"的凭证补发一次
        NetCapture.setCallback { creds -> onCredsCaptured(creds) }

        registerTriggerReceiver()

        kick("Application 启动")
    }

    /**
     * 统一的触发入口：确保看门狗在跑，并立刻检查一次凭证。
     */
    fun kick(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastKickAt < KICK_THROTTLE_MS) return
        lastKickAt = now

        ModuleLog.log("触发领取检查：$source")
        startWatcher()

        val creds = resolveCreds()
        if (creds != null) {
            cachedCreds = creds
            attemptClaim()
        } else {
            ModuleLog.log("暂无凭证，看门狗继续等（$source）")
        }
    }

    /** 忽略节流，强制触发（模块里手点用） */
    fun kickNow(source: String) {
        lastKickAt = 0L
        kick(source)
    }

    /** 领取是否已完成（成功或服务器确认今日已领） */
    fun isClaimDone(): Boolean {
        if (claimedInThisProcess) return true
        val ctx = appContext ?: return false
        return try {
            PrefStore.isClaimedSuccessToday(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    /** 今日全部任务（领取 + 升级）是否已完成 */
    fun isAllDone(): Boolean {
        val ctx = appContext ?: return false
        return isClaimDone() && (!Settings.isAutoUpgrade(ctx) || isUpgradeDone())
    }

    /** 升级是否已完成 */
    fun isUpgradeDone(): Boolean {
        val ctx = appContext ?: return false
        return try {
            PrefStore.isUpgradeDoneToday(ctx)
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ 看门狗

    private fun startWatcher() {
        if (watcherRunning.getAndSet(true)) return
        Thread(ClaimWorker::watchLoop, "KuGouLiteVip-Watch").start()
    }

    private fun watchLoop() {
        val deadline = System.currentTimeMillis() + WATCH_TOTAL_MS
        ModuleLog.log("凭证看门狗启动，最长追 " + (WATCH_TOTAL_MS / 60000) + " 分钟")
        var poll = 0
        var lastConfigRefreshAt = 0L
        try {
            while (System.currentTimeMillis() < deadline) {
                if (isAllDone()) {
                    ModuleLog.log("看门狗：今日任务已完成，退出")
                    break
                }
                val creds = resolveCreds()
                if (creds != null) {
                    cachedCreds = creds
                    attemptClaim()
                }

                // 配置后台刷新：模块 APP 里改了开关，最多 60 秒后这里生效
                val ctx = appContext
                if (ctx != null && System.currentTimeMillis() - lastConfigRefreshAt > CONFIG_REFRESH_GAP_MS) {
                    lastConfigRefreshAt = System.currentTimeMillis()
                    Settings.refreshAsync(ctx)
                }

                poll++
                val gap = when {
                    poll <= 2 -> 1000L
                    poll <= 5 -> 2000L
                    poll <= 10 -> 3000L
                    else -> 5000L
                }
                Thread.sleep(gap)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            ModuleLog.log("看门狗异常: $t")
        } finally {
            watcherRunning.set(false)
            ModuleLog.log("凭证看门狗结束")
        }
    }

    /**
     * 四处兜底取凭证：最新捕获 → 进程内缓存 → 模块缓存 SP → 酷狗自身 SP（带退避）
     */
    private fun resolveCreds(): KugouApi.Credentials? {
        val ctx = appContext ?: return null

        try {
            val captured = NetCapture.getCapturedCreds()
            if (captured != null && captured.isValid()) {
                PrefStore.saveCreds(ctx, captured)
                return captured
            }
        } catch (_: Throwable) {
        }

        cachedCreds?.let { if (it.isValid()) return it }

        try {
            val creds = PrefStore.loadCreds(ctx)
            if (creds != null && creds.isValid()) {
                cachedCreds = creds
                return creds
            }
        } catch (_: Throwable) {
        }

        try {
            val creds = PrefStore.tryReadFromAppPrefs(ctx)
            if (creds != null && creds.isValid()) {
                ModuleLog.log("从酷狗SP取到凭证: userid=" + creds.userid)
                PrefStore.saveCreds(ctx, creds)
                cachedCreds = creds
                return creds
            }
        } catch (_: Throwable) {
        }

        return null
    }

    // ------------------------------------------------------------ 领取 + 升级

    private fun onCredsCaptured(creds: KugouApi.Credentials) {
        ModuleLog.log("NetCapture 捕获到凭证: userid=" + creds.userid)
        val ctx = appContext
        if (ctx != null) {
            try {
                PrefStore.saveCreds(ctx, creds)
            } catch (_: Throwable) {
            }
        }
        cachedCreds = creds
        attemptClaim()
    }

    private fun attemptClaim() {
        if (attempting.getAndSet(true)) {
            ModuleLog.log("已有领取任务进行中，跳过")
            return
        }

        val ctx = appContext
        if (ctx == null) {
            attempting.set(false)
            return
        }

        if (!Settings.isEnabled(ctx)) {
            ModuleLog.log("模块已禁用")
            Settings.putStatus(ctx, "模块已禁用", KugouApi.CODE_ERROR)
            attempting.set(false)
            return
        }

        val claimDone = isClaimDone()
        val needUpgrade = !claimDone || (!isUpgradeDone() && Settings.isAutoUpgrade(ctx))

        if (claimDone && !needUpgrade) {
            ModuleLog.log("今日已成功领取，跳过")
            Settings.putStatus(ctx, "今日已领取", KugouApi.CODE_ALREADY)
            attempting.set(false)
            return
        }

        if (claimDone) {
            // 领取早就完成，只差升级 —— 直接补升级，不再碰领取接口
            ModuleLog.log("今日已领取但升级未完成，直接补升级")
        }

        val creds = cachedCreds
        if (creds == null || !creds.isValid()) {
            ModuleLog.log("凭证无效，跳过领取")
            attempting.set(false)
            return
        }

        Thread(Runnable {
            try {
                // 首次使用前同步初始化配置（此后全部进程内秒读）
                Settings.ensureInitialized(ctx)

                if (!isClaimDone()) {
                    runClaimPhase(ctx)
                } else {
                    ModuleLog.log("跳过领取阶段（今日已领取）")
                }

                if (isClaimDone() && Settings.isAutoUpgrade(ctx) && !isUpgradeDone()) {
                    runUpgradePhase(ctx)
                }
            } catch (t: Throwable) {
                ModuleLog.log("领取过程异常: $t")
                Settings.putStatus(ctx, "领取异常: " + t.message, KugouApi.CODE_ERROR)
            } finally {
                attempting.set(false)
            }
        }, "KuGouLiteVip-Claim").start()
    }

    /** 领取阶段：立即首试，失败 2s / 4s 退避重试 */
    private fun runClaimPhase(ctx: Context) {
        for (attempt in 1..CLAIM_MAX_TRY) {
            val current = resolveCreds()
            if (current == null || !current.isValid()) {
                ModuleLog.log("第 $attempt 次尝试：凭证不可用，等看门狗重来")
                return
            }
            cachedCreds = current

            ModuleLog.log("第 $attempt 次领取尝试: userid=" + current.userid)
            val result = KugouApi.claimVip(current)
            ModuleLog.log("领取结果: code=" + result.code + " msg=" + result.message)

            if (result.code == KugouApi.CODE_SUCCESS || result.code == KugouApi.CODE_ALREADY) {
                claimedInThisProcess = true
                PrefStore.setClaimedSuccessToday(ctx, true)
                PrefStore.setClaimedToday(ctx, true)
                Settings.putStatus(ctx, result.message, result.code)
                NotifyUtil.notifyResult(ctx, result)
                return
            }

            // 失败：先回写状态，再退避重试
            Settings.putStatus(ctx, result.message, result.code)
            if (result.code == KugouApi.CODE_RISK) {
                // 风控不重试，免得越试越难看
                ModuleLog.log("账号风控，本轮不再重试")
                return
            }
            if (attempt < CLAIM_MAX_TRY) {
                Thread.sleep(CLAIM_RETRY_GAP_MS * attempt)
            }
        }
        ModuleLog.log("领取尝试结束（未成功），交给看门狗继续")
    }

    /** 升级阶段：立即首试，失败 3s 后退避重试一次；失败则留给看门狗/手动触发 */
    private fun runUpgradePhase(ctx: Context) {
        ModuleLog.log("开始升级概念版VIP...")
        for (attempt in 1..UPGRADE_MAX_TRY) {
            val current = resolveCreds()
            if (current == null || !current.isValid()) {
                ModuleLog.log("升级第 $attempt 次：凭证不可用")
                return
            }
            cachedCreds = current

            val upgrade = KugouApi.upgradeVip(current)
            ModuleLog.log("升级结果: code=" + upgrade.code + " msg=" + upgrade.message)

            if (upgrade.code == KugouApi.CODE_SUCCESS || upgrade.code == KugouApi.CODE_ALREADY) {
                PrefStore.setUpgradeDoneToday(ctx, true)
                Settings.putStatus(ctx, upgrade.message, KugouApi.CODE_SUCCESS)
                NotifyUtil.notifyResult(ctx, upgrade)
                return
            }

            Settings.putStatus(ctx, upgrade.message, upgrade.code)
            if (attempt < UPGRADE_MAX_TRY) {
                Thread.sleep(UPGRADE_RETRY_GAP_MS)
            }
        }
        ModuleLog.log("升级未完成，留给看门狗/手动触发重试")
    }

    // ------------------------------------------------------------ 重置标记

    private fun clearResetFlagIfNeeded() {
        val ctx = appContext ?: return
        try {
            val uri = android.net.Uri.parse("content://com.moekoe.xposed.kugoulite.state")
            val result = ctx.contentResolver.call(uri, "check_reset_claim", null, null)
            if (result != null && result.getBoolean("value", false)) {
                ModuleLog.log("检测到重置标记，清除领取与升级状态")
                PrefStore.setClaimedToday(ctx, false)
                PrefStore.setClaimedSuccessToday(ctx, false)
                PrefStore.setUpgradeDoneToday(ctx, false)
                claimedInThisProcess = false
            }
        } catch (_: Exception) {
            try {
                val moduleCtx = ctx.createPackageContext(
                    "com.moekoe.xposed.kugoulite", Context.CONTEXT_IGNORE_SECURITY
                )
                val sp = moduleCtx.getSharedPreferences("module_state", Context.MODE_PRIVATE)
                if (sp.getBoolean("reset_claim_pending", false)) {
                    sp.edit().remove("reset_claim_pending").apply()
                    ModuleLog.log("检测到重置标记(文件)，清除领取与升级状态")
                    PrefStore.setClaimedToday(ctx, false)
                    PrefStore.setClaimedSuccessToday(ctx, false)
                    PrefStore.setUpgradeDoneToday(ctx, false)
                    claimedInThisProcess = false
                }
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ 广播

    /**
     * 注册手动触发 / 配置变更广播接收器。
     * 说明：Android 没有"某应用启动"的系统广播，外部收不到，
     * 所以自动触发靠的是进程内 attachBaseContext / onCreate / onResume 这些 hook；
     * 这里的广播用于模块 APP 的交互（手动触发、开关变更）。
     */
    private fun registerTriggerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    if (intent == null) return
                    val ctx = appContext ?: return
                    when (intent.action) {
                        ACTION_TRIGGER -> {
                            ModuleLog.log("收到手动触发广播，强制重新领取/升级")
                            PrefStore.setClaimedToday(ctx, false)
                            PrefStore.setClaimedSuccessToday(ctx, false)
                            PrefStore.setUpgradeDoneToday(ctx, false)
                            claimedInThisProcess = false
                            kickNow("模块手动触发")
                        }
                        ACTION_CONFIG_CHANGED -> {
                            ModuleLog.log("收到配置变更广播，刷新配置缓存")
                            Settings.refreshAsync(ctx)
                        }
                    }
                }
            }

            val filter = IntentFilter()
            filter.addAction(ACTION_TRIGGER)
            filter.addAction(ACTION_CONFIG_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext?.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext?.registerReceiver(receiver, filter)
            }
            ModuleLog.log("广播接收器注册成功")
        } catch (e: Exception) {
            receiverRegistered.set(false)
            ModuleLog.log("注册广播接收器失败: $e")
        }
    }
}
