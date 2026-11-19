package com.smsfwd.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：重启后重新拉起无障碍服务（系统有时会在重启后重置服务绑定）。
 * 同时起到「保活」作用——把服务重新拉起。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsFwdBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "开机自启: " + action);
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "拉起失败: " + e.getMessage(), e);
        }
    }
}
