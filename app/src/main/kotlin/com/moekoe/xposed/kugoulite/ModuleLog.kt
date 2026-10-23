package com.moekoe.xposed.kugoulite

import android.content.Context
import android.os.SystemClock
import com.moekoe.xposed.kugoulite.compat.XposedBridge
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志工具，写入酷狗私有目录。
 *
 * v1.5.0：每行带 [ +启动后毫秒数 ] 前缀，方便直接衡量领取链路延迟。
 */
object ModuleLog {

    private const val TAG = "[KuGouLiteVip]"
    private const val FALLBACK_FILE =
        "/data/data/com.kugou.android.lite/files/kugou_vip_module.log"

    @Volatile
    private var logFile: String? = null

    private val t0 = SystemClock.elapsedRealtime()

    fun init(ctx: Context) {
        try {
            logFile = ctx.filesDir.absolutePath + "/kugou_vip_module.log"
            rotateIfTooBig()
        } catch (_: Exception) {
            logFile = FALLBACK_FILE
        }
    }

    private fun rotateIfTooBig() {
        try {
            val file = File(logFile ?: return)
            if (file.exists() && file.length() > 1024 * 1024) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun log(msg: String) {
        val elapsed = SystemClock.elapsedRealtime() - t0
        val line = "[+" + elapsed + "ms] "
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val full = line + time + " " + msg
        XposedBridge.log(TAG + " " + full)
        append(full)
    }

    private fun append(line: String) {
        try {
            if (logFile == null) {
                logFile = FALLBACK_FILE
            }
            val file = File(logFile)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(file, true).use { fos ->
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // 日志写失败不能影响主流程
        }
    }
}
