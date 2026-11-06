package com.example.bt115helper;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class BluetoothWorkerService extends Service {
    public static final String PREFS = "bt115_prefs";
    public static final String KEY_BOOT = "start_on_boot";
    public static final String KEY_STATUS = "status";
    public static final String ACTION_RUN_NOW = "com.example.bt115helper.RUN_NOW";
    public static final String ACTION_BOOT = "com.example.bt115helper.BOOT";
    private static final String TAG = "BT115Helper";
    private static final String CHANNEL_ID = "bt115_monitor";
    private static final int NOTIFICATION_ID = 115;
    private static final String[] COMMANDS = {
            "cmd bluetooth_manager disable",
            "cmd bluetooth_manager enable",
            "pm clear com.android.bluetooth"
    };
    private static final Set<String> TARGET_NAMES = new HashSet<>(Arrays.asList(
            "BT115-001", "BT115-002", "BT115-003"));

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final HandlerLoop scanLoop = new HandlerLoop();
    private final Set<String> bondRequested = new HashSet<>();
    private boolean busy;
    private BroadcastReceiver bluetoothReceiver;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerBluetoothReceiver();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("يتم تشغيل خدمة المراقبة"));
        if (!busy) {
            busy = true;
            String action = intent == null ? null : intent.getAction();
            boolean runCommands = ACTION_RUN_NOW.equals(action) || ACTION_BOOT.equals(action);
            worker.execute(() -> {
                try {
                    if (runCommands) {
                        runRequestedSequence();
                        updateStatus("تم تنفيذ المحاولات. جارٍ البحث عن ريموت BT115.");
                    }
                    beginMonitoring();
                } finally {
                    busy = false;
                }
            });
        }
        return START_STICKY;
    }

    private void runRequestedSequence() {
        for (int i = 0; i < COMMANDS.length; i++) {
            String command = COMMANDS[i];
            updateStatus("تنفيذ " + (i + 1) + "/3: " + command);
            CommandResult result = runPrivilegedOrSystem(command);
            Log.i(TAG, command + " => " + result.describe());
            if (result.success) {
                updateStatus("نجح الأمر " + (i + 1) + "/3: " + command);
            } else {
                updateStatus("تعذر الأمر " + (i + 1) + "/3. يلزم root أو دمج التطبيق وتوقيعه كتطبيق نظام. " + shortText(result.describe()));
            }
            try { Thread.sleep(i == 1 ? 3500 : 700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private CommandResult runPrivilegedOrSystem(String command) {
        CommandResult rootResult = execute(new String[]{"su", "-c", command});
        if (rootResult.success) return rootResult;
        CommandResult systemResult = execute(new String[]{"/system/bin/sh", "-c", command});
        if (systemResult.success) return new CommandResult(true, "نفذ ضمن سياق النظام: " + systemResult.output);
        return new CommandResult(false, "su: " + shortText(rootResult.describe()) + " | system: " + shortText(systemResult.describe()));
    }

    private CommandResult execute(String[] args) {
        Process process = null;
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            final InputStream stream = process.getInputStream();
            Future<String> outputFuture = readerExecutor.submit((Callable<String>) () -> {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            });
            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "انتهت مهلة التنفيذ (12 ثانية)");
            }
            String output = outputFuture.get(2, TimeUnit.SECONDS).trim();
            return new CommandResult(process.exitValue() == 0, output.isEmpty() ? "exit=" + process.exitValue() : output + " (exit=" + process.exitValue() + ")");
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new CommandResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private void beginMonitoring() {
        if (!hasBluetoothPermissions()) {
            updateStatus("أوامر النظام انتهت؛ البحث يحتاج إذن Bluetooth من واجهة التطبيق أولًا.");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            updateStatus("هذا الجهاز لا يوفر Bluetooth عبر Android.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                updateStatus("Bluetooth غير مفعّل. تحقق من نتيجة cmd bluetooth_manager enable.");
                return;
            }
            checkBondedDevices(adapter);
            scanLoop.runScanCycle(adapter);
        } catch (SecurityException e) {
            updateStatus("رفض Android الوصول إلى Bluetooth: " + e.getMessage());
        }
    }

    private void checkBondedDevices(BluetoothAdapter adapter) {
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = safeName(device);
                if (isTarget(name)) updateStatus("الريموت " + name + " مقترن. حالة الاتصال الفعلية يحددها نظام Bluetooth.");
            }
        } catch (SecurityException ignored) { }
    }

    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                BluetoothDevice device;
                try { device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); }
                catch (Exception e) { return; }
                if (device == null) return;
                String name = safeName(device);
                if (!isTarget(name)) return;

                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    int bondState = device.getBondState();
                    updateStatus("عُثر على " + name + (bondState == BluetoothDevice.BOND_BONDED ? " (مقترن)" : " (غير مقترن)"));
                    if (bondState == BluetoothDevice.BOND_NONE && bondRequested.add(device.getAddress())) {
                        try {
                            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                            if (adapter != null) adapter.cancelDiscovery();
                            boolean requested = device.createBond();
                            updateStatus(requested ? "طُلب الاقتران مع " + name + ". قد يعرض Android تأكيدًا على الشاشة." : "تعذر طلب الاقتران مع " + name);
                        } catch (SecurityException e) {
                            updateStatus("تعذر طلب الاقتران؛ تحقق من إذن Bluetooth.");
                        }
                    }
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    updateStatus("تغيرت حالة اقتران " + name + ".");
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    updateStatus("أبلغ Android عن اتصال " + name + ".");
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    updateStatus("أبلغ Android عن انقطاع " + name + "؛ سيواصل التطبيق البحث دون تزوير حالة الاتصال.");
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(bluetoothReceiver, filter);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private String safeName(BluetoothDevice device) {
        try { return device.getName(); } catch (SecurityException e) { return null; }
    }

    private boolean isTarget(String name) {
        return name != null && TARGET_NAMES.contains(name.trim().toUpperCase(Locale.ROOT));
    }

    private void updateStatus(String status) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "مراقبة ريموت BT115", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("حالة البحث عن ريموت Bluetooth المحدد");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_bluetooth_small)
                .setContentTitle("BT115 TV Helper")
                .setContentText(shortText(text))
                .setOngoing(true)
                .build();
    }

    private String shortText(String value) {
        if (value == null) return "";
        return value.length() > 115 ? value.substring(0, 112) + "…" : value;
    }

    @Override public void onDestroy() {
        scanLoop.stop();
        if (bluetoothReceiver != null) {
            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) { }
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class CommandResult {
        final boolean success;
        final String output;
        CommandResult(boolean success, String output) { this.success = success; this.output = output == null ? "" : output; }
        String describe() { return output; }
    }

    private final class HandlerLoop {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private Runnable scanRunnable;
        void runScanCycle(BluetoothAdapter adapter) {
            scanRunnable = new Runnable() {
                @Override public void run() {
                    try {
                        if (adapter.isEnabled()) {
                            boolean started = adapter.startDiscovery();
                            if (started) updateStatus("جارٍ البحث عن BT115-001 / BT115-002 / BT115-003.");
                            else updateStatus("تعذر بدء البحث؛ سيعيد التطبيق المحاولة.");
                        }
                    } catch (SecurityException e) {
                        updateStatus("منع Android البحث عن أجهزة Bluetooth.");
                    }
                    handler.postDelayed(this, 30000);
                }
            };
            handler.removeCallbacks(scanRunnable);
            handler.post(scanRunnable);
        }
        void stop() { if (scanRunnable != null) handler.removeCallbacks(scanRunnable); }
    }
}
