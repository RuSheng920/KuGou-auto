package com.moekoe.xposed.kugoulite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.moekoe.xposed.kugoulite.compat.XposedBridge

/**
 * 通知工具（使用原生 Notification.Builder，不依赖 AndroidX）
 */
object NotifyUtil {

    private const val TAG = "[KuGouLiteVip]"
    private const val CHANNEL_ID = "kugoulite_vip_channel"
    private const val CHANNEL_NAME = "酷狗概念版VIP领取"
    private const val NOTIFICATION_ID = 10086

    @Volatile
    private var channelCreated = false

    fun notifyResult(ctx: Context, result: KugouApi.ClaimResult) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            ensureChannel(nm)

            val content = result.message ?: "未知结果"

            val builder: Notification.Builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(ctx, CHANNEL_ID)
                } else {
                    Notification.Builder(ctx)
                }
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("酷狗概念版VIP")
                .setContentText(content)
                .setStyle(Notification.BigTextStyle().bigText(content))
                .setAutoCancel(true)

            nm.notify(NOTIFICATION_ID, builder.build())

        } catch (e: Exception) {
            XposedBridge.log(TAG + " 发送通知失败: $e")
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "每日VIP领取结果通知"
            nm.createNotificationChannel(channel)
        }
        channelCreated = true
    }
}
