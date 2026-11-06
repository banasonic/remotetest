package com.example.bt115helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences(BluetoothWorkerService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BluetoothWorkerService.KEY_BOOT, false);
        if (!enabled) return;
        Intent service = new Intent(context, BluetoothWorkerService.class);
        service.setAction(BluetoothWorkerService.ACTION_BOOT);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (RuntimeException ignored) {
            // Android/OEM background-start restrictions may prevent boot startup.
        }
    }
}
