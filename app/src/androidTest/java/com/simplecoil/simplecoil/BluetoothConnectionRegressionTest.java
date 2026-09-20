package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises connection handoffs using Android 5.x GATT objects and no radio traffic. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 21, maxSdkVersion = 22)
public class BluetoothConnectionRegressionTest {
    private RecordingService service;
    private BluetoothGattCallback callback;
    private FakeGatt original;
    private FakeGatt replacement;
    private BluetoothAdapter savedDefaultAdapter;
    private Field defaultAdapterField;
    private final List<FakeGatt> connections = new ArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        service = new RecordingService();
        savedDefaultAdapter = BluetoothAdapter.getDefaultAdapter();
        assertNotNull(savedDefaultAdapter);
        serviceField("mBluetoothAdapter").set(service, savedDefaultAdapter);
        callback = (BluetoothGattCallback) serviceField("mGattCallback").get(service);
        original = new FakeGatt("00:11:22:33:44:55", 1);
        replacement = new FakeGatt("00:11:22:33:44:66", 2);
        installFakeAdapter();
        publish(original, 2);
    }

    @After
    public void tearDown() throws Exception {
        try {
            for (Thread worker : workers) {
                worker.interrupt();
                worker.join(2000);
            }
            for (FakeGatt connection : connections) connection.duringClose = null;
            if (service != null) service.close();
            for (FakeGatt connection : connections) connection.gatt.close();
        } finally {
            if (defaultAdapterField != null) defaultAdapterField.set(null, savedDefaultAdapter);
        }
        for (Thread worker : workers) assertFalse("Bluetooth worker survived cleanup", worker.isAlive());
        assertTrue("Bluetooth worker failed: " + failures, failures.isEmpty());
    }

    private void installFakeAdapter() throws Exception {
        // LG's Android 5.1 GATT implementation checks the process-default adapter
        // before close/disconnect. Satisfy that guard without turning on the radio
        // or changing the real adapter's service. Restore this reference after each test.
        Class<?> adapterServiceType = Class.forName("android.bluetooth.IBluetooth");
        Object adapterService = Proxy.newProxyInstance(adapterServiceType.getClassLoader(),
                new Class<?>[]{adapterServiceType}, (proxy, method, args) -> {
                    if (method.getName().equals("getState")) return BluetoothAdapter.STATE_ON;
                    if (method.getName().equals("getBleQmState"))
                        return field(BluetoothAdapter.class, "STATE_BLE_QM_ON").getInt(null);
                    throw new AssertionError("Unexpected adapter operation: " + method.getName());
                });
        Class<?> managerType = Class.forName("android.bluetooth.IBluetoothManager");
        Object manager = Proxy.newProxyInstance(managerType.getClassLoader(), new Class<?>[]{managerType},
                (proxy, method, args) -> {
                    if (method.getName().equals("registerAdapter")) return adapterService;
                    if (method.getName().equals("unregisterAdapter")) return null;
                    if (method.getName().equals("getBleQmState"))
                        return field(BluetoothAdapter.class, "STATE_BLE_QM_ON").getInt(null);
                    throw new AssertionError("Unexpected adapter-manager operation: " + method.getName());
                });
        Constructor<BluetoothAdapter> constructor = BluetoothAdapter.class.getDeclaredConstructor(managerType);
        constructor.setAccessible(true);
        BluetoothAdapter fakeAdapter = constructor.newInstance(manager);
        defaultAdapterField = field(BluetoothAdapter.class, "sAdapter");
        defaultAdapterField.set(null, fakeAdapter);
    }

    @Test
    public void earlyConnectedCallbackWaitsForTheNewHandleToBePublished() throws Exception {
        service.close();
        // connect() holds this monitor while Android creates the new GATT object.
        runAcrossTransition(() -> callback.onConnectionStateChange(replacement.gatt,
                BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED), () -> publish(replacement, 1));
        assertEquals(Arrays.asList(BluetoothLeService.ACTION_GATT_CONNECTED), actions());
        assertEquals(Arrays.asList("discoverServices"), replacement.operations);
        assertEquals(2, connectionState());
    }

    @Test
    public void earlyDisconnectCallbackIsNotLostBeforeHandlePublication() throws Exception {
        service.close();
        runAcrossTransition(() -> callback.onConnectionStateChange(replacement.gatt,
                BluetoothGatt.GATT_FAILURE, BluetoothProfile.STATE_DISCONNECTED), () -> publish(replacement, 1));
        assertEquals(Arrays.asList(BluetoothLeService.ACTION_GATT_DISCONNECTED), actions());
        assertEquals(0, connectionState());
    }

    @Test
    public void oldDisconnectWaitingDuringReplacementCannotEraseNewCommands() throws Exception {
        assertReplacedCallbackIgnored(() -> callback.onConnectionStateChange(original.gatt,
                BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED));
    }

    @Test
    public void oldConnectedCallbackWaitingDuringReplacementCannotPublishAFalseConnection() throws Exception {
        assertReplacedCallbackIgnored(() -> callback.onConnectionStateChange(original.gatt,
                BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED));
    }

    @Test
    public void oldDiscoveryWaitingDuringReplacementCannotConfigureTheNewWeapon() throws Exception {
        assertReplacedCallbackIgnored(() -> callback.onServicesDiscovered(original.gatt, BluetoothGatt.GATT_SUCCESS));
    }

    @Test
    public void oldTelemetryWaitingDuringReplacementCannotReachTheGame() throws Exception {
        assertReplacedCallbackIgnored(() -> callback.onCharacteristicChanged(original.gatt, original.telemetry));
    }

    private void assertReplacedCallbackIgnored(Runnable event) throws Exception {
        runAcrossTransition(event, () -> {
            service.close();
            publish(replacement, 2);
            service.writeCharacteristic(replacement.command, new byte[]{16, 0, 2});
            service.writeCharacteristic(replacement.command, new byte[]{32, 0, 4});
        });
        assertTrue("The old connection published an event after replacement", service.events.isEmpty());
        assertEquals(2, connectionState());
        assertSame(replacement.gatt, serviceField("mBluetoothGatt").get(service));
        assertFalse(serviceField("mActionAvailable").getBoolean(service));
        assertNotNull(serviceField("mActiveCharacteristicWrite").get(service));
        assertEquals(1, ((Queue<?>) serviceField("mCharacteristicWriteQueue").get(service)).size());
        assertEquals(Arrays.asList("writeCharacteristic"), replacement.operations);
        completeCommand(replacement);
        completeCommand(replacement);
        assertEquals(Arrays.asList("writeCharacteristic", "writeCharacteristic"), replacement.operations);
        assertEquals(Arrays.asList(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED,
                BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED), actions());
        assertTrue(serviceField("mActionAvailable").getBoolean(service));
    }

    @Test
    public void disconnectQueuedBehindCloseDoesNotUseTheRetiredHandle() throws Exception {
        runAcrossTransition(service::disconnect, service::close);
        assertFalse(original.operations.contains("clientDisconnect"));
        assertClosed();
    }

    @Test
    public void serviceQueryQueuedBehindCloseDoesNotReturnRetiredServices() throws Exception {
        AtomicReference<List<BluetoothGattService>> services = new AtomicReference<>();
        runAcrossTransition(() -> services.set(service.getSupportedGattServices()), service::close);
        assertNull(services.get());
        assertClosed();
    }

    @Test
    public void frameworkCloseCannotPublishConnectionDiscoveryOrTelemetryCallbacks() throws Exception {
        original.duringClose = () -> {
            callback.onConnectionStateChange(original.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
            callback.onConnectionStateChange(original.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
            callback.onServicesDiscovered(original.gatt, BluetoothGatt.GATT_SUCCESS);
            callback.onCharacteristicChanged(original.gatt, original.telemetry);
        };
        service.close();
        assertTrue(service.events.isEmpty());
        assertEquals(Arrays.asList("unregisterClient"), original.operations);
        assertClosed();
    }

    @Test
    public void frameworkCloseCannotCompleteOrStartAnotherQueuedCommand() throws Exception {
        service.writeCharacteristic(original.command, new byte[]{16, 0, 2});
        service.writeCharacteristic(original.command, new byte[]{32, 0, 4});
        original.duringClose = () -> {
            try { completeCommand(original); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };
        service.close();
        assertTrue(service.events.isEmpty());
        assertEquals(Arrays.asList("writeCharacteristic", "unregisterClient"), original.operations);
        assertTrue(((Queue<?>) serviceField("mCharacteristicWriteQueue").get(service)).isEmpty());
        assertNull(serviceField("mActiveCharacteristicWrite").get(service));
        assertTrue(serviceField("mActionAvailable").getBoolean(service));
        assertClosed();
    }

    @Test
    public void normalConnectedCallbackStillStartsDiscovery() throws Exception {
        callback.onConnectionStateChange(original.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED);
        assertEquals(Arrays.asList(BluetoothLeService.ACTION_GATT_CONNECTED), actions());
        assertEquals(Arrays.asList("discoverServices"), original.operations);
        assertEquals(2, connectionState());
    }

    @Test
    public void normalDisconnectStillClearsPendingCommandsAndNotifiesTheGame() throws Exception {
        service.writeCharacteristic(original.command, new byte[]{16, 0, 2});
        service.writeCharacteristic(original.command, new byte[]{32, 0, 4});
        callback.onConnectionStateChange(original.gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED);
        assertEquals(Arrays.asList(BluetoothLeService.ACTION_GATT_DISCONNECTED), actions());
        assertEquals(0, connectionState());
        assertTrue(serviceField("mActionAvailable").getBoolean(service));
        assertNull(serviceField("mActiveCharacteristicWrite").get(service));
        assertTrue(((Queue<?>) serviceField("mCharacteristicWriteQueue").get(service)).isEmpty());
    }

    @Test
    public void currentDiscoveryAndTelemetryAreStillDelivered() {
        callback.onServicesDiscovered(original.gatt, BluetoothGatt.GATT_SUCCESS);
        callback.onCharacteristicChanged(original.gatt, original.telemetry);
        assertEquals(Arrays.asList(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED,
                BluetoothLeService.TELEMETRY_DATA_AVAILABLE), actions());
        assertArrayEquals(new byte[20], service.events.get(1).getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
    }

    @Test
    public void currentServiceQueryAndDisconnectStillReachTheActiveConnection() {
        assertEquals(1, service.getSupportedGattServices().size());
        service.disconnect();
        assertEquals(Arrays.asList("clientDisconnect"), original.operations);
    }

    private void runAcrossTransition(Runnable event, CheckedAction transition) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try { event.run(); }
            catch (Throwable failure) { failures.add(failure); }
        }, "Bluetooth handoff regression");
        workers.add(worker);
        synchronized (service) {
            worker.start();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (worker.isAlive() && worker.getState() != Thread.State.BLOCKED
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(1);
            assertTrue("Callback did not reach its synchronization point",
                    !worker.isAlive() || worker.getState() == Thread.State.BLOCKED);
            transition.run();
        }
        worker.join(2000);
        assertFalse("Callback did not finish after the handoff", worker.isAlive());
        assertTrue("Callback failed: " + failures, failures.isEmpty());
    }

    private void publish(FakeGatt connection, int state) throws Exception {
        serviceField("mBluetoothGatt").set(service, connection.gatt);
        serviceField("mBluetoothDeviceAddress").set(service, connection.address);
        serviceField("mConnectionState").setInt(service, state);
    }

    private void completeCommand(FakeGatt connection) throws Exception {
        field(BluetoothGatt.class, "mDeviceBusy").set(connection.gatt, Boolean.FALSE);
        callback.onCharacteristicWrite(connection.gatt, connection.command, BluetoothGatt.GATT_SUCCESS);
    }

    private void assertClosed() throws Exception {
        assertNull(serviceField("mBluetoothGatt").get(service));
        assertNull(serviceField("mBluetoothDeviceAddress").get(service));
        assertEquals(0, connectionState());
    }

    private int connectionState() throws Exception {
        return serviceField("mConnectionState").getInt(service);
    }

    private List<String> actions() {
        List<String> result = new ArrayList<>();
        for (Intent event : service.events) result.add(event.getAction());
        return result;
    }

    private final class FakeGatt {
        final String address;
        final BluetoothGatt gatt;
        final BluetoothGattCharacteristic command;
        final BluetoothGattCharacteristic telemetry;
        final List<String> operations = new CopyOnWriteArrayList<>();
        Runnable duringClose;

        @SuppressWarnings("unchecked")
        FakeGatt(String address, int clientID) throws Exception {
            this.address = address;
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
            Class<?> transportType = Class.forName("android.bluetooth.IBluetoothGatt");
            Object transport = Proxy.newProxyInstance(transportType.getClassLoader(), new Class<?>[]{transportType},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (!name.equals("discoverServices") && !name.equals("clientDisconnect")
                                && !name.equals("writeCharacteristic") && !name.equals("unregisterClient"))
                            throw new AssertionError("Unexpected Bluetooth transport call: " + name);
                        operations.add(name);
                        if (name.equals("unregisterClient") && duringClose != null) duringClose.run();
                        return null;
                    });
            Constructor<BluetoothGatt> constructor = BluetoothGatt.class.getDeclaredConstructor(
                    Context.class, transportType, BluetoothDevice.class, int.class);
            constructor.setAccessible(true);
            gatt = constructor.newInstance(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    transport, device, 0);
            field(BluetoothGatt.class, "mClientIf").setInt(gatt, clientID);
            BluetoothGattService remoteService = new BluetoothGattService(
                    UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY);
            field(BluetoothGattService.class, "mDevice").set(remoteService, device);
            command = new BluetoothGattCharacteristic(UUID.fromString(GattAttributes.RECOIL_COMMAND_UUID),
                    BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
            telemetry = new BluetoothGattCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID),
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
            telemetry.setValue(new byte[20]);
            remoteService.addCharacteristic(command);
            remoteService.addCharacteristic(telemetry);
            ((List<BluetoothGattService>) field(BluetoothGatt.class, "mServices").get(gatt)).add(remoteService);
            connections.add(this);
        }
    }

    private static final class RecordingService extends BluetoothLeService {
        final List<Intent> events = new CopyOnWriteArrayList<>();

        @Override public synchronized void sendBroadcast(Intent intent) {
            // Pause callback publication while the connection monitor is held, so
            // tests can replace the handle after an unsynchronized identity check.
            events.add(intent);
        }
    }

    private interface CheckedAction { void run() throws Exception; }

    private static Field serviceField(String name) throws Exception {
        return field(BluetoothLeService.class, name);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
