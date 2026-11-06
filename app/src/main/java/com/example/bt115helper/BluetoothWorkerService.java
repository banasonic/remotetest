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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BluetoothWorkerService extends Service {
    public static final String PREFS = "bt115_prefs";
    public static final String KEY_BOOT = "start_on_boot";
    public static final String KEY_STATUS = "status";
    public static final String ACTION_RUN_NOW = "com.example.bt115helper.RUN_NOW";
    public static final String ACTION_BOOT = "com.example.bt115helper.BOOT";
    private static final String TAG = "BT115Helper";
    private static final String CHANNEL_ID = "bt115_monitor";
    private static final int NOTIFICATION_ID = 115;
    private static final long BOND_RETRY_DELAY_MS = 60_000L;
    private static final long SCAN_RETRY_DELAY_MS = 1_000L;
    private static final long SCAN_FALLBACK_DELAY_MS = 25_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> bondRequestTimes = new HashMap<>();
    private final Set<String> connectedTargetAddresses = new HashSet<>();
    private final Set<String> bondingTargetAddresses = new HashSet<>();
    private BroadcastReceiver bluetoothReceiver;
    private BluetoothAdapter adapter;
    private boolean monitoring;

    private final Runnable scanRunnable = this::startDiscovery;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        registerBluetoothReceiver();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("بدء مراقبة أجهزة BT115"));
        worker.execute(this::beginMonitoring);
        return START_STICKY;
    }

    private void beginMonitoring() {
        if (!hasBluetoothPermissions()) {
            updateStatus("يلزم منح إذني Bluetooth Scan وConnect مرة واحدة من التطبيق قبل المراقبة.");
            return;
        }
        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            updateStatus("هذا الجهاز لا يوفر Bluetooth عبر Android.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                updateStatus("Bluetooth متوقف. شغّله من إعدادات Android ثم أعد بدء المراقبة.");
                return;
            }
            monitoring = true;
            inspectBondedDevices();
            updateStatus("المراقبة تعمل؛ البحث عن أي اسم جهاز يحتوي BT115.");
            handler.removeCallbacks(scanRunnable);
            handler.post(scanRunnable);
        } catch (SecurityException e) {
            updateStatus("رفض Android الوصول إلى Bluetooth: " + e.getMessage());
        }
    }

    private void inspectBondedDevices() {
        if (adapter == null || !hasBluetoothPermissions()) return;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String name = safeName(device);
                if (isTarget(name)) {
                    int state = device.getBondState();
                    updateStatus(state == BluetoothDevice.BOND_BONDED
                            ? "الريموت " + name + " محفوظ كجهاز مقترن؛ سيتولى Android إعادة الاتصال عندما يصبح متاحًا."
                            : "عُثر على سجل ريموت " + name + ".");
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot read bonded devices", e);
        }
    }

    private void registerBluetoothReceiver() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    scheduleNextScan(SCAN_RETRY_DELAY_MS);
                    return;
                }
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON && monitoring) {
                        updateStatus("عاد Bluetooth للعمل؛ استئناف مراقبة BT115.");
                        scheduleNextScan(0L);
                    } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        handler.removeCallbacks(scanRunnable);
                        updateStatus("Bluetooth متوقف؛ ستُستأنف المراقبة عند تشغيله.");
                    }
                    return;
                }
                BluetoothDevice device;
                try { device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); }
                catch (Exception e) { return; }
                if (device == null) return;
                String name = safeName(device);
                if (!isTarget(name)) return;

                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    onTargetFound(device, name);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                    int state;
                    try { state = device.getBondState(); }
                    catch (SecurityException e) { return; }
                    if (state == BluetoothDevice.BOND_BONDED) {
                        bondingTargetAddresses.remove(device.getAddress());
                        bondRequestTimes.remove(device.getAddress());
                        updateStatus("اكتمل اقتران " + name + ". سيعيد Android الاتصال به تلقائيًا إذا كان الريموت متاحًا.");
                        if (connectedTargetAddresses.isEmpty() && bondingTargetAddresses.isEmpty()) scheduleNextScan(1_000L);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        bondingTargetAddresses.remove(device.getAddress());
                        updateStatus("لم يكتمل الاقتران مع " + name + "; قد يتطلب تأكيدًا أو PIN من Android.");
                        if (connectedTargetAddresses.isEmpty() && bondingTargetAddresses.isEmpty()) scheduleNextScan(1_000L);
                    } else {
                        bondingTargetAddresses.add(device.getAddress());
                        handler.removeCallbacks(scanRunnable);
                        updateStatus("جارٍ الاقتران مع " + name + ".");
                    }
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    connectedTargetAddresses.add(device.getAddress());
                    handler.removeCallbacks(scanRunnable);
                    try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); }
                    catch (SecurityException ignored) { }
                    updateStatus("اتصال فعلي حسب Android: " + name + ". أُوقف البحث لتجنب إزعاج الاتصال؛ Android يدير إبقاءه وإعادة الاتصال.");
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    connectedTargetAddresses.remove(device.getAddress());
                    updateStatus("أبلغ Android عن انقطاع " + name + "; يُمنح Android لحظة لمحاولة إعادة اتصال الريموت المقترن، ثم يستأنف البحث.");
                    if (connectedTargetAddresses.isEmpty()) scheduleNextScan(SCAN_RETRY_DELAY_MS);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(bluetoothReceiver, filter);
    }

    private void onTargetFound(BluetoothDevice device, String name) {
        try {
            int bondState = device.getBondState();
            if (bondState == BluetoothDevice.BOND_BONDED) {
                updateStatus("عُثر على " + name + " وهو مقترن؛ Android يدير إعادة الاتصال.");
                return;
            }
            updateStatus("عُثر على " + name + (bondState == BluetoothDevice.BOND_BONDING ? "؛ جارٍ الاقتران." : "؛ طلب الاقتران."));
            if (bondState == BluetoothDevice.BOND_BONDING) {
                bondingTargetAddresses.add(device.getAddress());
                handler.removeCallbacks(scanRunnable);
            } else if (bondState == BluetoothDevice.BOND_NONE) {
                requestBondIfDue(device, name);
            }
        } catch (SecurityException e) {
            updateStatus("تعذر قراءة الجهاز؛ تحقق من إذن Bluetooth Connect.");
        }
    }

    private void requestBondIfDue(BluetoothDevice device, String name) {
        long now = System.currentTimeMillis();
        Long previous = bondRequestTimes.get(device.getAddress());
        if (previous != null && now - previous < BOND_RETRY_DELAY_MS) return;
        bondRequestTimes.put(device.getAddress(), now);
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
            boolean requested = device.createBond();
            if (requested) {
                bondingTargetAddresses.add(device.getAddress());
                handler.removeCallbacks(scanRunnable);
            }
            updateStatus(requested
                    ? "أُرسل طلب اقتران إلى " + name + ". قد يتطلب Android موافقة أو PIN."
                    : "رفض Android طلب الاقتران مع " + name + "; ستتم إعادة المحاولة لاحقًا.");
        } catch (SecurityException e) {
            updateStatus("لا يملك التطبيق صلاحية طلب الاقتران مع " + name + ".");
        }
    }

    private void startDiscovery() {
        if (!monitoring || adapter == null) return;
        if (!connectedTargetAddresses.isEmpty() || !bondingTargetAddresses.isEmpty()) return;
        if (!hasBluetoothPermissions()) {
            updateStatus("انتهى إذن Bluetooth؛ افتح التطبيق وامنح الأذونات مجددًا.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                updateStatus("Bluetooth متوقف؛ البحث متوقف حتى تشغيله من إعدادات النظام.");
                scheduleNextScan(10_000L);
                return;
            }
            boolean started = adapter.startDiscovery();
            if (started) {
                updateStatus("جارٍ البحث عن أي ريموت يتضمن اسمه BT115.");
                handler.removeCallbacks(scanRunnable);
                handler.postDelayed(scanRunnable, SCAN_FALLBACK_DELAY_MS);
            } else {
                scheduleNextScan(SCAN_RETRY_DELAY_MS);
            }
        } catch (SecurityException e) {
            updateStatus("منع Android بدء بحث Bluetooth: " + e.getMessage());
        }
    }

    private void scheduleNextScan(long delayMs) {
        if (!monitoring) return;
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, delayMs);
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
        return name != null && name.toUpperCase(Locale.ROOT).contains("BT115");
    }

    private void updateStatus(String status) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(status));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "مراقبة ريموت BT115", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("البحث عن أجهزة Bluetooth التي يحتوي اسمها BT115");
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
        monitoring = false;
        handler.removeCallbacksAndMessages(null);
        if (adapter != null) {
            try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); }
            catch (SecurityException ignored) { }
        }
        if (bluetoothReceiver != null) {
            try { unregisterReceiver(bluetoothReceiver); } catch (Exception ignored) { }
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
