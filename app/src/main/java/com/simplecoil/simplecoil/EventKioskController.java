package com.simplecoil.simplecoil;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.simplecoil.protocol.EventMode;

/** Uses Android's supported pinning/kiosk APIs, with a local organizer recovery path. */
final class EventKioskController {
    static final String PREFS = "EventKiosk";
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Runnable changed;
    private String revision = "", hash = "", identity = "", bypass = "";
    private boolean enabled, pinning, prompted, error;
    private AlertDialog unlockDialog;

    EventKioskController(Activity activity, Runnable changed) {
        this.activity = activity;this.changed = changed;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Globals globals = Globals.getInstance();
        // A Wi-Fi loss or process restart must not unlock an event phone.
        globals.mEventMode = prefs.getBoolean("enabled", false);
        globals.mEventPinning = prefs.getBoolean("pinning", false);
        globals.mEventRevision = prefs.getString("revision", "");
        globals.mEventPinHash = prefs.getString("hash", "");
        globals.mHostPlayerIdentity = prefs.getString("identity", null);
        bypass = prefs.getString("bypass", "");
    }

    private String policyKey() { return revision + ":" + identity; }

    boolean restricted() { return enabled && !policyKey().equals(bypass); }

    void sync() {
        Globals globals = Globals.getInstance();
        String nextIdentity = globals.mHostPlayerIdentity == null ? "" : globals.mHostPlayerIdentity;
        if (enabled != globals.mEventMode || pinning != globals.mEventPinning
                || !revision.equals(globals.mEventRevision) || !hash.equals(globals.mEventPinHash)
                || !identity.equals(nextIdentity)) {
            if (!revision.equals(globals.mEventRevision) || !identity.equals(nextIdentity)) { prompted = false;error = false; }
            enabled = globals.mEventMode;pinning = globals.mEventPinning;
            revision = globals.mEventRevision;hash = globals.mEventPinHash;identity = nextIdentity;
            prefs.edit().putBoolean("enabled", enabled).putBoolean("pinning", pinning)
                    .putString("revision", revision).putString("hash", hash).putString("identity", identity).apply();
        }
        if (!restricted() || !pinning) {
            if (!"none".equals(lockStatus())) stop();
            return;
        }
        if (!activity.hasWindowFocus() || activity.isFinishing()) return;
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return;
        if (!"none".equals(lockStatus()) || prompted) return;
        prompted = true;
        try {
            DevicePolicyManager policy = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (policy != null && policy.isDeviceOwnerApp(activity.getPackageName())) {
                policy.setLockTaskPackages(EventDeviceAdminReceiver.component(activity), new String[]{activity.getPackageName()});
                if (Build.VERSION.SDK_INT >= 28) policy.setLockTaskFeatures(EventDeviceAdminReceiver.component(activity),
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
            }
            // Without device-owner allowlisting Android asks the user to confirm screen pinning.
            activity.startLockTask();
        } catch (RuntimeException failure) {
            error = true;
            Toast.makeText(activity, R.string.event_pin_failed, Toast.LENGTH_LONG).show();
        }
    }

    String lockStatus() {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return error ? "error" : "none";
        if (Build.VERSION.SDK_INT >= 23) {
            int state = manager.getLockTaskModeState();
            if (state == ActivityManager.LOCK_TASK_MODE_LOCKED) return "locked";
            if (state == ActivityManager.LOCK_TASK_MODE_PINNED) return "pinned";
        } else if (manager.isInLockTaskMode()) {
            DevicePolicyManager policy = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return policy != null && policy.isLockTaskPermitted(activity.getPackageName()) ? "locked" : "pinned";
        }
        return error ? "error" : "none";
    }

    void showOrganizerUnlock() {
        if (unlockDialog != null && unlockDialog.isShowing()) return;
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.event_organizer_pin);
        unlockDialog = new AlertDialog.Builder(activity).setTitle(R.string.event_organizer_unlock)
                .setMessage(R.string.event_unlock_help).setView(input).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.event_unlock, null).create();
        unlockDialog.setOnShowListener(ignored -> unlockDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            long now = System.currentTimeMillis(), waitUntil = prefs.getLong("retryAfter", 0);
            if (waitUntil > now) { input.setError(activity.getString(R.string.event_pin_wait));return; }
            if (!EventMode.matchesPin(identity, input.getText().toString(), hash)) {
                int failures = prefs.getInt("failures", 0) + 1;
                prefs.edit().putInt("failures", failures % 3).putLong("retryAfter", failures >= 3 ? now + 60_000 : 0).apply();
                input.setError(activity.getString(failures >= 3 ? R.string.event_pin_wait : R.string.event_pin_incorrect));return;
            }
            bypass = policyKey();
            prefs.edit().putString("bypass", bypass).putInt("failures", 0).putLong("retryAfter", 0).apply();
            stop();unlockDialog.dismiss();changed.run();
        }));
        unlockDialog.show();
    }

    void retryPinning() {
        bypass = "";prefs.edit().remove("bypass").apply();
        prompted = false;error = false;sync();changed.run();
    }

    private void stop() {
        try { activity.stopLockTask(); }
        catch (RuntimeException ignored) { }
        error = false;
    }
}
