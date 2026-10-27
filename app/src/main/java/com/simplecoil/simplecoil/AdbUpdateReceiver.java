package com.simplecoil.simplecoil;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** Explicit, DUMP-protected shell handshake for the authorized laptop's ADB updater. */
public final class AdbUpdateReceiver extends BroadcastReceiver {
    static final String PREFIX = "com.simplecoil.simplecoil.ADB_UPDATE_";
    private static final AdbUpdateGate GATE = new AdbUpdateGate(SystemClock::elapsedRealtime);
    private static WeakReference<FullscreenActivity> activity = new WeakReference<>(null);

    static void bind(FullscreenActivity current) { activity = new WeakReference<>(current); }

    static void unbind(FullscreenActivity current) {
        if (activity.get() == current) activity.clear();
    }

    static boolean held() { return GATE.held(); }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !isOrderedBroadcast()) return;
        String action = intent.getAction();
        if (!(PREFIX + "PREPARE").equals(action) && !(PREFIX + "STATUS").equals(action)
                && !(PREFIX + "RELEASE").equals(action)) return;
        String nonce = intent.getStringExtra("nonce"), host = intent.getStringExtra("host");
        try {
            FullscreenActivity current = activity.get();
            boolean valid = AdbUpdateGate.valid(nonce, host);
            boolean eligible = false;
            String status;
            if ((PREFIX + "RELEASE").equals(action)) {
                status = GATE.release(nonce, host);
            } else {
                eligible = valid && current != null && current.canPrepareAdbUpdate(host)
                        && context.getPackageManager().getPackageInstaller().getMySessions().isEmpty();
                status = (PREFIX + "PREPARE").equals(action)
                        ? GATE.prepare(nonce, host, eligible) : GATE.status(nonce, host, eligible);
            }
            PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long version = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
            JSONObject result = new JSONObject().put("protocol", 1).put("status", status)
                    .put("nonce", valid ? nonce : "").put("host", valid ? host : "")
                    .put("expiresInMs", "ready".equals(status) ? GATE.remaining() : 0)
                    .put("eligible", eligible).put("managed", current != null && current.hasManagedAdbUpdates())
                    .put("package", context.getPackageName()).put("versionCode", version);
            setResult("ready".equals(status) || "released".equals(status)
                    ? Activity.RESULT_OK : Activity.RESULT_CANCELED, "SCADB1 " + result, null);
        } catch (Exception unavailable) {
            setResult(Activity.RESULT_CANCELED, "SCADB1 {\"protocol\":1,\"status\":\"unavailable\"}", null);
        }
    }
}
