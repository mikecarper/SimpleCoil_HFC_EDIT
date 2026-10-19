package com.simplecoil.simplecoil;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Managed event phones return to the pinned game after a reboot. */
public final class EventBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(EventKioskController.PREFS, Context.MODE_PRIVATE);
        DevicePolicyManager policy = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (policy != null && policy.isDeviceOwnerApp(context.getPackageName())
                && prefs.getBoolean("enabled", false) && prefs.getBoolean("pinning", false)
                && !(prefs.getString("revision", "") + ":" + prefs.getString("identity", "")).equals(prefs.getString("bypass", "")))
            context.startActivity(new Intent(context, FullscreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
