package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Service regressions that never open a Bluetooth connection. */
@RunWith(AndroidJUnit4.class)
public class BluetoothRegressionTest {
    @Test
    public void queuedWritesKeepTheirOwnPayloadAndWriteType() {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID), 0, 0);
        byte[] first = new byte[]{16, 0, 2};
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        BluetoothLeService.CharacteristicWrite firstWrite =
                new BluetoothLeService.CharacteristicWrite(characteristic, first);
        first[2] = 99;
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        BluetoothLeService.CharacteristicWrite secondWrite =
                new BluetoothLeService.CharacteristicWrite(characteristic, new byte[]{32, 0, 4});
        firstWrite.prepare();
        assertArrayEquals(new byte[]{16, 0, 2}, characteristic.getValue());
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, characteristic.getWriteType());
        characteristic.getValue()[2] = 88;
        secondWrite.prepare();
        assertArrayEquals(new byte[]{32, 0, 4}, characteristic.getValue());
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, characteristic.getWriteType());
        firstWrite.prepare();
        assertArrayEquals(new byte[]{16, 0, 2}, characteristic.getValue());
    }

    @Test
    public void queuedDescriptorKeepsItsOriginalNotificationSetting() {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG), 0);
        descriptor.setValue(new byte[]{1, 0});
        BluetoothLeService.DescriptorWrite write = new BluetoothLeService.DescriptorWrite(descriptor);
        descriptor.getValue()[0] = 0;
        write.prepare();
        assertArrayEquals(new byte[]{1, 0}, descriptor.getValue());
        descriptor.getValue()[0] = 2;
        write.prepare();
        assertArrayEquals(new byte[]{1, 0}, descriptor.getValue());
    }

    @Test
    public void disconnectedWriteReportsFailureWithTheOriginalCommand() {
        RecordingService service = new RecordingService();
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID), 0, 0);
        byte[] value = new byte[]{16, 0, 2};
        service.writeCharacteristic(characteristic, value);
        value[2] = 4;
        assertEquals(1, service.broadcasts.size());
        Intent result = service.broadcasts.get(0);
        assertEquals(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED, result.getAction());
        assertEquals(GattAttributes.RECOIL_COMMAND_UUID, result.getStringExtra(BluetoothLeService.EXTRA_UUID));
        assertEquals(BluetoothGatt.GATT_FAILURE, result.getIntExtra(BluetoothLeService.EXTRA_STATUS, -1));
        assertArrayEquals(new byte[]{16, 0, 2}, result.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void staleConnectionCallbackCannotClearPendingWrites() throws Exception {
        RecordingService service = new RecordingService();
        Field field = BluetoothLeService.class.getDeclaredField("mCharacteristicWriteQueue");
        field.setAccessible(true);
        Queue<BluetoothLeService.CharacteristicWrite> queue =
                (Queue<BluetoothLeService.CharacteristicWrite>) field.get(service);
        queue.add(new BluetoothLeService.CharacteristicWrite(new BluetoothGattCharacteristic(
                UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID), 0, 0), new byte[]{16, 0, 2}));
        Method advance = BluetoothLeService.class.getDeclaredMethod("startNextGattOperation", BluetoothGatt.class);
        advance.setAccessible(true);
        advance.invoke(service, new Object[]{null});
        assertEquals(1, queue.size());
    }

    @Test
    public void telemetryBroadcastOwnsAnImmutablePacketSnapshot() throws Exception {
        RecordingService service = new RecordingService();
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID), 0, 0);
        byte[] data = new byte[20];
        data[8] = 1;
        characteristic.setValue(data);
        Method broadcast = BluetoothLeService.class.getDeclaredMethod(
                "broadcastUpdate", BluetoothGattCharacteristic.class);
        broadcast.setAccessible(true);
        broadcast.invoke(service, characteristic);
        data[8] = 2;
        broadcast.invoke(service, characteristic);
        assertEquals(2, service.broadcasts.size());
        byte[] expectedFirst = data.clone();
        expectedFirst[8] = 1;
        assertArrayEquals(expectedFirst, service.broadcasts.get(0).getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
        assertArrayEquals(data, service.broadcasts.get(1).getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
    }

    private static final class RecordingService extends BluetoothLeService {
        final List<Intent> broadcasts = new ArrayList<>();

        @Override
        public void sendBroadcast(Intent intent) {
            broadcasts.add(intent);
        }
    }
}
