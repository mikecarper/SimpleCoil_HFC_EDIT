/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil;

import android.annotation.SuppressLint;
import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.util.LinkedList;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
// The activity obtains BLUETOOTH_SCAN and BLUETOOTH_CONNECT before binding on Android 12+.
@SuppressLint("MissingPermission")
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    // Diagnostic observation only: never schedule a shot or change a gun's settings.
    // Capture at the GATT callback, before UI/broadcast delays distort shot intervals.
    private static volatile boolean sFireTimingCapture;

    static void setFireTimingCapture(boolean enabled) {
        boolean capture = BuildConfig.DEBUG && enabled;
        if (sFireTimingCapture == capture)
            return;
        sFireTimingCapture = capture;
        Log.i("GunFire", "ms=" + SystemClock.elapsedRealtime()
                + " capture=" + (capture ? "enabled" : "disabled"));
    }

    private void logFireTiming(String event, UUID characteristic, byte[] data, int status) {
        if (!BuildConfig.DEBUG || !sFireTimingCapture)
            return;
        Log.d("GunFire", "ms=" + SystemClock.elapsedRealtime() + " slot=" + weaponSlot()
                + " event=" + event + " characteristic=" + characteristic
                + " status=" + status + " data=" + Arrays.toString(data));
    }

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private volatile BluetoothGatt mBluetoothGatt;
    private static final AtomicLong CONNECTION_IDS = new AtomicLong();
    private long mConnectionId;
    private int mConnectionState = STATE_DISCONNECTED;
    private boolean mConnectionForeground;
    private PowerManager.WakeLock mConnectionWakeLock;

    /** A connected blaster continues delivering telemetry when another app has focus. */
    @SuppressLint("WakelockTimeout") // Released by close()/onUnbind()/onDestroy().
    private void keepConnectionActive() {
        if (getBaseContext() == null) return;
        try {
            String channel = "gun_connection";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null)
                manager.createNotificationChannel(new NotificationChannel(channel,
                        getString(R.string.gun_connection_channel), NotificationManager.IMPORTANCE_LOW));
            Intent open = new Intent(this, FullscreenActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent returnToGame = PendingIntent.getActivity(this, weaponSlot(), open, flags);
            startForeground(17520 + weaponSlot(), new NotificationCompat.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_gun_connection)
                    .setContentTitle(getString(R.string.gun_connection_title))
                    .setContentText(getString(R.string.gun_connection_background))
                    .setContentIntent(returnToGame).setOngoing(true).setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW).build());
            mConnectionForeground = true;
            if (mConnectionWakeLock == null) {
                PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                if (power != null) {
                    mConnectionWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            "SimpleCoil:GunConnection:" + weaponSlot());
                    mConnectionWakeLock.setReferenceCounted(false);
                }
            }
            if (mConnectionWakeLock != null && !mConnectionWakeLock.isHeld()) mConnectionWakeLock.acquire();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to prioritize the gun connection", e);
        }
    }

    private void releaseConnectionPriority() {
        if (mConnectionWakeLock != null && mConnectionWakeLock.isHeld()) mConnectionWakeLock.release();
        mConnectionWakeLock = null;
        if (mConnectionForeground) {
            stopForeground(true);
            mConnectionForeground = false;
        }
    }

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String TELEMETRY_DATA_AVAILABLE =
            "com.example.bluetooth.le.TELEMETRY_DATA_AVAILABLE";
    public final static String ID_DATA_AVAILABLE =
            "com.example.bluetooth.le.ID_DATA_AVAILABLE";
    public final static String CHARACTERISTIC_WRITE_FINISHED =
            "com.example.bluetooth.le.CHARACTERISTIC_WRITE_FINISHED";
    public final static String DESCRIPTOR_WRITE_FINISHED =
            "com.example.bluetooth.le.DESCRIPTOR_WRITE_FINISHED";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";
    public final static String EXTRA_UUID =
            "com.example.bluetooth.le.EXTRA_UUID";
    public final static String EXTRA_STATUS =
            "com.example.bluetooth.le.EXTRA_STATUS";
    public final static String EXTRA_WEAPON_SLOT =
            "com.example.bluetooth.le.EXTRA_WEAPON_SLOT";
    public final static String EXTRA_COMPLETED_AT =
            "com.example.bluetooth.le.EXTRA_COMPLETED_AT";
    public final static String EXTRA_CONNECTION_ID =
            "com.example.bluetooth.le.EXTRA_CONNECTION_ID";

    /** A separate service component supplies its own GATT handle and operation queue. */
    protected int weaponSlot() {
        return 0;
    }

    public final static UUID UUID_RECOIL_TELEMETRY =
            UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID);
    public final static UUID UUID_RECOIL_ID =
            UUID.fromString(GattAttributes.RECOIL_ID_UUID);

    // A stalled GATT callback must not let callers retain an unbounded number of payloads.
    static final int MAX_QUEUED_GATT_OPERATIONS = 64;
    static final int MAX_COMPLETED_WRITE_RESULTS = 64;
    static final long GATT_OPERATION_TIMEOUT_MS = 5_000L;
    static final long GATT_CONNECT_TIMEOUT_MS = 10_000L;
    static final long GATT_SETUP_TIMEOUT_MS = 5_000L;
    static final long GATT_TELEMETRY_TIMEOUT_MS = 5_000L;

    private boolean mActionAvailable = true;
    private final Queue<CharacteristicWrite> mCharacteristicWriteQueue = new LinkedList<>();
    private final Queue<DescriptorWrite> mDescriptorWriteQueue = new LinkedList<>();
    private final Queue<BluetoothGattCharacteristic> mCharacteristicReadQueue = new LinkedList<>();
    private CharacteristicWrite mActiveCharacteristicWrite;
    private DescriptorWrite mActiveDescriptorWrite;
    private BluetoothGattCharacteristic mActiveCharacteristicRead;
    private final Handler mGattOperationHandler = new Handler(Looper.getMainLooper());
    private Runnable mGattOperationTimeout;
    private Runnable mConnectionHealthTimeout;
    private long mConnectionHealthDeadline;
    private boolean mTelemetryExpected;
    private boolean mTelemetryReceived;
    private BluetoothGattCharacteristic mTelemetryNotificationCharacteristic;
    private final Queue<CompletedWriteResult> mCompletedWriteResults = new LinkedList<>();

    // Android 5.1 uses mutable characteristic objects. Keep each operation's payload
    // separate from that object until it actually reaches the front of the queue.
    static final class CharacteristicWrite {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        final int writeType;
        final BluetoothGatt gatt;
        final long connectionId;

        CharacteristicWrite(BluetoothGattCharacteristic characteristic, byte[] value) {
            this(characteristic, value, null);
        }

        CharacteristicWrite(BluetoothGattCharacteristic characteristic, byte[] value, BluetoothGatt gatt) {
            this(characteristic, value, gatt, 0);
        }

        CharacteristicWrite(BluetoothGattCharacteristic characteristic, byte[] value,
                            BluetoothGatt gatt, long connectionId) {
            this.characteristic = characteristic;
            this.value = value.clone();
            writeType = characteristic.getWriteType();
            this.gatt = gatt;
            this.connectionId = connectionId;
        }

        void prepare() {
            characteristic.setWriteType(writeType);
            characteristic.setValue(value.clone());
        }
    }

    private static final class CompletedWriteResult {
        final CharacteristicWrite write;
        final int status;
        final long completedAt;

        CompletedWriteResult(CharacteristicWrite write, int status) {
            this.write = write;
            this.status = status;
            completedAt = SystemClock.elapsedRealtime();
        }
    }

    static final class DescriptorWrite {
        final BluetoothGattDescriptor descriptor;
        final byte[] value;

        DescriptorWrite(BluetoothGattDescriptor descriptor) {
            this.descriptor = descriptor;
            value = descriptor.getValue().clone();
        }

        void prepare() {
            descriptor.setValue(value.clone());
        }
    }

    private boolean isCurrentGatt(BluetoothGatt gatt) {
        // Keep the service monitor held from this check through the callback's effects.
        return gatt != null && gatt == mBluetoothGatt;
    }

    private boolean hasGattQueueCapacity() {
        return mCharacteristicWriteQueue.size() + mDescriptorWriteQueue.size()
                + mCharacteristicReadQueue.size() < MAX_QUEUED_GATT_OPERATIONS;
    }

    private synchronized void clearPendingGattOperations() {
        cancelGattOperationTimeout();
        mActionAvailable = true;
        mActiveCharacteristicWrite = null;
        mActiveDescriptorWrite = null;
        mActiveCharacteristicRead = null;
        mCharacteristicWriteQueue.clear();
        mDescriptorWriteQueue.clear();
        mCharacteristicReadQueue.clear();
    }

    private Intent writeResultIntent(CompletedWriteResult result) {
        CharacteristicWrite write = result.write;
        Intent intent = new Intent(CHARACTERISTIC_WRITE_FINISHED);
        intent.putExtra(EXTRA_WEAPON_SLOT, weaponSlot());
        intent.putExtra(EXTRA_UUID, write.characteristic.getUuid().toString());
        intent.putExtra(EXTRA_DATA, write.value.clone());
        intent.putExtra(EXTRA_STATUS, result.status);
        intent.putExtra(EXTRA_COMPLETED_AT, result.completedAt);
        intent.putExtra(EXTRA_CONNECTION_ID, write.connectionId);
        return intent;
    }

    private void forgetCompletedWriteResult(BluetoothGattCharacteristic characteristic, byte[] value) {
        Iterator<CompletedWriteResult> results = mCompletedWriteResults.iterator();
        while (results.hasNext()) {
            CharacteristicWrite write = results.next().write;
            if (write.characteristic == characteristic && Arrays.equals(write.value, value)) results.remove();
        }
    }

    /** Recover an exact ACK missed while the activity receiver was paused, never an old connection's result. */
    public synchronized Intent getCompletedWriteResult(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (mBluetoothGatt == null || characteristic == null || value == null) return null;
        // An earlier identical command can complete after a wrapped-ID copy
        // was queued. Its ACK cannot stand in for that unfinished submission.
        if (mActiveCharacteristicWrite != null && mActiveCharacteristicWrite.characteristic == characteristic
                && Arrays.equals(mActiveCharacteristicWrite.value, value)) return null;
        for (CharacteristicWrite write : mCharacteristicWriteQueue)
            if (write.characteristic == characteristic && Arrays.equals(write.value, value)) return null;
        for (CompletedWriteResult result : mCompletedWriteResults) {
            CharacteristicWrite write = result.write;
            if (write.gatt == mBluetoothGatt && write.characteristic == characteristic
                    && write.characteristic.getUuid().equals(characteristic.getUuid())
                    && Arrays.equals(write.value, value)) return writeResultIntent(result);
        }
        return null;
    }

    private void broadcastWriteFinished(CharacteristicWrite write, int status) {
        CompletedWriteResult result = new CompletedWriteResult(write, status);
        logFireTiming("write_finished", write.characteristic.getUuid(), write.value, status);
        if (write.gatt != null && write.gatt == mBluetoothGatt) {
            forgetCompletedWriteResult(write.characteristic, write.value);
            while (mCompletedWriteResults.size() >= MAX_COMPLETED_WRITE_RESULTS) mCompletedWriteResults.remove();
            mCompletedWriteResults.add(result);
        }
        sendBroadcast(writeResultIntent(result));
    }

    private List<CharacteristicWrite> pendingCharacteristicWrites() {
        List<CharacteristicWrite> failed = new ArrayList<>();
        if (mActiveCharacteristicWrite != null) failed.add(mActiveCharacteristicWrite);
        failed.addAll(mCharacteristicWriteQueue);
        return failed;
    }

    private void failPendingGattOperations() {
        List<CharacteristicWrite> failed = pendingCharacteristicWrites();
        clearPendingGattOperations();
        for (CharacteristicWrite write : failed) broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
    }

    private void cancelGattOperationTimeout() {
        if (mGattOperationTimeout != null) mGattOperationHandler.removeCallbacks(mGattOperationTimeout);
        mGattOperationTimeout = null;
    }

    private void cancelConnectionHealthTimeout() {
        if (mConnectionHealthTimeout != null)
            mGattOperationHandler.removeCallbacks(mConnectionHealthTimeout);
        mConnectionHealthTimeout = null;
        mConnectionHealthDeadline = 0;
    }

    private void startConnectionHealthTimeout(final BluetoothGatt gatt, long waitMs) {
        cancelConnectionHealthTimeout();
        final long connectionId = mConnectionId;
        mConnectionHealthDeadline = SystemClock.elapsedRealtime() + waitMs;
        mConnectionHealthTimeout = new Runnable() {
            @Override public void run() {
                synchronized (BluetoothLeService.this) {
                    if (mConnectionHealthTimeout != this || !isCurrentGatt(gatt)
                            || connectionId != mConnectionId) return;
                    long remaining = mConnectionHealthDeadline - SystemClock.elapsedRealtime();
                    if (remaining > 0) {
                        mGattOperationHandler.postDelayed(this, remaining);
                        return;
                    }
                    retireGattConnection(gatt, "Connection setup or telemetry timed out");
                }
            }
        };
        mGattOperationHandler.postDelayed(mConnectionHealthTimeout, waitMs);
    }

    private void retireGattConnection(BluetoothGatt gatt, String reason) {
        if (!isCurrentGatt(gatt)) return;
        Log.w(TAG, reason + "; reconnecting the affected gun");
        List<CharacteristicWrite> failed = pendingCharacteristicWrites();
        close();
        for (CharacteristicWrite write : failed)
            broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
        broadcastUpdate(ACTION_GATT_DISCONNECTED);
    }

    private boolean isTelemetryEnable(DescriptorWrite write) {
        return mTelemetryExpected && write.descriptor.getCharacteristic() == mTelemetryNotificationCharacteristic
                && GattAttributes.CLIENT_CHARACTERISTIC_CONFIG.equals(write.descriptor.getUuid().toString())
                && Arrays.equals(write.value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    }

    private void startGattOperationTimeout(final BluetoothGatt gatt, final Object operation) {
        cancelGattOperationTimeout();
        mGattOperationTimeout = new Runnable() {
            @Override public void run() {
                synchronized (BluetoothLeService.this) {
                    if (mGattOperationTimeout != this || !isCurrentGatt(gatt)
                            || operation != mActiveCharacteristicWrite && operation != mActiveDescriptorWrite
                            && operation != mActiveCharacteristicRead) return;
                    Log.w(TAG, "GATT operation timed out; reconnecting instead of advancing a stale handle");
                    List<CharacteristicWrite> failed = pendingCharacteristicWrites();
                    // A delayed ACK only identifies its handle/characteristic, not
                    // its operation. Retire the handle before permitting any retry.
                    close();
                    for (CharacteristicWrite write : failed)
                        broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
                    broadcastUpdate(ACTION_GATT_DISCONNECTED);
                }
            }
        };
        mGattOperationHandler.postDelayed(mGattOperationTimeout, GATT_OPERATION_TIMEOUT_MS);
    }

    private boolean startCharacteristicWrite(BluetoothGatt gatt, CharacteristicWrite write) {
        write.prepare();
        logFireTiming("write_started", write.characteristic.getUuid(), write.value, -1);
        // Android can dispatch a completion before writeCharacteristic() returns.
        // Mark the transport busy before calling into the framework so that
        // callback cannot be overwritten by a stale false assignment afterward.
        mActionAvailable = false;
        mActiveCharacteristicWrite = write;
        startGattOperationTimeout(gatt, write);
        if (gatt.writeCharacteristic(write.characteristic))
            return true;
        if (mActiveCharacteristicWrite != write) return true;
        cancelGattOperationTimeout();
        mActiveCharacteristicWrite = null;
        mActionAvailable = true;
        broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
        return false;
    }

    private boolean startDescriptorWrite(BluetoothGatt gatt, DescriptorWrite write) {
        write.prepare();
        mActionAvailable = false;
        mActiveDescriptorWrite = write;
        startGattOperationTimeout(gatt, write);
        if (gatt.writeDescriptor(write.descriptor))
            return true;
        if (mActiveDescriptorWrite != write) return true;
        cancelGattOperationTimeout();
        mActiveDescriptorWrite = null;
        mActionAvailable = true;
        if (isTelemetryEnable(write))
            retireGattConnection(gatt, "Telemetry notification descriptor was rejected");
        return false;
    }

    private boolean startCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        mActionAvailable = false;
        mActiveCharacteristicRead = characteristic;
        startGattOperationTimeout(gatt, characteristic);
        if (gatt.readCharacteristic(characteristic))
            return true;
        if (mActiveCharacteristicRead != characteristic) return true;
        cancelGattOperationTimeout();
        mActiveCharacteristicRead = null;
        mActionAvailable = true;
        return false;
    }

    /**
     * A GATT operation completes asynchronously.  If a queued operation cannot be started,
     * keep advancing instead of waiting forever for a callback that will never arrive.
     */
    private synchronized void startNextGattOperation(BluetoothGatt gatt) {
        if (!isCurrentGatt(gatt)) {
            // A late callback must not discard the replacement connection's queue.
            return;
        }
        try {
            while (true) {
                if (!isCurrentGatt(gatt)) return;
                if (!mCharacteristicWriteQueue.isEmpty()) {
                    CharacteristicWrite write = mCharacteristicWriteQueue.remove();
                    if (startCharacteristicWrite(gatt, write))
                        return;
                    Log.w(TAG, "Failed to write queued characteristic " + write.characteristic.getUuid());
                    continue;
                }
                if (!mDescriptorWriteQueue.isEmpty()) {
                    DescriptorWrite write = mDescriptorWriteQueue.remove();
                    if (startDescriptorWrite(gatt, write))
                        return;
                    Log.w(TAG, "Failed to write queued descriptor " + write.descriptor.getUuid());
                    continue;
                }
                if (!mCharacteristicReadQueue.isEmpty()) {
                    BluetoothGattCharacteristic characteristic = mCharacteristicReadQueue.remove();
                    if (startCharacteristicRead(gatt, characteristic))
                        return;
                    Log.w(TAG, "Failed to read queued characteristic " + characteristic.getUuid());
                    continue;
                }
                mActionAvailable = true;
                return;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while processing queued operations", e);
            if (mActiveDescriptorWrite != null && isTelemetryEnable(mActiveDescriptorWrite))
                retireGattConnection(gatt, "Queued telemetry descriptor permission was revoked");
            else failPendingGattOperations();
        }
    }

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            synchronized (BluetoothLeService.this) {
                // connect() publishes the new handle under this same monitor. An early
                // callback must wait for it, and an old one must not affect a replacement.
                if (!isCurrentGatt(gatt)) {
                    Log.d(TAG, "Ignoring callback from a closed GATT connection");
                    return;
                }
                String intentAction;
                if (newState != BluetoothProfile.STATE_DISCONNECTED && status != BluetoothGatt.GATT_SUCCESS) {
                    retireGattConnection(gatt, "GATT connection failed with status " + status);
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    intentAction = ACTION_GATT_CONNECTED;
                    mConnectionState = STATE_CONNECTED;
                    startConnectionHealthTimeout(gatt, GATT_SETUP_TIMEOUT_MS);
                    broadcastUpdate(intentAction);
                    Log.i(TAG, "Connected to GATT server.");
                    // Attempts to discover services after successful connection.
                    try {
                        if (!gatt.discoverServices())
                            retireGattConnection(gatt, "Service discovery was rejected");
                    } catch (SecurityException e) {
                        Log.w(TAG, "Bluetooth permission was revoked before service discovery", e);
                        retireGattConnection(gatt, "Service discovery permission was revoked");
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    intentAction = ACTION_GATT_DISCONNECTED;
                    // Even a disconnected framework handle can deliver late
                    // callbacks. Retain its event ID, but never revive it.
                    close();
                    Log.i(TAG, "Disconnected from GATT server: " + status);
                    broadcastUpdate(intentAction);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            synchronized (BluetoothLeService.this) {
                if (!isCurrentGatt(gatt)) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    boolean found = false;
                    try {
                        for (BluetoothGattService service : gatt.getServices()) {
                            if (GattAttributes.RECOIL_MAIN_SERVICE.equals(service.getUuid().toString())
                                    && service.getCharacteristic(UUID_RECOIL_TELEMETRY) != null
                                    && service.getCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID)) != null)
                                found = true;
                        }
                    } catch (SecurityException e) { Log.w(TAG, "Cannot inspect gun services", e); }
                    if (!found) {
                        retireGattConnection(gatt, "Required gun services are missing");
                        return;
                    }
                    startConnectionHealthTimeout(gatt, GATT_SETUP_TIMEOUT_MS);
                    broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                } else {
                    retireGattConnection(gatt, "Service discovery failed with status " + status);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            synchronized (BluetoothLeService.this) {
                // An unrelated or late completion must not advance another request's queue.
                if (!isCurrentGatt(gatt) || mActiveCharacteristicRead == null
                        || mActiveCharacteristicRead != characteristic)
                    return;
                cancelGattOperationTimeout();
                mActiveCharacteristicRead = null;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "read success!");
                    broadcastUpdate(characteristic);
                } else {
                    Log.d(TAG, "read failed");
                }
                startNextGattOperation(gatt);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            synchronized (BluetoothLeService.this) {
                if (!isCurrentGatt(gatt) || mActiveCharacteristicWrite == null
                        || mActiveCharacteristicWrite.characteristic != characteristic)
                    return;
                CharacteristicWrite completed = mActiveCharacteristicWrite;
                cancelGattOperationTimeout();
                mActiveCharacteristicWrite = null;
                // Report the completed snapshot, not a value changed by the next write.
                broadcastWriteFinished(completed, status);
                startNextGattOperation(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            // The framework can reuse this mutable object while another callback
            // waits for the service monitor. Preserve this notification first.
            byte[] value = characteristic.getValue();
            byte[] snapshot = value == null ? null : value.clone();
            synchronized (BluetoothLeService.this) {
                if (!isCurrentGatt(gatt)) return;
                if (mTelemetryExpected && characteristic == mTelemetryNotificationCharacteristic
                        && snapshot != null && snapshot.length == 20) {
                    mTelemetryReceived = true;
                    mConnectionHealthDeadline = SystemClock.elapsedRealtime() + GATT_TELEMETRY_TIMEOUT_MS;
                }
                broadcastUpdate(characteristic, snapshot);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                          BluetoothGattDescriptor descriptor,
                                          int status) {
            synchronized (BluetoothLeService.this) {
                if (!isCurrentGatt(gatt) || mActiveDescriptorWrite == null
                        || mActiveDescriptorWrite.descriptor != descriptor)
                    return;
                cancelGattOperationTimeout();
                DescriptorWrite completed = mActiveDescriptorWrite;
                mActiveDescriptorWrite = null;
                if (isTelemetryEnable(completed)) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        retireGattConnection(gatt, "Telemetry notification descriptor failed");
                        return;
                    }
                    if (!mTelemetryReceived)
                        startConnectionHealthTimeout(gatt, GATT_TELEMETRY_TIMEOUT_MS);
                }
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "descriptor write success!");
                } else {
                    Log.d(TAG, "descriptor write failed");
                }
                startNextGattOperation(gatt);
                broadcastUpdate(DESCRIPTOR_WRITE_FINISHED);
            }
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        intent.putExtra(EXTRA_WEAPON_SLOT, weaponSlot());
        intent.putExtra(EXTRA_CONNECTION_ID, mConnectionId);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        broadcastUpdate(characteristic, value == null ? null : value.clone());
    }

    private void broadcastUpdate(final BluetoothGattCharacteristic characteristic, byte[] data) {
        if (UUID_RECOIL_TELEMETRY.equals((characteristic.getUuid()))) {
            if (data == null)
                return;
            logFireTiming("telemetry", characteristic.getUuid(), data, BluetoothGatt.GATT_SUCCESS);
            final Intent intent = new Intent(TELEMETRY_DATA_AVAILABLE);
            intent.putExtra(EXTRA_WEAPON_SLOT, weaponSlot());
            intent.putExtra(EXTRA_CONNECTION_ID, mConnectionId);
            intent.putExtra(EXTRA_DATA, data.clone());
            sendBroadcast(intent);
        } else if (UUID_RECOIL_ID.equals((characteristic.getUuid()))) {
            if (data == null || data.length <= 10) {
                Log.w(TAG, "Ignoring short ID characteristic response");
                return;
            }
            int firmwareVer = ((data[0] & 0xFF) << 8) + (data[1] & 0xFF);
            Log.d(TAG, "Firmware version: " + firmwareVer);
            // This gets the blaster type, 1 for rifle and 2 for pistol
            final Intent intent = new Intent(ID_DATA_AVAILABLE);
            intent.putExtra(EXTRA_WEAPON_SLOT, weaponSlot());
            intent.putExtra(EXTRA_CONNECTION_ID, mConnectionId);
            intent.putExtra(EXTRA_DATA, data[10]);
            sendBroadcast(intent);
        } else {
            Log.e(TAG, "unexpected characteristic data from " + characteristic.getUuid().toString());
        }
    }

    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        synchronized (this) {
            clearPendingGattOperations();
            mCompletedWriteResults.clear();
        }

        return true;
    }

    /** Readiness query for activity setup missed while its receiver was paused. */
    public synchronized boolean isConnected() {
        return mBluetoothGatt != null && mConnectionState == STATE_CONNECTED;
    }

    /** Reject delayed broadcasts from an older handle or a destroyed service instance. */
    public synchronized boolean isCurrentConnectionEvent(Intent event) {
        if (!event.hasExtra(EXTRA_CONNECTION_ID)) return true;
        if (event.getLongExtra(EXTRA_CONNECTION_ID, -1) != mConnectionId) return false;
        if (ACTION_GATT_DISCONNECTED.equals(event.getAction())) return true;
        if (CHARACTERISTIC_WRITE_FINISHED.equals(event.getAction())
                && event.getIntExtra(EXTRA_STATUS, BluetoothGatt.GATT_FAILURE) != BluetoothGatt.GATT_SUCCESS)
            return true;
        return isConnected();
    }

    /** The activity must not judge radio health by delayed UI broadcasts. */
    public synchronized boolean monitorsConnectionHealth() {
        return mBluetoothGatt != null && mConnectionHealthTimeout != null;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public synchronized boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            // Resuming an activity must not reconnect an already healthy GATT
            // session or restart a connection/discovery still in flight.
            if (mConnectionState == STATE_CONNECTED || mConnectionState == STATE_CONNECTING) return true;
            // A disconnected handle can still dispatch old callbacks. Publish
            // a fresh handle and identity for the next connection attempt.
        }

        final BluetoothDevice device;
        try {
            device = mBluetoothAdapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid Bluetooth device address", e);
            return false;
        }
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        final BluetoothGatt gatt;
        try {
            gatt = device.connectGatt(this, false, mGattCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while connecting", e);
            return false;
        }
        if (gatt == null) {
            Log.w(TAG, "Unable to create GATT connection");
            return false;
        }
        // Only retire the previous handle after creating its replacement. Its
        // callbacks cannot finish the new connection's operations, so discard
        // the old queue as well before publishing the new handle.
        close();
        mBluetoothGatt = gatt;
        mConnectionId = CONNECTION_IDS.incrementAndGet();
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        startConnectionHealthTimeout(gatt, GATT_CONNECT_TIMEOUT_MS);
        keepConnectionActive();
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public synchronized void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        try {
            mBluetoothGatt.disconnect();
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while disconnecting", e);
        }
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public synchronized void close() {
        BluetoothGatt gatt = mBluetoothGatt;
        // Retire the handle and queue before framework cleanup can dispatch callbacks.
        mBluetoothGatt = null;
        mBluetoothDeviceAddress = null;
        mConnectionState = STATE_DISCONNECTED;
        cancelConnectionHealthTimeout();
        mTelemetryExpected = mTelemetryReceived = false;
        mTelemetryNotificationCharacteristic = null;
        releaseConnectionPriority();
        clearPendingGattOperations();
        mCompletedWriteResults.clear();
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException e) {
                Log.w(TAG, "Bluetooth permission was revoked while closing", e);
            }
        }
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public synchronized void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        if ((!mActionAvailable)) {
            if (!hasGattQueueCapacity()) {
                Log.w(TAG, "Dropping read because the GATT operation queue is full");
                return;
            }
            Log.d(TAG, "Reading not available yet, queuing...");
            mCharacteristicReadQueue.add(characteristic);
            return;
        }
        try {
            if (startCharacteristicRead(mBluetoothGatt, characteristic)) {
                Log.d(TAG, "read the char");
            } else {
                Log.d(TAG, "failed to read the char");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while reading", e);
            clearPendingGattOperations();
        }
    }

    public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (characteristic != null)
            writeCharacteristic(characteristic, characteristic.getValue());
    }

    public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (characteristic == null || value == null)
            return;
        // Command IDs wrap. A new identical submission must not find an old ACK
        // while it is queued or in flight on this same connection.
        forgetCompletedWriteResult(characteristic, value);
        CharacteristicWrite write = new CharacteristicWrite(characteristic, value, mBluetoothGatt, mConnectionId);
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
            return;
        }
        if ((!mActionAvailable)) {
            if (!hasGattQueueCapacity()) {
                Log.w(TAG, "Dropping write because the GATT operation queue is full");
                broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
                return;
            }
            Log.d(TAG, "Writing not available yet, queuing...");
            mCharacteristicWriteQueue.add(write);
            return;
        }
        try {
            if (startCharacteristicWrite(mBluetoothGatt, write)) {
                //Log.d(TAG, "wrote char");
            } else {
                Log.d(TAG, "failed to write char");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while writing", e);
            failPendingGattOperations();
        }
    }

    public synchronized void writeDescriptor(BluetoothGattDescriptor descriptor) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null || descriptor == null || descriptor.getValue() == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        if (!mActionAvailable) {
            if (!hasGattQueueCapacity()) {
                Log.w(TAG, "Dropping descriptor write because the GATT operation queue is full");
                if (isTelemetryEnable(new DescriptorWrite(descriptor)))
                    retireGattConnection(mBluetoothGatt, "Telemetry descriptor queue is full");
                return;
            }
            Log.d(TAG, "Writing not available yet, queuing...");
            mDescriptorWriteQueue.add(new DescriptorWrite(descriptor));
            return;
        }
        try {
            if (startDescriptorWrite(mBluetoothGatt, new DescriptorWrite(descriptor))) {
                //Log.d(TAG, "wrote descriptor success");
            } else {
                Log.d(TAG, "wrote descriptor FAIL");
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while writing a descriptor", e);
            if (isTelemetryEnable(new DescriptorWrite(descriptor)))
                retireGattConnection(mBluetoothGatt, "Telemetry descriptor permission was revoked");
            else clearPendingGattOperations();
        }
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public synchronized void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null || characteristic == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        final BluetoothGatt gatt = mBluetoothGatt;
        boolean telemetry = UUID_RECOIL_TELEMETRY.equals(characteristic.getUuid());
        if (telemetry) {
            mTelemetryExpected = enabled;
            mTelemetryReceived = false;
            mTelemetryNotificationCharacteristic = enabled ? characteristic : null;
            if (enabled) startConnectionHealthTimeout(gatt, GATT_SETUP_TIMEOUT_MS);
            else cancelConnectionHealthTimeout();
        }
        try {
            if (!mBluetoothGatt.setCharacteristicNotification(characteristic, enabled)) {
                Log.w(TAG, "Failed to change characteristic notification state");
                if (telemetry && enabled) retireGattConnection(gatt, "Telemetry notifications were rejected");
                return;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while changing notifications", e);
            if (telemetry && enabled) retireGattConnection(gatt, "Telemetry notification permission was revoked");
            else failPendingGattOperations();
            return;
        }

        if (UUID_RECOIL_TELEMETRY.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            if (descriptor == null) {
                Log.w(TAG, "Telemetry characteristic has no client configuration descriptor");
                if (enabled) retireGattConnection(gatt, "Telemetry notification descriptor is missing");
                return;
            }
            if (enabled) {
                Log.d(TAG, "Telling telemetry to enable notifications");
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            } else {
                Log.d(TAG, "Telling telemetry to disable notifications");
                descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            }
            writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public synchronized List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;

        try {
            return mBluetoothGatt.getServices();
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while reading services", e);
            return null;
        }
    }
}
