package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBluetoothGatt;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Runs the real BLE service callbacks and deadlines without a radio or activity. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = BluetoothRecoveryTest.ControlledGatt.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BluetoothRecoveryTest {
    @Test public void missingConnectCallbackExpiresForEitherGunWithoutRepeatedConnectExtendingIt() throws Exception {
        for (int slot : new int[] {0, 1}) try (Fixture fixture = new Fixture(slot)) {
            assertTrue(fixture.service.monitorsConnectionHealth());
            advance(9_000);
            assertTrue(fixture.service.connect(Fixture.ADDRESS));
            assertSame(fixture.gatt, get(fixture.service, "mBluetoothGatt"));
            advance(999);assertFalse(fixture.shadow.closed);
            advance(1);fixture.assertRetired();
            assertEquals(slot, fixture.latest(BluetoothLeService.ACTION_GATT_DISCONNECTED)
                    .getIntExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, -1));
        }
    }

    @Test public void missingDiscoveryCallbackExpiresAfterSuccessfulRadioConnection() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.connected();
            assertTrue(fixture.service.isConnected());
            assertEquals(1, fixture.shadow.discoveries);
            advance(4_999);assertFalse(fixture.shadow.closed);
            advance(1);fixture.assertRetired();
        }
    }

    @Test public void failedConnectionOrDiscoveryCannotRemainConnected() throws Exception {
        for (int failure : new int[] {0, 1, 2}) try (Fixture fixture = new Fixture(0)) {
            if (failure == 0)
                fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_FAILURE,
                        BluetoothProfile.STATE_CONNECTED);
            else {
                fixture.shadow.discoveryAccepted = failure != 1;
                fixture.connected();
                if (failure == 2) fixture.callback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_FAILURE);
            }
            fixture.assertRetired();
        }
    }

    @Test public void missingGunServicesAndMissingNotificationSetupAreBounded() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.connected();fixture.shadow.services.clear();
            fixture.callback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS);
            fixture.assertRetired();
        }
        try (Fixture fixture = new Fixture(0)) {
            fixture.discovered();
            assertNotNull(fixture.latest(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED));
            advance(5_000);fixture.assertRetired();
        }
    }

    @Test public void failedTelemetryNotificationEnableRetiresOnlyTheAffectedHandle() throws Exception {
        for (int failure : new int[] {0, 1, 2, 3}) try (Fixture fixture = new Fixture(1)) {
            fixture.discovered();
            if (failure == 0) fixture.shadow.notificationsAccepted = false;
            if (failure == 1) fixture.telemetry.getDescriptors().clear();
            if (failure == 2) fixture.shadow.descriptorAccepted = false;
            fixture.service.setCharacteristicNotification(fixture.telemetry, true);
            if (failure == 3)
                fixture.callback.onDescriptorWrite(fixture.gatt, fixture.descriptor, BluetoothGatt.GATT_FAILURE);
            fixture.assertRetired();
        }
    }

    @Test public void acknowledgedNotificationsStillNeedTheirFirstTelemetryPacket() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.enableTelemetry();advance(5_000);fixture.assertRetired();
        }
    }

    @Test public void serviceCallbackTelemetryKeepsBackgroundConnectionAliveButShortPacketsDoNot() throws Exception {
        for (int slot : new int[] {0, 1}) try (Fixture fixture = new Fixture(slot)) {
            fixture.enableTelemetry();
            for (int packet = 0; packet < 4; packet++) {
                advance(4_000);fixture.telemetry(20);
                assertTrue(fixture.service.isConnected());
            }
            advance(4_000);fixture.telemetry(19);
            advance(999);assertTrue(fixture.service.isConnected());
            advance(1);fixture.assertRetired();
        }
    }

    @Test public void oldTimeoutAndOldCallbacksCannotRetireAReplacementOrPublishTelemetry() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.enableTelemetry();
            Runnable stale = (Runnable) get(fixture.service, "mConnectionHealthTimeout");
            fixture.service.close();
            assertTrue(fixture.service.connect(Fixture.ADDRESS));
            BluetoothGatt replacement = (BluetoothGatt) get(fixture.service, "mBluetoothGatt");
            assertNotSame(fixture.gatt, replacement);
            int events = fixture.service.events.size();
            stale.run();
            fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS,
                    BluetoothProfile.STATE_DISCONNECTED);
            fixture.callback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS);
            fixture.telemetry(20);
            assertSame(replacement, get(fixture.service, "mBluetoothGatt"));
            assertFalse(((ControlledGatt) Shadow.extract(replacement)).closed);
            assertEquals(events, fixture.service.events.size());
            assertTrue(fixture.service.monitorsConnectionHealth());
        }
    }

    @Test public void disconnectedHandleCannotReviveAndTheNextConnectionGetsAFreshIdentity() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.enableTelemetry();
            fixture.service.writeCharacteristic(fixture.command, new byte[] {16, 0, 2});
            fixture.service.writeCharacteristic(fixture.command, new byte[] {32, 0, 4});
            int events = fixture.service.events.size();
            fixture.callback.onConnectionStateChange(fixture.gatt, BluetoothGatt.GATT_SUCCESS,
                    BluetoothProfile.STATE_DISCONNECTED);
            fixture.assertRetired();
            assertEquals("Normal disconnect invented write failure events", events + 1, fixture.service.events.size());
            Intent disconnected = fixture.latest(BluetoothLeService.ACTION_GATT_DISCONNECTED);
            fixture.connected();fixture.callback.onServicesDiscovered(fixture.gatt, BluetoothGatt.GATT_SUCCESS);
            fixture.telemetry(20);
            assertNull(get(fixture.service, "mBluetoothGatt"));
            assertEquals(events + 1, fixture.service.events.size());
            assertTrue(fixture.service.connect(Fixture.ADDRESS));
            assertNotSame(fixture.gatt, get(fixture.service, "mBluetoothGatt"));
            assertNotEquals(disconnected.getLongExtra(BluetoothLeService.EXTRA_CONNECTION_ID, -1),
                    (long) get(fixture.service, "mConnectionId"));
            assertFalse(fixture.service.isCurrentConnectionEvent(disconnected));
        }
    }

    @Test public void genericDescriptorFailureStillAdvancesTheCommandQueue() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.discovered();
            fixture.descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            fixture.service.writeDescriptor(fixture.descriptor);
            fixture.service.writeCharacteristic(fixture.command, new byte[] {16, 0, 4});
            fixture.callback.onDescriptorWrite(fixture.gatt, fixture.descriptor, BluetoothGatt.GATT_FAILURE);
            fixture.callback.onCharacteristicWrite(fixture.gatt, fixture.command, BluetoothGatt.GATT_SUCCESS);
            assertTrue(fixture.service.isConnected());assertFalse(fixture.shadow.closed);
            assertNotNull(fixture.latest(BluetoothLeService.DESCRIPTOR_WRITE_FINISHED));
            assertNotNull(fixture.service.getCompletedWriteResult(fixture.command, new byte[] {16, 0, 4}));
        }
    }

    @Test public void connectionIdsFilterCachedAcksAndDelayedEventsAcrossServiceInstances() throws Exception {
        try (Fixture first = new Fixture(0);Fixture other = new Fixture(0)) {
            first.discovered();other.discovered();
            first.service.writeCharacteristic(first.command, new byte[] {16, 0, 4});
            first.callback.onCharacteristicWrite(first.gatt, first.command, BluetoothGatt.GATT_SUCCESS);
            Intent ack = first.service.getCompletedWriteResult(first.command, new byte[] {16, 0, 4});
            assertNotNull(ack);
            long identity = ack.getLongExtra(BluetoothLeService.EXTRA_CONNECTION_ID, -1);
            assertTrue(identity > 0);
            assertTrue(first.service.isCurrentConnectionEvent(ack));
            assertFalse(other.service.isCurrentConnectionEvent(ack));
            assertNotEquals(identity, other.latest(BluetoothLeService.ACTION_GATT_CONNECTED)
                    .getLongExtra(BluetoothLeService.EXTRA_CONNECTION_ID, -1));
            first.service.close();
            assertFalse(first.service.isCurrentConnectionEvent(ack));
            Intent failed = new Intent(ack).putExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_FAILURE);
            assertTrue(first.service.isCurrentConnectionEvent(failed));
            Intent disconnect = new Intent(BluetoothLeService.ACTION_GATT_DISCONNECTED)
                    .putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, identity);
            assertTrue(first.service.isCurrentConnectionEvent(disconnect));
            assertTrue(first.service.connect(Fixture.ADDRESS));
            assertFalse(first.service.isCurrentConnectionEvent(failed));
            assertFalse(first.service.isCurrentConnectionEvent(disconnect));
            assertTrue(first.service.isCurrentConnectionEvent(new Intent(BluetoothLeService.ACTION_GATT_CONNECTED)));
        }
    }

    @Test public void setupFailureFailsPendingWritesWithTheirRetiredConnectionIdentity() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.discovered();
            fixture.service.writeCharacteristic(fixture.command, new byte[] {16, 0, 2});
            fixture.service.writeCharacteristic(fixture.command, new byte[] {32, 0, 4});
            fixture.shadow.notificationsAccepted = false;
            fixture.service.setCharacteristicNotification(fixture.telemetry, true);
            fixture.assertRetired();
            int failures = 0;
            for (Intent event : fixture.service.events) {
                assertTrue(event.hasExtra(BluetoothLeService.EXTRA_CONNECTION_ID));
                if (!BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED.equals(event.getAction())) continue;
                failures++;
                assertEquals(BluetoothGatt.GATT_FAILURE, event.getIntExtra(BluetoothLeService.EXTRA_STATUS, -1));
                assertTrue(fixture.service.isCurrentConnectionEvent(event));
            }
            assertEquals(2, failures);
        }
    }

    @Test public void explicitCloseAndNotificationDisableCancelHealthRecovery() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.enableTelemetry();fixture.service.setCharacteristicNotification(fixture.telemetry, false);
            fixture.callback.onDescriptorWrite(fixture.gatt, fixture.descriptor, BluetoothGatt.GATT_SUCCESS);
            assertFalse(fixture.service.monitorsConnectionHealth());
            advance(10_000);assertTrue(fixture.service.isConnected());
            fixture.service.close();int events = fixture.service.events.size();
            advance(10_000);assertEquals(events, fixture.service.events.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        static final String ADDRESS = "00:11:22:33:44:55";
        final RecordingService service;
        final BluetoothGatt gatt;
        final BluetoothGattCallback callback;
        final ControlledGatt shadow;
        final BluetoothGattCharacteristic command = characteristic(GattAttributes.RECOIL_COMMAND_UUID);
        final BluetoothGattCharacteristic telemetry = characteristic(GattAttributes.RECOIL_TELEMETRY_UUID);
        final BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                UUID.fromString(GattAttributes.CLIENT_CHARACTERISTIC_CONFIG), BluetoothGattDescriptor.PERMISSION_WRITE);
        Fixture(int slot) throws Exception {
            service = new RecordingService(slot);
            set(service, "mBluetoothAdapter", BluetoothAdapter.getDefaultAdapter());
            assertTrue(service.connect(ADDRESS));
            gatt = (BluetoothGatt) get(service, "mBluetoothGatt");
            callback = (BluetoothGattCallback) get(service, "mGattCallback");
            shadow = Shadow.extract(gatt);
            BluetoothGattService recoil = new BluetoothGattService(UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE),
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
            recoil.addCharacteristic(command);recoil.addCharacteristic(telemetry);telemetry.addDescriptor(descriptor);
            shadow.services.add(recoil);
        }
        void connected() { callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED); }
        void discovered() { connected();callback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS); }
        void enableTelemetry() {
            discovered();service.setCharacteristicNotification(telemetry, true);
            callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS);
        }
        void telemetry(int length) { telemetry.setValue(new byte[length]);callback.onCharacteristicChanged(gatt, telemetry); }
        Intent latest(String action) {
            for (int i = service.events.size() - 1; i >= 0; i--)
                if (action.equals(service.events.get(i).getAction())) return service.events.get(i);
            return null;
        }
        void assertRetired() throws Exception {
            assertTrue(shadow.closed);assertFalse(service.isConnected());
            assertNull(get(service, "mBluetoothGatt"));assertFalse(service.monitorsConnectionHealth());
            Intent event = latest(BluetoothLeService.ACTION_GATT_DISCONNECTED);assertNotNull(event);
            assertTrue(service.isCurrentConnectionEvent(event));
        }
        @Override public void close() { service.onDestroy(); }
    }

    private static final class RecordingService extends BluetoothLeService {
        final List<Intent> events = new ArrayList<>();
        final int slot;
        RecordingService(int slot) { this.slot = slot; }
        @Override protected int weaponSlot() { return slot; }
        @Override public void sendBroadcast(Intent event) { events.add(new Intent(event)); }
    }

    @Implements(BluetoothGatt.class)
    public static class ControlledGatt extends ShadowBluetoothGatt {
        final List<BluetoothGattService> services = new ArrayList<>();
        boolean discoveryAccepted = true, notificationsAccepted = true, descriptorAccepted = true, closed;
        int discoveries;
        @Implementation protected boolean discoverServices() { discoveries++;return discoveryAccepted; }
        @Implementation protected List<BluetoothGattService> getServices() { return new ArrayList<>(services); }
        @Implementation protected boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
            return notificationsAccepted;
        }
        @Implementation protected boolean writeDescriptor(BluetoothGattDescriptor descriptor) { return descriptorAccepted; }
        @Implementation protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) { return true; }
        @Implementation protected void close() { closed = true; }
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), BluetoothGattCharacteristic.PROPERTY_WRITE
                | BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_WRITE);
    }
    private static void advance(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS); }
    private static Object get(Object target, String name) throws Exception {
        Field field = BluetoothLeService.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = BluetoothLeService.class.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
}
