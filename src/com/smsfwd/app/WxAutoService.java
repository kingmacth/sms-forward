package com.smsfwd.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务：收到短信后自动通过微信转发给指定联系人。
 *
 * 微信真实流程（SEND + text/plain 分享）：
 *   1. com.tencent.mm.ui.LauncherUI / ShareImgUI  -> 分享页
 *   2. com.tencent.mm.ui.transmit.SelectConversationUI -> 「选择一个聊天」（选联系人）
 *   3. 点击联系人 -> 进入 ChatUI（聊天页）
 *   4. 点击输入框 -> 输入文本 -> 点击「发送」
 *
 * 因为微信界面多为自绘，uiautomator dump 拿不到节点，
 * 因此以「当前 Activity 名 + 屏幕坐标」双轨定位，坐标按 1080x2088 基准换算。
 */
public class WxAutoService extends AccessibilityService {

    private static final String TAG = "WxAuto";
    private static final String PREFS = "smsfwd";
    private static final String PREF_TARGET = "target";
    private static final String PREF_QUEUE = "queue";

    private static WxAutoService instance;

    /** 待发队列，持久化在 SharedPreferences，避免进程被杀丢失 */
    private static final String KEY_QUEUE = "pending";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String currentActivity = "";
    private String pendingTarget = null;
    private String pendingText = null;
    private int stage = 0;
    private long lastAction = 0;

    /** 屏幕宽高，用于坐标换算 */
    private int sw = 1080, sh = 2088;

    public static void enqueue(Context ctx, String target, String text) {
        // 持久化队列（JSON 数组，避免分隔符问题）
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("target", target);
            o.put("text", text);
            arr.put(o);
            prefs.edit().putString(KEY_QUEUE, arr.toString()).commit();
        } catch (Exception e) {
            Log.e(TAG, "队列写入失败: " + e.getMessage(), e);
        }

        Log.i(TAG, "enqueue: instance=" + (instance != null));
        if (instance != null) {
            instance.kick();
        } else {
            // 服务未就绪：拉起前台服务并重试等待
            try {
                Intent svc = new Intent(ctx.getApplicationContext(), KeepAliveService.class);
                ctx.getApplicationContext().startForegroundService(svc);
            } catch (Exception ignored) {
            }
            try {
                Intent i = new Intent(ctx, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
            // 轮询等待服务实例就绪
            for (int i = 0; i < 20; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
                if (instance != null) {
                    Log.i(TAG, "服务就绪，触发转发");
                    instance.kick();
                    return;
                }
            }
            Log.w(TAG, "等待无障碍服务超时");
        }
    }

    /** 屏幕已点亮（由 TurnOnActivity 回调）：继续微信转发流程 */
    public static void onScreenOn(Context ctx) {
        Log.i(TAG, "屏幕已点亮，继续微信流程");
        if (instance != null) {
            instance.kick();
            return;
        }
        kickStatic(ctx);
        // 服务实例未就绪：稍等重试（服务绑定后 onServiceConnected 会自行处理队列）
        new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
                if (instance != null) {
                    Log.i(TAG, "服务就绪，kick");
                    instance.kick();
                    return;
                }
            }
            Log.w(TAG, "等待服务超时");
        }).start();
    }

    /** 由 WakeupReceiver 调用：若有排队任务则触发 */
    public static void triggerIfPending(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, "[]");
        if (raw == null || raw.length() <= 2) return;
        if (instance != null) {
            instance.kick();
        } else {
            // 服务未绑定 -> 拉起应用，服务随之绑定
            try {
                Intent i = new Intent(ctx, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接 instance 已赋值");
        // 立刻处理积压任务
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            kick();
        }).start();
        // 服务刚连上，若有积压任务立即处理
        handler.post(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String raw = prefs.getString(KEY_QUEUE, "[]");
            if (raw != null && raw.length() > 2) kick();
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (!"com.tencent.mm".equals(pkg)) return;

        CharSequence cls = event.getClassName();
        if (cls != null) {
            currentActivity = cls.toString();
            Log.i(TAG, "事件: " + currentActivity + " type=" + event.getEventType());
        }
        handler.post(() -> handle());
        ensurePolling();
    }

    @Override
    public void onInterrupt() {
    }

    /** 从持久化队列取出一条 */
    private String[] peekItem() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            if (arr.length() == 0) return null;
            org.json.JSONObject o = arr.getJSONObject(0);
            return new String[]{o.optString("target", ""), o.optString("text", "")};
        } catch (Exception e) {
            return null;
        }
    }

    private void popItem() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            if (arr.length() > 0) arr.remove(0);
            prefs.edit().putString(KEY_QUEUE, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** 启动微信分享流程（静态，供任意入口调用） */
    private static void kickStatic(Context ctx) {
        if (instance != null) {
            instance.kick();
            return;
        }
        // 服务实例未就绪：直接以本应用身份调起微信分享（不依赖无障碍实例）
        try {
            String[] item = peekItemStatic(ctx);
            if (item == null) return;
            String target = item[0];
            String text = item[1];
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.setPackage("com.tencent.mm");
            i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
            i.putExtra(Intent.EXTRA_TEXT, text);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            ctx.startActivity(i);
            Log.i(TAG, "静态路径已调起微信: target=" + target);
            if (instance != null) {
                instance.pendingTarget = target;
                instance.pendingText = null;
                instance.stage = 2;
                instance.lastAction = System.currentTimeMillis();
            }
        } catch (Exception e) {
            Log.e(TAG, "静态调起微信失败: " + e.getMessage(), e);
        }
    }

    private static String[] peekItemStatic(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = prefs.getString(KEY_QUEUE, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            if (arr.length() == 0) return null;
            org.json.JSONObject o = arr.getJSONObject(0);
            return new String[]{o.optString("target", ""), o.optString("text", "")};
        } catch (Exception e) {
            return null;
        }
    }

    /** 启动微信分享流程 */
    private void kick() {
        String[] item = peekItem();
        if (item == null) {
            stage = 0;
            return;
        }
        pendingTarget = item[0];
        pendingText = item[1];
        stage = 1;
        lastAction = System.currentTimeMillis();

        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.setPackage("com.tencent.mm");
        // 显式指定微信分享 Activity，避免弹系统选择器
        i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI");
        i.putExtra(Intent.EXTRA_TEXT, pendingText);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 息屏时微信页面无法显示 -> 点亮屏幕并允许在锁屏上显示
        i.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // 解锁：FLAG_DISMISS_KEYGUARD 仅对无密码锁屏有效；
        // 有 PIN 时系统仍会拦截，已在 handle() 中做检测与提示
        try {
            startActivity(i);
            Log.i(TAG, "已调起微信分享: target=" + pendingTarget);
            // 亮屏后等界面渲染完成
            handler.postDelayed(() -> {
                stage = 2;
                lastAction = System.currentTimeMillis();
            }, 2000);
        } catch (Exception e) {
            Log.e(TAG, "调起微信失败(尝试重试): " + e.getMessage(), e);
            // 亮屏后重试一次
            handler.postDelayed(() -> {
                try {
                    startActivity(i);
                    Log.i(TAG, "重试调起微信成功");
                    handler.postDelayed(() -> {
                        stage = 2;
                        lastAction = System.currentTimeMillis();
                    }, 2000);
                } catch (Exception e2) {
                    Log.e(TAG, "重试仍失败: " + e2.getMessage(), e2);
                }
            }, 1200);
        }
    }

    /** 点亮屏幕（息屏时微信界面需可见才能执行手势） */
    private void wakeUpScreen() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            android.os.PowerManager.WakeLock wl =
                    pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "smsfwd:awake");
            wl.setReferenceCounted(false);
            wl.acquire(60000);
            // 同时发送唤醒广播，确保从 Dozing 变为 Awake
            try {
                sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
            } catch (Exception ignored) {
            }
            // 保持窗口常亮
            handler.postDelayed(() -> {
                try { if (wl.isHeld()) wl.release(); } catch (Exception ignored) { }
            }, 55000);
            Log.i(TAG, "已点亮屏幕");
        } catch (Exception e) {
            Log.e(TAG, "点亮屏幕失败: " + e.getMessage(), e);
        }
    }

    /**
     * 主状态机 —— 微信界面自绘，无障碍节点树为空，
     * 采用「Activity 名判定 + 无障碍手势坐标点击」。
     *
     * 实测流程（1080x2220）：
     *   ShareImgUI（分享页，含文本框） -> 点「发送给朋友」-> SelectConversationUI（选择一个聊天）
     *   -> 点最近聊天首项 -> 半屏确认面板 -> 点「发送」
     */
    private void handle() {
        String[] item = peekItem();
        if (item == null) {
            stage = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAction > 60000) {
            Log.w(TAG, "整体超时，放弃当前任务");
            popItem();
            stage = 0;
            pendingText = null;
            return;
        }

        int sw = getResources().getDisplayMetrics().widthPixels;   // 1080
        int sh = getResources().getDisplayMetrics().heightPixels;  // 2220
        String act = currentActivity;

        // 阶段 1：分享页 ShareImgUI —— 文本已由 Intent 带入，点「发送给朋友」
        if (stage == 1) {
            if (act.contains("ShareImgUI")) {
                // 「发送给朋友」按钮在分享页下方，实测约 y=0.62*sh, x=0.5*sw
                // 但微信 8.x 分享页结构会变，优先用文本查找
                if (clickText("发送给朋友") || clickText("发送给微信好友")) {
                    stage = 2;
                    lastAction = now;
                    return;
                }
                // 退化：直接进入选联系人页
                Intent i = new Intent();
                i.setClassName("com.tencent.mm", "com.tencent.mm.ui.transmit.SelectConversationUI");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                    stage = 2;
                    lastAction = now;
                } catch (Exception e) {
                    Log.e(TAG, "跳转选联系人失败: " + e.getMessage());
                }
                return;
            }
            if (act.contains("SelectConversationUI")) {
                stage = 2;
                lastAction = now;
            }
            return;
        }

        // 阶段 2：选择一个聊天 —— 点击最近聊天首项
        if (stage == 2) {
            if (act.contains("SelectConversationUI")) {
                // 1) 优先按昵称文本匹配（微信若暴露了节点）
                if (clickText(pendingTarget)) {
                    Log.i(TAG, "已按昵称匹配到联系人: " + pendingTarget);
                    stage = 3;
                    handler.postDelayed(() -> {
                        stage = 4;
                        lastAction = System.currentTimeMillis();
                    }, 1500);
                    return;
                }
                // 2) 退化：按「最近聊天」列表首项的固定位置点击
                //    坐标按 1080x2220 屏幕比例换算，具体位置取决于微信版本与列表内容
                int tx = (int) (sw * 0.096);
                int ty = (int) (sh * 0.309);
                if (gestureClick(tx, ty)) {
                    stage = 3;
                    handler.postDelayed(() -> {
                        stage = 4;
                        lastAction = System.currentTimeMillis();
                    }, 1500);
                    Log.i(TAG, "已点击联系人坐标 " + tx + "," + ty);
                }
                return;
            }
            // 已进入半屏确认面板
            stage = 3;
            lastAction = now;
            return;
        }

        // 阶段 3：等待确认面板（由定时器推进到 4）
        if (stage == 3) {
            return;
        }

        // 阶段 4：半屏确认面板（发送给: <昵称>）-> 点「发送」
        if (stage == 4) {
            if (act.contains("ShareImgUI") || act.contains("SelectConversationUI")) {
                // 尚未进入面板，稍等
                return;
            }
            // 发送按钮：显示坐标 (347,898) -> 设备 (722,1870)
            int bx = (int) (sw * 0.668);
            int by = (int) (sh * 0.842);
            if (gestureClick(bx, by)) {
                Log.i(TAG, "已点击发送按钮");
                stage = 5;
                lastAction = now;
            }
            return;
        }

        // 阶段 5：等待发送完成
        if (stage == 5) {
            if (now - lastAction > 6000) {
                Log.i(TAG, "转发流程完成: target=" + pendingTarget);
                popItem();
                stage = 0;
                pendingText = null;
            }
            return;
        }
    }

    /** 按文本点击节点 */
    private boolean clickText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isVisibleToUser()) {
                try {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /** 按 resource-id 设置文本 */
    private boolean setTextById(String id, String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo n : nodes) {
            if (n.isVisibleToUser() && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle(text))) {
                return true;
            }
        }
        return false;
    }

    /** 在聊天页找到输入框并输入 */
    private boolean setChatInput(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String[] ids = {"com.tencent.mm:id/hce", "com.tencent.mm:id/et_", "com.tencent.mm:id/chat_edit_text"};
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser() && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle(text))) {
                    return true;
                }
            }
        }
        // 退化：递归找 EditText
        AccessibilityNodeInfo ed = findEditText(root);
        if (ed != null) {
            return ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle(text));
        }
        return false;
    }

    /** 点击搜索框并输入关键字 */
    private boolean clickSearchAndType(String keyword) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String[] ids = {"com.tencent.mm:id/search_src_text", "com.tencent.mm:id/search_text"};
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser() && n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle(keyword))) {
                    return true;
                }
            }
        }
        return false;
    }

    private android.os.Bundle bundle(String text) {
        android.os.Bundle b = new android.os.Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return b;
    }

    /** 递归查找 EditText */
    private AccessibilityNodeInfo findEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".equals(node.getClassName())
                && node.isVisibleToUser() && node.isEnabled()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findEditText(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    /** 无障碍手势点击（不依赖节点可见性） */
    private boolean gestureClick(int x, int y) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            android.accessibilityservice.GestureDescription gesture =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                                    path, 0, 60))
                            .build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            Log.e(TAG, "手势点击失败: " + e.getMessage(), e);
            return false;
        }
    }

    /** 无障碍手势滑动 */
    private boolean gestureSwipe(int x1, int y1, int x2, int y2, long dur) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            android.accessibilityservice.GestureDescription gesture =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                                    path, 0, dur))
                            .build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** 提交文本到当前焦点输入框 */
    private boolean commitText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            try {
                if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle(text))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private PollTask pollTask;

    /** 启动轮询兜底：微信自绘页面事件稀疏，用定时器推进状态机 */
    private void ensurePolling() {
        if (pollTask != null) return;
        pollTask = new PollTask(this);
        handler.postDelayed(pollTask, 800);
    }

    /** 轮询任务（static 嵌套类，避免 d8 处理内部类实例引用出错） */
    private static class PollTask implements Runnable {
        private final WxAutoService svc;

        PollTask(WxAutoService svc) {
            this.svc = svc;
        }

        @Override
        public void run() {
            if (svc.peekItem() != null) {
                try {
                    svc.refreshCurrentActivity();
                    svc.handle();
                } catch (Exception e) {
                    Log.e(TAG, "轮询异常: " + e.getMessage(), e);
                }
            }
            if (svc.peekItem() != null) {
                svc.handler.postDelayed(this, 800);
            } else {
                svc.pollTask = null;
            }
        }
    }

    /** 读取当前前台 Activity（无障碍事件稀疏时的兜底） */
    private void refreshCurrentActivity() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return;
            AccessibilityWindowInfo w = windows.get(0);
            if (w == null) return;
            CharSequence t = w.getTitle();
            String act = t != null ? t.toString() : "";
            if (act.contains("微信") || act.contains("WeChat")) {
                act = "com.tencent.mm.ui.transmit.SelectConversationUI";
            }
            if (!act.isEmpty() && !act.equals(currentActivity)) {
                currentActivity = act;
                Log.i(TAG, "轮询更新 Activity: " + act + " (title=" + t + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "刷新 Activity 失败: " + e.getMessage());
        }
    }

    /**
     * 读取当前屏幕（微信）可见文字，用于验证消息是否真的发出。
     * 通过广播触发，日志输出。
     */
    public static void dumpScreenText() {
        if (instance == null) {
            Log.i(TAG, "dumpScreenText: 服务未就绪");
            return;
        }
        instance.dumpTextInternal();
    }

    private void dumpTextInternal() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                Log.i(TAG, "dump: root 为空");
                return;
            }
            StringBuilder sb = new StringBuilder();
            collectText(root, sb, 0);
            Log.i(TAG, "DUMP_START" + sb.toString() + "DUMP_END");
        } catch (Exception e) {
            Log.e(TAG, "dump 失败: " + e.getMessage(), e);
        }
    }

    /** 递归收集可见文字 */
    private void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            sb.append("\n[").append(depth).append("] ").append(t);
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectText(node.getChild(i), sb, depth + 1);
        }
    }

}
