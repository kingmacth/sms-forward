package com.smsfwd.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 配置界面：填写企业微信群机器人 Webhook、@的人，开关转发，并可发测试消息。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "smsfwd";
    private static final String PREF_WEBHOOK = "webhook";
    private static final String PREF_MENTION = "mention";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_MODE = "mode";
    private static final String PREF_TARGET = "target";

    private EditText webhookEt;
    private EditText mentionEt;
    private EditText targetEt;
    private RadioGroup modeRg;
    private TextView statusTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 注册周期唤醒（息屏时保障转发能继续）
        WakeupReceiver.schedule(getApplicationContext());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        webhookEt = new EditText(this);
        mentionEt = new EditText(this);
        targetEt = new EditText(this);
        webhookEt.setText(prefs.getString(PREF_WEBHOOK, ""));
        mentionEt.setText(prefs.getString(PREF_MENTION, ""));
        targetEt.setText(prefs.getString(PREF_TARGET, ""));
        webhookEt.setHint("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...");
        mentionEt.setHint("选填，逗号分隔，如 zhangsan,lisi");
        targetEt.setHint("微信联系人昵称（留空则用最近会话首项）");
        webhookEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mentionEt.setInputType(InputType.TYPE_CLASS_TEXT);
        targetEt.setInputType(InputType.TYPE_CLASS_TEXT);

        // 转发模式选择
        modeRg = new RadioGroup(this);
        modeRg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton wxBtn = new RadioButton(this);
        wxBtn.setText("微信");
        wxBtn.setTextColor(Color.WHITE);
        wxBtn.setId(1);
        RadioButton hookBtn = new RadioButton(this);
        hookBtn.setText("企微Webhook");
        hookBtn.setTextColor(Color.WHITE);
        hookBtn.setId(2);
        RadioButton bothBtn = new RadioButton(this);
        bothBtn.setText("两者同时");
        bothBtn.setTextColor(Color.WHITE);
        bothBtn.setId(3);
        modeRg.addView(wxBtn);
        modeRg.addView(hookBtn);
        modeRg.addView(bothBtn);
        String mode = prefs.getString(PREF_MODE, "both");
        if ("wx".equals(mode)) modeRg.check(1);
        else if ("hook".equals(mode)) modeRg.check(2);
        else modeRg.check(3);
        modeRg.setOnCheckedChangeListener((g, checkedId) -> {
            boolean isWx = (checkedId == 1 || checkedId == 3);
            boolean isHook = (checkedId == 2 || checkedId == 3);
            targetEt.setVisibility(isWx ? View.VISIBLE : View.GONE);
            webhookEt.setVisibility(isHook ? View.VISIBLE : View.GONE);
            mentionEt.setVisibility(isHook ? View.VISIBLE : View.GONE);
        });

        Switch enableSw = new Switch(this);
        enableSw.setChecked(prefs.getBoolean(PREF_ENABLED, true));
        enableSw.setText("启用转发");
        enableSw.setTextColor(Color.WHITE);

        statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#CCCCCC"));
        statusTv.setTextSize(12);
        statusTv.setPadding(0, 12, 0, 0);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存设置");
        saveBtn.setOnClickListener(v -> save());

        Button testBtn = new Button(this);
        testBtn.setText("发送测试消息");
        testBtn.setOnClickListener(v -> sendTest());

        Button openWxBtn = new Button(this);
        openWxBtn.setText("打开微信");
        openWxBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent();
                i.setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                toast("无法打开微信");
            }
        });

        Button batteryBtn = new Button(this);
        batteryBtn.setText("忽略电池优化（建议开启）");
        batteryBtn.setOnClickListener(v -> {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    toast("已在白名单中");
                } else {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                toast("无法跳转，请到系统设置中手动允许");
            }
        });

        Button a11yBtn = new Button(this);
        a11yBtn.setText("开启无障碍服务（必需）");
        a11yBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(i);
            } catch (Exception e) {
                toast("无法打开无障碍设置");
            }
        });

        Button testWxBtn = new Button(this);
        testWxBtn.setText("测试微信发送");
        testWxBtn.setOnClickListener(v -> {
            save();
            WxAutoService.enqueue(getApplicationContext(), targetEt.getText().toString().trim(),
                    "【测试】短信转发功能已配置成功 ✅");
            statusTv.setText("已触发微信发送，请观察屏幕…");
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(Color.parseColor("#12151A"));

        TextView title = new TextView(this);
        title.setText("短信转发");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("收到短信后立即通过微信转发给你指定的联系人。需先开启「无障碍服务」并允许本应用后台运行。");
        desc.setTextColor(Color.parseColor("#AAAAAA"));
        desc.setTextSize(13);
        desc.setPadding(0, 0, 0, 24);
        root.addView(desc);

        root.addView(enableSw);
        addLabel(root, "转发方式");
        root.addView(modeRg);
        addLabel(root, "微信联系人昵称");
        root.addView(targetEt);
        addLabel(root, "群机器人 Webhook");
        root.addView(webhookEt);
        addLabel(root, "@ 的人（可选）");
        root.addView(mentionEt);
        root.addView(saveBtn);
        root.addView(testWxBtn);
        root.addView(batteryBtn);
        root.addView(a11yBtn);
        root.addView(openWxBtn);
        root.addView(statusTv);

        // 初始显示状态
        int mid = modeRg.getCheckedRadioButtonId();
        boolean isWx = (mid == 1 || mid == 3);
        boolean isHook = (mid == 2 || mid == 3);
        targetEt.setVisibility(isWx ? View.VISIBLE : View.GONE);
        webhookEt.setVisibility(isHook ? View.VISIBLE : View.GONE);
        mentionEt.setVisibility(isHook ? View.VISIBLE : View.GONE);

        // 支持命令行触发：am start -n com.smsfwd.app/.MainActivity --es cmd test --es target <联系人昵称>
        handleCommand(getIntent());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#12151A"));
        sv.addView(root);
        setContentView(sv);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#8AB4F8"));
        tv.setTextSize(13);
        tv.setPadding(0, 16, 0, 4);
        parent.addView(tv);
    }

    private void save() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int mid = modeRg.getCheckedRadioButtonId();
        String mode = (mid == 1) ? "wx" : (mid == 2 ? "hook" : "both");
        prefs.edit()
                .putString(PREF_WEBHOOK, webhookEt.getText().toString().trim())
                .putString(PREF_MENTION, mentionEt.getText().toString().trim())
                .putString(PREF_TARGET, targetEt.getText().toString().trim())
                .putString(PREF_MODE, mode)
                .putBoolean(PREF_ENABLED, true)
                .apply();
        toast("已保存");
    }

    private void sendTest() {
        final String url = webhookEt.getText().toString().trim();
        if (url.isEmpty()) {
            toast("请先填写 Webhook");
            return;
        }
        save();
        statusTv.setText("正在发送测试消息…");
        new Thread(() -> {
            String result;
            try {
                JSONObject payload = new JSONObject();
                payload.put("msgtype", "markdown");
                JSONObject md = new JSONObject();
                md.put("content", "**测试消息**\n短信转发功能已配置成功 ✅");
                payload.put("markdown", md);
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
                result = (code == 200) ? "✅ 发送成功（HTTP 200）" : "❌ 失败 HTTP " + code;
                conn.disconnect();
            } catch (Exception e) {
                result = "❌ 失败：" + e.getMessage();
            }
            final String r = result;
            new Handler(Looper.getMainLooper()).post(() -> statusTv.setText(r));
        }).start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 处理命令行传入的指令 */
    private void handleCommand(Intent intent) {
        if (intent == null) return;
        Bundle extras = intent.getExtras();
        if (extras == null) return;
        String cmd = extras.getString("cmd");
        android.util.Log.i("SmsFwdMain", "handleCommand cmd=" + cmd);
        if (cmd == null) return;
        if ("test".equals(cmd)) {
            String t = extras.getString("target");
            if (t == null || t.isEmpty()) t = targetEt.getText().toString().trim();
            save();
            WxAutoService.enqueue(getApplicationContext(), t,
                    "【测试】短信转发功能已配置成功 ✅");
            statusTv.setText("已触发微信发送");
            setResult(0);
        } else if ("set".equals(cmd)) {
            String cmdMode = extras.getString("mode");
            String target = extras.getString("target");
            String webhook = extras.getString("webhook");
            String mention = extras.getString("mention");
            if (cmdMode != null) modeRg.check("wx".equals(cmdMode) ? 1 : ("hook".equals(cmdMode) ? 2 : 3));
            if (target != null) targetEt.setText(target);
            if (webhook != null) webhookEt.setText(webhook);
            if (mention != null) mentionEt.setText(mention);
            // 直接写配置，避免 UI 状态与实际值不一致
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            int mid = modeRg.getCheckedRadioButtonId();
            String m = (mid == 1) ? "wx" : (mid == 2 ? "hook" : "both");
            sp.edit()
                    .putString(PREF_MODE, m)
                    .putString(PREF_TARGET, targetEt.getText().toString().trim())
                    .putString(PREF_WEBHOOK, webhookEt.getText().toString().trim())
                    .putString(PREF_MENTION, mentionEt.getText().toString().trim())
                    .putBoolean(PREF_ENABLED, true)
                    .commit();
            android.util.Log.i("SmsFwdMain", "配置已写入 mode=" + m);
            statusTv.setText("配置已更新 mode=" + m);
            setResult(0);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }
}
