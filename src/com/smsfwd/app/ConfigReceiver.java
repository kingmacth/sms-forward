package com.smsfwd.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 通过 adb 广播设置配置，避免必须打开应用界面：
 *   adb shell am broadcast -a com.smsfwd.app.SETTINGS \
 *       --es mode wx --es target <联系人昵称> --es webhook <url> --es mention <names> --ez enabled true
 *   adb shell am broadcast -a com.smsfwd.app.TEST
 */
public class ConfigReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsFwdCfg";
    private static final String PREFS = "smsfwd";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final String action = intent.getAction();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                handle(context, intent, action);
            } catch (Exception e) {
                Log.e(TAG, "处理失败: " + e.getMessage(), e);
            } finally {
                pending.finish();
            }
        }).start();
    }

    private void handle(Context context, Intent intent, String action) {

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();

        if ("com.smsfwd.app.SETTINGS".equals(action)) {
            String mode = intent.getStringExtra("mode");
            String target = intent.getStringExtra("target");
            String webhook = intent.getStringExtra("webhook");
            String mention = intent.getStringExtra("mention");
            boolean enabled = intent.getBooleanExtra("enabled", true);

            if (mode != null) ed.putString("mode", mode);
            if (target != null) ed.putString("target", target);
            if (webhook != null) ed.putString("webhook", webhook);
            if (mention != null) ed.putString("mention", mention);
            ed.putBoolean("enabled", enabled);
            ed.apply();
            Log.i(TAG, "配置已更新: mode=" + mode + " target=" + target);
            return;
        }

        if ("com.smsfwd.app.DUMP".equals(action)) {
            try {
                WxAutoService.dumpScreenText();
            } catch (Exception e) {
                Log.e(TAG, "dump 失败: " + e.getMessage(), e);
            }
            return;
        }

        if ("com.smsfwd.app.SIMULATE".equals(action)) {
            // 模拟一条真实短信：走 SmsReceiver 完全相同的处理路径
            String sender = intent.getStringExtra("sender");
            String text = intent.getStringExtra("text");
            if (sender == null) sender = "10086";
            if (text == null) text = "【模拟】验证码 123456，5分钟内有效。";
            Log.i(TAG, "模拟短信: from=" + sender);
            try {
                SharedPreferences sp = context.getSharedPreferences("smsfwd", Context.MODE_PRIVATE);
                SmsReceiver.handleSms(context, sp, sender, text);
                Log.i(TAG, "模拟短信处理完成");
            } catch (Exception e) {
                Log.e(TAG, "模拟短信失败: " + e.getMessage(), e);
            }
            return;
        }

        if ("com.smsfwd.app.TEST".equals(action)) {
            String target = intent.getStringExtra("target");
            if (target == null || target.isEmpty()) {
                target = prefs.getString("target", "");
            }
            WxAutoService.enqueue(context.getApplicationContext(), target,
                    "【测试】短信转发功能已配置成功 ✅");
            Log.i(TAG, "已触发测试转发: target=" + target);
        }
    }

}
