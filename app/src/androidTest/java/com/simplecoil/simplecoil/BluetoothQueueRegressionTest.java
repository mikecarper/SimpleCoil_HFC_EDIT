package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Uses the Android 5.x GATT implementation with an isolated, non-radio transport. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 21, maxSdkVersion = 22)
public class BluetoothQueueRegressionTest {
    private RecordingService service;
    private BluetoothGatt gatt;
    private BluetoothGattCallback callback;
    private BluetoothGattCharacteristic command;
    private BluetoothGattCharacteristic telemetry;
    private BluetoothGattDescriptor descriptor;
    private final List<String> operations = new ArrayList<>();
    private final List<byte[]> descriptorValues = new ArrayList<>();
    private boolean denyNotifications;
    private boolean completeWritesSynchronously;

    @Before
    public void setUp() throws Exception {
        service = new RecordingService();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull(adapter);
        BluetoothDevice device = adapter.getRemoteDevice("00:11:22:33:44:55");
        Class<?> transportType = Class.forName("android.bluetooth.IBluetoothGatt");
        Object transport = Proxy.newProxyInstance(transportType.getClassLoader(),
                new Class<?>[]{transportType}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("registerForNotification")) {
                        if (denyNotifications) throw new SecurityException("Test permission revocation");
                    } else if (name.equals("writeCharacteristic") || name.equals("readCharacteristic")
                            || name.equals("writeDescriptor")) {
                        operations.add(name);
                        if (name.equals("writeDescriptor"))
                            descriptorValues.add(((byte[]) args[args.length - 1]).clone());
                        if (name.equals("writeCharacteristic") && completeWritesSynchronously) {
                            // A framework callback can race the return from writeCharacteristic().
                            // Model that reentrant completion without using a radio.
                            field(BluetoothGatt.class, "mDeviceBusy").set(gatt, Boolean.FALSE);
                            callback.onCharacteristicWrite(gatt, command, BluetoothGatt.GATT_SUCCESS);
                        }
                    } else if (!name.equals("unregisterClient")) {
                        throw new AssertionError("Unexpected Bluetooth transport call: " + name);
                    }
                    return null;
                });
        Constructor<BluetoothGatt> constructor = BluetoothGatt.class.getDeclaredConstructor(
                Context.class, transportType, BluetoothDevice.class, int.class);
        constructor.setAccessible(true);
        gatt = constructor.newInstance(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                transport, device, 0);
        field(BluetoothGatt.class, "mClientIf").setInt(gatt, 1);
        serviceField("mBluetoothAdapter").set(service, adapter);
        serviceField("mBluetoothGatt").set(service, gatt);
        callback = (BluetoothGattCallback) serviceField("mGattCallback").get(service);

        BluetoothGattService remoteService = new BluetoothGattService(
                UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY);
        field(BluetoothGattService.class, "mDevice").set(remoteService, device);
        command = characteristic(GattAttributes.RECOIL_COMMAND_UUID);
        telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        telemetry.setValue(new byte[20]);
        remoteService.addCharacteristic(command);
        remoteService.addCharacteristic(telemetry);
        descriptor = new BluetoothGattDescriptor(UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG),
                BluetoothGattDescriptor.PERMISSION_WRITE);
        descriptor.setValue(new byte[]{1, 0});
        telemetry.addDescriptor(descriptor);
    }

    @After
    public void tearDown() {
        if (service != null) service.close();
    }

    @Test
    public void unsolicitedReadCannotAdvancePendingWrites() throws Exception {
        queueTwoCommands();
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertSingleWriteStillPending();
        finishBothCommands();
    }

    @Test
    public void unsolicitedDescriptorCallbackCannotAdvancePendingWrites() throws Exception {
        queueTwoCommands();
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        assertSingleWriteStillPending();
        finishBothCommands();
    }

    @Test
    public void lateReadCompletionCannotFinishFollowingWrite() throws Exception {
        service.readCharacteristic(telemetry);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertEquals(Arrays.asList("readCharacteristic", "writeCharacteristic"), operations);
        assertEquals(1, service.broadcasts.size());
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertEquals(1, service.broadcasts.size());
        assertFalse(isAvailable());
        completeWrite();
        assertWriteResult(1, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void lateDescriptorCompletionCannotFinishFollowingWrite() throws Exception {
        service.writeDescriptor(descriptor);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        assertEquals(Arrays.asList("writeDescriptor", "writeCharacteristic"), operations);
        assertEquals(1, service.broadcasts.size());
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        assertEquals(1, service.broadcasts.size());
        assertFalse(isAvailable());
        completeWrite();
        assertWriteResult(1, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void readCallbackForDifferentObjectWithSameUuidCannotCompleteRead() throws Exception {
        service.readCharacteristic(telemetry);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        BluetoothGattCharacteristic other = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        other.setValue(new byte[20]);
        completeRead(other, BluetoothGatt.GATT_SUCCESS);
        assertEquals(Arrays.asList("readCharacteristic"), operations);
        assertTrue(service.broadcasts.isEmpty());
        assertFalse(isAvailable());
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        completeWrite();
        assertWriteResult(1, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void descriptorCallbackForDifferentObjectWithSameUuidCannotCompleteWrite() throws Exception {
        service.writeDescriptor(descriptor);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        completeDescriptor(new BluetoothGattDescriptor(descriptor.getUuid(), 0), BluetoothGatt.GATT_SUCCESS);
        assertEquals(Arrays.asList("writeDescriptor"), operations);
        assertTrue(service.broadcasts.isEmpty());
        assertFalse(isAvailable());
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        completeWrite();
        assertWriteResult(1, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void failedReadStillStartsNextCommand() throws Exception {
        service.readCharacteristic(telemetry);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        completeRead(telemetry, BluetoothGatt.GATT_FAILURE);
        assertEquals(Arrays.asList("readCharacteristic", "writeCharacteristic"), operations);
        assertTrue(service.broadcasts.isEmpty());
        completeWrite();
        assertWriteResult(0, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void failedDescriptorWriteStillStartsNextCommand() throws Exception {
        service.writeDescriptor(descriptor);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        completeDescriptor(descriptor, BluetoothGatt.GATT_FAILURE);
        assertEquals(Arrays.asList("writeDescriptor", "writeCharacteristic"), operations);
        assertEquals(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED, service.broadcasts.get(0).getAction());
        completeWrite();
        assertWriteResult(1, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void synchronousWriteCompletionDoesNotLeaveTheNextCommandQueued() throws Exception {
        completeWritesSynchronously = true;
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        service.writeCharacteristic(command, new byte[]{32, 0, 4});
        assertEquals(Arrays.asList("writeCharacteristic", "writeCharacteristic"), operations);
        assertWriteResult(0, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertWriteResult(1, new byte[]{32, 0, 4}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void queuedDescriptorAndReadRunAfterCommandWithOriginalDescriptorValue() throws Exception {
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        service.readCharacteristic(telemetry);
        service.writeDescriptor(descriptor);
        descriptor.setValue(new byte[]{0, 0});
        completeWrite();
        assertEquals(Arrays.asList("writeCharacteristic", "writeDescriptor"), operations);
        assertArrayEquals(new byte[]{1, 0}, descriptorValues.get(0));
        assertFalse(isAvailable());
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        assertEquals(Arrays.asList("writeCharacteristic", "writeDescriptor", "readCharacteristic"), operations);
        assertFalse(isAvailable());
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
        assertEquals(3, service.broadcasts.size());
        assertEquals(BluetoothLeService.TELEMETRY_DATA_AVAILABLE, service.broadcasts.get(2).getAction());
    }

    @Test
    public void rejectedReadDoesNotOwnALaterCallback() throws Exception {
        BluetoothGattCharacteristic unreadable = new BluetoothGattCharacteristic(telemetry.getUuid(), 0, 0);
        unreadable.setValue(new byte[20]);
        service.readCharacteristic(unreadable);
        assertTrue(isAvailable());
        queueTwoCommands();
        completeRead(unreadable, BluetoothGatt.GATT_SUCCESS);
        assertSingleWriteStillPending();
        finishBothCommands();
    }

    @Test
    public void rejectedDescriptorDoesNotOwnALaterCallback() throws Exception {
        BluetoothGattDescriptor orphan = new BluetoothGattDescriptor(descriptor.getUuid(), 0);
        orphan.setValue(new byte[]{1, 0});
        service.writeDescriptor(orphan);
        assertTrue(isAvailable());
        queueTwoCommands();
        completeDescriptor(orphan, BluetoothGatt.GATT_SUCCESS);
        assertSingleWriteStillPending();
        finishBothCommands();
    }

    @Test
    public void rejectedQueuedOperationsDoNotBlockTheNextRead() throws Exception {
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        service.writeCharacteristic(new BluetoothGattCharacteristic(command.getUuid(), 0, 0), new byte[]{32, 0, 4});
        BluetoothGattDescriptor orphan = new BluetoothGattDescriptor(descriptor.getUuid(), 0);
        orphan.setValue(new byte[]{1, 0});
        service.writeDescriptor(orphan);
        BluetoothGattCharacteristic unreadable = new BluetoothGattCharacteristic(telemetry.getUuid(), 0, 0);
        service.readCharacteristic(unreadable);
        service.readCharacteristic(telemetry);
        completeWrite();
        assertEquals(Arrays.asList("writeCharacteristic", "readCharacteristic"), operations);
        assertWriteResult(0, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertWriteResult(1, new byte[]{32, 0, 4}, BluetoothGatt.GATT_FAILURE);
        completeDescriptor(orphan, BluetoothGatt.GATT_SUCCESS);
        completeRead(unreadable, BluetoothGatt.GATT_FAILURE);
        assertEquals(2, service.broadcasts.size());
        assertFalse(isAvailable());
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertEquals(3, service.broadcasts.size());
        assertTrue(isAvailable());
    }

    @Test
    public void notificationPermissionFailureReportsActiveAndQueuedCommandFailures() throws Exception {
        byte[] first = new byte[]{16, 0, 2};
        service.writeCharacteristic(command, first);
        service.writeCharacteristic(command, new byte[]{32, 0, 4});
        first[2] = 99;
        denyNotifications = true;
        service.setCharacteristicNotification(telemetry, true);
        assertEquals(2, service.broadcasts.size());
        assertWriteResult(0, new byte[]{16, 0, 2}, BluetoothGatt.GATT_FAILURE);
        assertWriteResult(1, new byte[]{32, 0, 4}, BluetoothGatt.GATT_FAILURE);
        assertTrue(isAvailable());
        completeWrite();
        assertEquals(2, service.broadcasts.size());
        assertEquals(Arrays.asList("writeCharacteristic"), operations);
    }

    @Test
    public void telemetryNotificationCannotCompleteActiveRead() throws Exception {
        service.readCharacteristic(telemetry);
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        callback.onCharacteristicChanged(gatt, telemetry);
        assertEquals(Arrays.asList("readCharacteristic"), operations);
        assertEquals(1, service.broadcasts.size());
        assertFalse(isAvailable());
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        completeWrite();
        assertWriteResult(2, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    @Test
    public void closeClearsActiveReadAndRejectsItsLateCallback() throws Exception {
        service.readCharacteristic(telemetry);
        service.close();
        completeRead(telemetry, BluetoothGatt.GATT_SUCCESS);
        assertTrue(service.broadcasts.isEmpty());
        assertNull(serviceField("mActiveCharacteristicRead").get(service));
        assertTrue(isAvailable());
    }

    @Test
    public void closeClearsActiveDescriptorAndRejectsItsLateCallback() throws Exception {
        service.writeDescriptor(descriptor);
        service.close();
        completeDescriptor(descriptor, BluetoothGatt.GATT_SUCCESS);
        assertTrue(service.broadcasts.isEmpty());
        assertNull(serviceField("mActiveDescriptorWrite").get(service));
        assertTrue(isAvailable());
    }

    private void queueTwoCommands() {
        service.writeCharacteristic(command, new byte[]{16, 0, 2});
        service.writeCharacteristic(command, new byte[]{32, 0, 4});
        assertEquals(Arrays.asList("writeCharacteristic"), operations);
    }

    private void assertSingleWriteStillPending() throws Exception {
        assertEquals(Arrays.asList("writeCharacteristic"), operations);
        assertTrue(service.broadcasts.isEmpty());
        assertFalse(isAvailable());
    }

    private void finishBothCommands() throws Exception {
        completeWrite();
        assertEquals(Arrays.asList("writeCharacteristic", "writeCharacteristic"), operations);
        assertFalse(isAvailable());
        completeWrite();
        assertEquals(2, service.broadcasts.size());
        assertWriteResult(0, new byte[]{16, 0, 2}, BluetoothGatt.GATT_SUCCESS);
        assertWriteResult(1, new byte[]{32, 0, 4}, BluetoothGatt.GATT_SUCCESS);
        assertTrue(isAvailable());
    }

    private void assertWriteResult(int index, byte[] value, int status) {
        Intent result = service.broadcasts.get(index);
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, result.getAction());
        assertEquals(command.getUuid().toString(), result.getStringExtra(BluetoothLeService.EXTRA_UUID));
        assertEquals(status, result.getIntExtra(BluetoothLeService.EXTRA_STATUS, -1));
        assertArrayEquals(value, result.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
    }

    private void completeWrite() throws Exception {
        releaseTransport();
        callback.onCharacteristicWrite(gatt, command, BluetoothGatt.GATT_SUCCESS);
    }

    private void completeRead(BluetoothGattCharacteristic characteristic, int status) throws Exception {
        releaseTransport();
        callback.onCharacteristicRead(gatt, characteristic, status);
    }

    private void completeDescriptor(BluetoothGattDescriptor completed, int status) throws Exception {
        releaseTransport();
        callback.onDescriptorWrite(gatt, completed, status);
    }

    private void releaseTransport() throws Exception {
        // Android 5.x releases its busy flag before delivering these callbacks.
        field(BluetoothGatt.class, "mDeviceBusy").set(gatt, Boolean.FALSE);
    }

    private boolean isAvailable() throws Exception {
        return serviceField("mActionAvailable").getBoolean(service);
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid),
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
    }

    private static final class RecordingService extends BluetoothLeService {
        final List<Intent> broadcasts = new ArrayList<>();

        @Override
        public void sendBroadcast(Intent intent) {
            broadcasts.add(intent);
        }
    }

    private static Field serviceField(String name) throws Exception {
        return field(BluetoothLeService.class, name);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
