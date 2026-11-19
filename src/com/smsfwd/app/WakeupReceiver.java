package com.smsfwd.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 定时唤醒器：息屏状态下系统可能禁止后台应用启动 Activity，
 * 用 AlarmManager 周期唤醒进程，让它能继续完成待发转发。
 * 这与真实短信广播唤醒是同一条系统通道（系统唤醒 -> 临时后台白名单）。
 */
public class WakeupReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsFwdWake";
    private static final String ACTION = "com.smsfwd.app.WAKEUP";
    private static final int REQUEST = 1001;
    private static final long INTERVAL = 60000; // 60 秒

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "定时唤醒");
        // 唤醒后尝试处理队列
        try {
            WxAutoService.triggerIfPending(context.getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "处理失败: " + e.getMessage(), e);
        }
        schedule(context);
        // Webhook 通道是纯 HTTP，不需要屏幕；
        // 仅当配置为微信 UI 模式时才需要点亮屏幕
        try {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("smsfwd", Context.MODE_PRIVATE);
            String mode = prefs.getString("mode", "hook");
            if ("wx".equals(mode) || "both".equals(mode)) {
                android.os.PowerManager pm = (android.os.PowerManager)
                        context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isInteractive()) {
                    Log.i(TAG, "微信模式，启动 TurnOnActivity 点亮");
                    Intent ti = new Intent(context.getApplicationContext(), TurnOnActivity.class);
                    ti.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(ti);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "点亮屏幕失败: " + e.getMessage(), e);
        }
    }

    /** 注册周期唤醒 */
    public static void schedule(Context context) {
        Log.i(TAG, "schedule() 被调用");
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context.getApplicationContext(), WakeupReceiver.class);
            i.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClockCompat.elapsedRealtime() + INTERVAL, pi);
            Log.i(TAG, "已注册周期唤醒 exact");
        } catch (Exception e) {
            Log.e(TAG, "注册唤醒失败: " + e.getMessage(), e);
        }
    }

    /** 取消周期唤醒 */
    public static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context.getApplicationContext(), WakeupReceiver.class);
            i.setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Exception ignored) {
        }
    }

    /** 兼容不同 Android 版本的 elapsedRealtime */
    static class SystemClockCompat {
        static long elapsedRealtime() {
            return android.os.SystemClock.elapsedRealtime();
        }
    }
}
