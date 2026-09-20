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
import android.os.IBinder;
import android.util.Log;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
// The activity obtains BLUETOOTH_SCAN and BLUETOOTH_CONNECT before binding on Android 12+.
@SuppressLint("MissingPermission")
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private volatile BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

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

    public final static UUID UUID_RECOIL_TELEMETRY =
            UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID);
    public final static UUID UUID_RECOIL_ID =
            UUID.fromString(GattAttributes.RECOIL_ID_UUID);

    // A stalled GATT callback must not let callers retain an unbounded number of payloads.
    static final int MAX_QUEUED_GATT_OPERATIONS = 64;

    private boolean mActionAvailable = true;
    private final Queue<CharacteristicWrite> mCharacteristicWriteQueue = new LinkedList<>();
    private final Queue<DescriptorWrite> mDescriptorWriteQueue = new LinkedList<>();
    private final Queue<BluetoothGattCharacteristic> mCharacteristicReadQueue = new LinkedList<>();
    private CharacteristicWrite mActiveCharacteristicWrite;
    private DescriptorWrite mActiveDescriptorWrite;
    private BluetoothGattCharacteristic mActiveCharacteristicRead;

    // Android 5.1 uses mutable characteristic objects. Keep each operation's payload
    // separate from that object until it actually reaches the front of the queue.
    static final class CharacteristicWrite {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        final int writeType;

        CharacteristicWrite(BluetoothGattCharacteristic characteristic, byte[] value) {
            this.characteristic = characteristic;
            this.value = value.clone();
            writeType = characteristic.getWriteType();
        }

        void prepare() {
            characteristic.setWriteType(writeType);
            characteristic.setValue(value.clone());
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
        mActionAvailable = true;
        mActiveCharacteristicWrite = null;
        mActiveDescriptorWrite = null;
        mActiveCharacteristicRead = null;
        mCharacteristicWriteQueue.clear();
        mDescriptorWriteQueue.clear();
        mCharacteristicReadQueue.clear();
    }

    private void broadcastWriteFinished(CharacteristicWrite write, int status) {
        Intent intent = new Intent(CHARACTERISTIC_WRITE_FINISHED);
        intent.putExtra(EXTRA_UUID, write.characteristic.getUuid().toString());
        intent.putExtra(EXTRA_DATA, write.value.clone());
        intent.putExtra(EXTRA_STATUS, status);
        sendBroadcast(intent);
    }

    private void failPendingGattOperations() {
        if (mActiveCharacteristicWrite != null)
            broadcastWriteFinished(mActiveCharacteristicWrite, BluetoothGatt.GATT_FAILURE);
        for (CharacteristicWrite write : mCharacteristicWriteQueue)
            broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
        clearPendingGattOperations();
    }

    private boolean startCharacteristicWrite(BluetoothGatt gatt, CharacteristicWrite write) {
        write.prepare();
        // Android can dispatch a completion before writeCharacteristic() returns.
        // Mark the transport busy before calling into the framework so that
        // callback cannot be overwritten by a stale false assignment afterward.
        mActionAvailable = false;
        mActiveCharacteristicWrite = write;
        if (gatt.writeCharacteristic(write.characteristic))
            return true;
        mActiveCharacteristicWrite = null;
        mActionAvailable = true;
        broadcastWriteFinished(write, BluetoothGatt.GATT_FAILURE);
        return false;
    }

    private boolean startDescriptorWrite(BluetoothGatt gatt, DescriptorWrite write) {
        write.prepare();
        mActionAvailable = false;
        mActiveDescriptorWrite = write;
        if (gatt.writeDescriptor(write.descriptor))
            return true;
        mActiveDescriptorWrite = null;
        mActionAvailable = true;
        return false;
    }

    private boolean startCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        mActionAvailable = false;
        mActiveCharacteristicRead = characteristic;
        if (gatt.readCharacteristic(characteristic))
            return true;
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
            failPendingGattOperations();
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
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    intentAction = ACTION_GATT_CONNECTED;
                    mConnectionState = STATE_CONNECTED;
                    broadcastUpdate(intentAction);
                    Log.i(TAG, "Connected to GATT server.");
                    // Attempts to discover services after successful connection.
                    try {
                        Log.i(TAG, "Attempting to start service discovery:" +
                                gatt.discoverServices());
                    } catch (SecurityException e) {
                        Log.w(TAG, "Bluetooth permission was revoked before service discovery", e);
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    intentAction = ACTION_GATT_DISCONNECTED;
                    mConnectionState = STATE_DISCONNECTED;
                    clearPendingGattOperations();
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
                    broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
                } else {
                    Log.w(TAG, "onServicesDiscovered received: " + status);
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
                mActiveCharacteristicWrite = null;
                // Report the completed snapshot, not a value changed by the next write.
                broadcastWriteFinished(completed, status);
                startNextGattOperation(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            synchronized (BluetoothLeService.this) {
                if (!isCurrentGatt(gatt)) return;
                broadcastUpdate(characteristic);
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
                mActiveDescriptorWrite = null;
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
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final BluetoothGattCharacteristic characteristic) {
        if (UUID_RECOIL_TELEMETRY.equals((characteristic.getUuid()))) {
            byte[] data = characteristic.getValue();
            if (data == null)
                return;
            final Intent intent = new Intent(TELEMETRY_DATA_AVAILABLE);
            intent.putExtra(EXTRA_DATA, data.clone());
            sendBroadcast(intent);
        } else if (UUID_RECOIL_ID.equals((characteristic.getUuid()))) {
            final byte[] data = characteristic.getValue();
            if (data == null || data.length <= 10) {
                Log.w(TAG, "Ignoring short ID characteristic response");
                return;
            }
            int firmwareVer = ((data[0] & 0xFF) << 8) + (data[1] & 0xFF);
            Log.d(TAG, "Firmware version: " + firmwareVer);
            // This gets the blaster type, 1 for rifle and 2 for pistol
            final Intent intent = new Intent(ID_DATA_AVAILABLE);
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
        }

        return true;
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
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            try {
                if (mBluetoothGatt.connect()) {
                    mConnectionState = STATE_CONNECTING;
                    return true;
                }
                return false;
            } catch (SecurityException e) {
                Log.w(TAG, "Bluetooth permission was revoked while reconnecting", e);
                return false;
            }
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
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
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
        clearPendingGattOperations();
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
        CharacteristicWrite write = new CharacteristicWrite(characteristic, value);
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
            clearPendingGattOperations();
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
        try {
            if (!mBluetoothGatt.setCharacteristicNotification(characteristic, enabled)) {
                Log.w(TAG, "Failed to change characteristic notification state");
                return;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth permission was revoked while changing notifications", e);
            failPendingGattOperations();
            return;
        }

        if (UUID_RECOIL_TELEMETRY.equals(characteristic.getUuid())) {
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
            if (descriptor == null) {
                Log.w(TAG, "Telemetry characteristic has no client configuration descriptor");
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
