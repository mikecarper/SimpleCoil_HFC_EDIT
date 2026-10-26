package com.simplecoil.simplecoil;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives only our explicit PackageInstaller status PendingIntent; never opens external UI. */
public final class AppUpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        AppUpdateInstaller.onInstallStatus(context, intent);
    }
}
