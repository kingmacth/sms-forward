package com.smsfwd.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 收到短信广播后立即把短信内容 POST 到企业微信群机器人 Webhook。
 * 配置项存放在 SharedPreferences：
 *   webhook  - 企业微信群机器人 Webhook URL（必填）
 *   mention  - @的人（可选，逗号分隔，如 "zhangsan,lisi"）
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsFwd";
    private static final String PREFS = "smsfwd";
    private static final String PREF_WEBHOOK = "webhook";
    private static final String PREF_MENTION = "mention";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_MODE = "mode";
    private static final String PREF_TARGET = "target";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ENABLED, true)) return;

        SmsMessage[] messages = getMessages(intent);
        if (messages == null || messages.length == 0) return;

        StringBuilder body = new StringBuilder();
        String sender = "";
        for (SmsMessage m : messages) {
            if (sender.isEmpty()) sender = m.getDisplayOriginatingAddress();
            String text = m.getDisplayMessageBody();
            if (text == null) continue;
            if (body.length() > 0) body.append("\n");
            body.append(text);
        }
        if (body.length() == 0) return;

        final String smsBody = body.toString();
        final String smsSender = sender == null ? "" : sender;
        handleSms(context, prefs, smsSender, smsBody);
    }


    /** 解析广播中的短信 PDU */
    private static SmsMessage[] getMessages(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return null;
            Object[] pdus = (Object[]) extras.get("pdus");
            if (pdus == null) return null;
            SmsMessage[] out = new SmsMessage[pdus.length];
            for (int i = 0; i < pdus.length; i++) {
                out[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "解析短信失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 短信转发的核心处理（供模拟入口复用） */
    static void handleSms(Context context, SharedPreferences prefs,
                          final String smsSender, final String smsBody) {
        String webhook = prefs.getString(PREF_WEBHOOK, "");
        if (webhook == null) webhook = "";
        String mention = prefs.getString(PREF_MENTION, "");
        if (mention == null) mention = "";

        String mode = prefs.getString(PREF_MODE, "both");
        boolean useWx = "wx".equals(mode) || "both".equals(mode);
        boolean useHook = "hook".equals(mode) || "both".equals(mode);
        Log.i(TAG, "handleSms mode=" + mode + " useWx=" + useWx + " useHook=" + useHook
                + " sender=" + smsSender);

        // 通道 1：企业微信群机器人 Webhook（纯 HTTP，息屏也可靠）
        if (useHook && !webhook.trim().isEmpty()) {
            final String hookUrl = webhook.trim();
            final String mentions = mention.trim();
            final String hSender = smsSender;
            final String hBody = smsBody;
            new Thread(() -> {
                try {
                    post(hookUrl, mentions, hSender, hBody);
                    Log.i(TAG, "Webhook 转发成功: from=" + hSender);
                } catch (Exception e) {
                    Log.e(TAG, "Webhook 转发失败: " + e.getMessage(), e);
                }
            }).start();
        }

        // 通道 2：微信自动发送
        if (useWx) {
            String target = prefs.getString(PREF_TARGET, "");
            WxAutoService.enqueue(context.getApplicationContext(), target,
                    "【短信通知】\n来自：" + smsSender + "\n内容：" + smsBody);
            Log.i(TAG, "已投递到微信队列: from=" + smsSender);
            // 拉起前台服务，保证无障碍服务被系统绑定并维持运行
            try {
                Intent svc = new Intent(context.getApplicationContext(), KeepAliveService.class);
                context.getApplicationContext().startForegroundService(svc);
            } catch (Exception ignored) {
            }
            // 注册周期唤醒：息屏时系统禁止后台启动 Activity，靠 AlarmManager 唤醒进程重试
            WakeupReceiver.schedule(context.getApplicationContext());
            // 仅微信 UI 模式才需要点亮屏幕；Webhook 模式保持息屏不打扰
            if ("wx".equals(mode) || "both".equals(mode)) {
                try {
                    Intent ti = new Intent(context.getApplicationContext(), TurnOnActivity.class);
                    ti.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.getApplicationContext().startActivity(ti);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void post(String webhook, String mentions, String sender, String body) throws Exception {
        StringBuilder content = new StringBuilder();
        content.append("【短信通知】\n");
        content.append("来自：").append(sender).append("\n");
        content.append("内容：").append(body);
        if (!mentions.isEmpty()) {
            StringBuilder at = new StringBuilder();
            for (String m : mentions.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) {
                    if (at.length() > 0) at.append(" ");
                    at.append("<@").append(t).append(">");
                }
            }
            if (at.length() > 0) content.append("\n").append(at);
        }

        // 用 text 类型而非 markdown：
        // 企业微信推送到个人微信时不支持 markdown 消息类型，
        // 会显示为「暂不支持此消息类型，点击前往企业微信查看」
        JSONObject payload = new JSONObject();
        payload.put("msgtype", "text");
        JSONObject textObj = new JSONObject();
        textObj.put("content", content.toString());
        payload.put("text", textObj);

        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(webhook).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setFixedLengthStreamingMode(data.length);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + conn.getResponseMessage());
        }
        conn.disconnect();
    }
}
