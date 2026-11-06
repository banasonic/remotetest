package com.example.bt115helper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQUEST_BT_PERMISSIONS = 101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private CheckBox bootCheck;
    private boolean startAfterPermission;
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            SharedPreferences prefs = getSharedPreferences(BluetoothWorkerService.PREFS, MODE_PRIVATE);
            String status = prefs.getString(BluetoothWorkerService.KEY_STATUS, "جاهز. اضغط «ابدأ الآن» للبدء.");
            statusView.setText(status);
            handler.postDelayed(this, 1800);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(createContent());
        SharedPreferences prefs = getSharedPreferences(BluetoothWorkerService.PREFS, MODE_PRIVATE);
        bootCheck.setChecked(prefs.getBoolean(BluetoothWorkerService.KEY_BOOT, false));
        bootCheck.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(BluetoothWorkerService.KEY_BOOT, checked).apply());
        handler.post(refreshStatus);
    }

    private View createContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(8, 15, 28));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(42), dp(28), dp(42), dp(28));
        scroll.addView(page);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_bluetooth);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        iconParams.bottomMargin = dp(6);
        page.addView(icon, iconParams);

        TextView title = new TextView(this);
        title.setText("مساعد BT115");
        title.setTextColor(Color.WHITE);
        title.setTextSize(29);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        page.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("أداة بسيطة لمراقبة ريموت Bluetooth على Android TV");
        subtitle.setTextColor(Color.rgb(176, 194, 219));
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = matchWrap();
        subParams.topMargin = dp(8);
        page.addView(subtitle, subParams);

        Button start = new Button(this);
        start.setText("ابدأ الآن");
        start.setTextSize(19);
        start.setAllCaps(false);
        start.setTextColor(Color.WHITE);
        start.setBackground(background(Color.rgb(24, 111, 224), dp(12)));
        start.setOnClickListener(v -> showSafetyConfirmation());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(dp(300), dp(62));
        startParams.topMargin = dp(27);
        page.addView(start, startParams);

        bootCheck = new CheckBox(this);
        bootCheck.setText("البدء مع تشغيل النظام");
        bootCheck.setTextColor(Color.WHITE);
        bootCheck.setTextSize(17);
        bootCheck.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(87, 169, 255)));
        bootCheck.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(340), dp(58));
        checkParams.topMargin = dp(12);
        page.addView(bootCheck, checkParams);

        TextView statusLabel = new TextView(this);
        statusLabel.setText("الحالة");
        statusLabel.setTextColor(Color.rgb(111, 181, 255));
        statusLabel.setTextSize(15);
        statusLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(18);
        page.addView(statusLabel, labelParams);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(226, 235, 247));
        statusView.setTextSize(15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(18), dp(12), dp(18), dp(12));
        statusView.setBackground(background(Color.rgb(17, 29, 48), dp(10)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(680), LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(7);
        page.addView(statusView, statusParams);

        TextView notice = new TextView(this);
        notice.setText("تنبيه: تنفيذ أوامر النظام يتطلب root. مسح بيانات Bluetooth قد يحذف الاقترانات. التطبيق لا يستطيع منع النظام من إظهار انقطاع الاتصال أو تغيير مهلة GATT/LMP.");
        notice.setTextColor(Color.rgb(242, 197, 105));
        notice.setTextSize(13);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(dp(720), LinearLayout.LayoutParams.WRAP_CONTENT);
        noticeParams.topMargin = dp(7);
        page.addView(notice, noticeParams);
        return scroll;
    }

    private void showSafetyConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("تأكيد تشغيل أوامر النظام")
                .setMessage("سيحاول التطبيق تنفيذ: تعطيل Bluetooth ثم تفعيله ثم مسح بيانات com.android.bluetooth. قد يؤدي المسح إلى إزالة الاقترانات أو إعادة ضبط خدمة Bluetooth. يتطلب الجهاز صلاحية root. هل تريد المتابعة؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", (dialog, which) -> ensureBluetoothPermissions())
                .show();
    }

    private void ensureBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            boolean scanGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
            boolean connectGranted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
            if (!scanGranted || !connectGranted) {
                startAfterPermission = true;
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT_PERMISSIONS);
                return;
            }
        }
        startWorker();
    }

    private void startWorker() {
        Intent intent = new Intent(this, BluetoothWorkerService.class);
        intent.setAction(BluetoothWorkerService.ACTION_RUN_NOW);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_PERMISSIONS && startAfterPermission) {
            startAfterPermission = false;
            boolean granted = grantResults.length >= 2
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED;
            if (!granted) getSharedPreferences(BluetoothWorkerService.PREFS, MODE_PRIVATE).edit()
                    .putString(BluetoothWorkerService.KEY_STATUS, "لم تُمنح أذونات Bluetooth؛ ستعمل محاولة أوامر النظام فقط دون بحث عن الريموت.").apply();
            startWorker();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshStatus);
        handler.post(refreshStatus);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
