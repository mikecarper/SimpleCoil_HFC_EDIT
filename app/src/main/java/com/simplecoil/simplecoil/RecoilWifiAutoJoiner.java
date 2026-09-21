/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.simplecoil.simplecoil;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * Joins the strongest visible open Recoil game hub while the main activity is foreground.
 *
 * <p>The original Android 5.1 Wi-Fi APIs are intentionally used here: this app supports
 * Lollipop devices, where {@link WifiConfiguration} is the supported way to join a known
 * field hub.  Protected access points are deliberately ignored; a prefix alone must never
 * cause the app to overwrite a user's saved Wi-Fi password.</p>
 */
final class RecoilWifiAutoJoiner {
    private static final String TAG = "scwifi";
    private static final String RECOIL_PREFIX = "recoil";
    private static final long SCAN_RETRY_MS = 3_000L;
    private static final long CONNECT_RETRY_MS = 5_000L;

    private final Context mApplicationContext;
    private final WifiManager mWifiManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mStarted;
    private boolean mReceiverRegistered;
    private String mLastRequestedSsid;
    private String mLastRequestedBssid;
    private long mLastRequestAt;

    private final Runnable mRetryRunnable = new Runnable() {
        @Override
        public void run() {
            attemptAutoJoin();
        }
    };

    private final BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mStarted || intent == null)
                return;
            String action = intent.getAction();
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)
                    || WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)
                    || WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                scheduleRetry(0L);
            }
        }
    };

    RecoilWifiAutoJoiner(Context context) {
        mApplicationContext = context.getApplicationContext();
        mWifiManager = (WifiManager) mApplicationContext.getSystemService(Context.WIFI_SERVICE);
    }

    void start() {
        if (mStarted)
            return;
        mStarted = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        try {
            ContextCompat.registerReceiver(mApplicationContext, mWifiReceiver, filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            mReceiverRegistered = true;
        } catch (SecurityException e) {
            // The retry path still lets the feature work on devices which reject a receiver.
            Log.w(TAG, "Unable to watch Wi-Fi state", e);
        }
        scheduleRetry(0L);
    }

    void stop() {
        mStarted = false;
        mHandler.removeCallbacks(mRetryRunnable);
        if (!mReceiverRegistered)
            return;
        mReceiverRegistered = false;
        try {
            mApplicationContext.unregisterReceiver(mWifiReceiver);
        } catch (IllegalArgumentException e) {
            // A platform restart can unregister dynamic receivers behind our back.
            Log.w(TAG, "Wi-Fi receiver was already unregistered", e);
        }
    }

    private void scheduleRetry(long delayMillis) {
        if (!mStarted)
            return;
        mHandler.removeCallbacks(mRetryRunnable);
        mHandler.postDelayed(mRetryRunnable, delayMillis);
    }

    @SuppressLint("MissingPermission")
    private void attemptAutoJoin() {
        if (!mStarted || mWifiManager == null)
            return;
        try {
            if (!mWifiManager.isWifiEnabled()) {
                Log.d(TAG, "Wi-Fi is disabled; waiting for the user to enable it");
                return;
            }
            if (isRecoilSsid(currentSsid())) {
                Log.d(TAG, "Already connected to a Recoil hub");
                return;
            }

            ScanResult candidate = findBestEligibleNetwork(mWifiManager.getScanResults());
            if (candidate != null)
                requestConnection(candidate);

            // Scan results may be stale when a hub was just switched on.  Keep looking until
            // association succeeds, but do not scan continuously once connected.
            try {
                mWifiManager.startScan();
            } catch (SecurityException e) {
                Log.w(TAG, "Wi-Fi scan was denied", e);
            }
            scheduleRetry(candidate == null ? SCAN_RETRY_MS : CONNECT_RETRY_MS);
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to inspect Wi-Fi state", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void requestConnection(ScanResult candidate) {
        long now = System.currentTimeMillis();
        if (sameAccessPoint(candidate, mLastRequestedSsid, mLastRequestedBssid)
                && now - mLastRequestAt < CONNECT_RETRY_MS) {
            return;
        }

        int networkId = findSavedOpenNetworkId(candidate.SSID);
        if (networkId == -1) {
            WifiConfiguration configuration = new WifiConfiguration();
            configuration.SSID = quoteSsid(candidate.SSID);
            configuration.allowedKeyManagement.clear();
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            networkId = mWifiManager.addNetwork(configuration);
        }
        if (networkId == -1) {
            Log.w(TAG, "Could not save open Recoil hub " + printableSsid(candidate.SSID));
            return;
        }

        mLastRequestedSsid = candidate.SSID;
        mLastRequestedBssid = candidate.BSSID;
        mLastRequestAt = now;
        boolean enabled = mWifiManager.enableNetwork(networkId, true);
        boolean reconnecting = enabled && mWifiManager.reconnect();
        Log.i(TAG, "Requested Recoil hub " + printableSsid(candidate.SSID)
                + " (enabled=" + enabled + ", reconnecting=" + reconnecting + ")");
    }

    @SuppressLint("MissingPermission")
    private int findSavedOpenNetworkId(String ssid) {
        List<WifiConfiguration> configurations = mWifiManager.getConfiguredNetworks();
        if (configurations == null)
            return -1;
        for (WifiConfiguration configuration : configurations) {
            if (configuration != null && isSameSsid(configuration.SSID, ssid)
                    && isOpenConfiguration(configuration)) {
                return configuration.networkId;
            }
        }
        return -1;
    }

    @SuppressLint("MissingPermission")
    private String currentSsid() {
        WifiInfo info = mWifiManager.getConnectionInfo();
        return info == null ? null : info.getSSID();
    }

    static ScanResult findBestEligibleNetwork(List<ScanResult> scanResults) {
        if (scanResults == null)
            return null;
        ScanResult best = null;
        for (ScanResult result : scanResults) {
            if (result == null || !isEligibleNetwork(result.SSID, result.capabilities))
                continue;
            if (best == null || result.level > best.level)
                best = result;
        }
        return best;
    }

    static boolean isEligibleNetwork(String ssid, String capabilities) {
        return isRecoilSsid(ssid) && isOpenNetwork(capabilities);
    }

    static boolean isRecoilSsid(String ssid) {
        String normalized = normalizeSsid(ssid);
        return normalized.length() >= RECOIL_PREFIX.length()
                && normalized.regionMatches(true, 0, RECOIL_PREFIX, 0, RECOIL_PREFIX.length());
    }

    static boolean isOpenNetwork(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty())
            return true;
        String upperCapabilities = capabilities.toUpperCase(Locale.US);
        return !upperCapabilities.contains("WEP")
                && !upperCapabilities.contains("PSK")
                && !upperCapabilities.contains("EAP")
                && !upperCapabilities.contains("SAE")
                && !upperCapabilities.contains("OWE")
                && !upperCapabilities.contains("WAPI")
                && !upperCapabilities.contains("IEEE8021X");
    }

    private static boolean isOpenConfiguration(WifiConfiguration configuration) {
        return configuration.allowedKeyManagement != null
                && configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                && !configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)
                && !configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)
                && !configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X);
    }

    private static boolean sameAccessPoint(ScanResult candidate, String ssid, String bssid) {
        return candidate != null && isSameSsid(candidate.SSID, ssid)
                && (candidate.BSSID == null ? bssid == null : candidate.BSSID.equalsIgnoreCase(bssid));
    }

    private static boolean isSameSsid(String first, String second) {
        return normalizeSsid(first).equals(normalizeSsid(second));
    }

    private static String normalizeSsid(String ssid) {
        if (ssid == null)
            return "";
        String normalized = ssid.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String quoteSsid(String ssid) {
        return "\"" + normalizeSsid(ssid).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String printableSsid(String ssid) {
        String normalized = normalizeSsid(ssid);
        return normalized.isEmpty() ? "<hidden>" : normalized;
    }
}
