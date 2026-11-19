package com.smsfwd.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * 透明唤醒 Activity：息屏(Dozing)状态下，后台应用无法启动 Activity，
 * 系统也不允许wakelock 唤醒屏幕。本 Activity 用 showWhenLocked + turnScreenOn
 * 由系统点亮屏幕，随后立即启动微信分享页并自我关闭。
 */
public class TurnOnActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 透明无主题
        getWindow().setBackgroundDrawable(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 屏幕已亮 -> 通知无障碍服务继续微信流程
        try {
            WxAutoService.onScreenOn(getApplicationContext());
        } catch (Exception e) {
            android.util.Log.e("TurnOn", "唤醒后续失败: " + e.getMessage(), e);
        }
        finish();
    }
}
