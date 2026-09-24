package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises real activity callbacks without starting a scan or connecting to a blaster. */
@RunWith(AndroidJUnit4.class)
public class BluetoothLifecycleRegressionTest {
    private static final String ADDRESS = "00:11:22:33:44:55";
    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity activity;
    private RecordingBluetoothService bluetooth;
    private SharedPreferences preferences;
    private String savedDeviceAddress;
    private boolean hadDeviceAddress;

    @Before
    public void setUp() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mUseGPS = false;
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            activity = current;
            preferences = current.getSharedPreferences(FullscreenActivity.PREF_NAME, 0);
            hadDeviceAddress = preferences.contains(FullscreenActivity.PREF_DEVICE_ADDRESS);
            savedDeviceAddress = preferences.getString(FullscreenActivity.PREF_DEVICE_ADDRESS, null);
            bluetooth = new RecordingBluetoothService();
            set("mBluetoothLeService", bluetooth);
            set("mBluetoothLeScanner", null);
            set("mBLEServiceConnection", null);
            set("mBLEServiceBound", false);
            set("mDeviceAddress", ADDRESS);
            set("mUseNetwork", false);
            set("mScanning", false);
            set("mConnected", true);
            set("mCommunicating", true);
            status().setText("unchanged");
        });
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.onActivity(current -> {
                // No test binding was registered with Android.
                set("mBLEServiceBound", false);
                invoke("stopBLEScan");
            });
            scenario.close();
        }
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadDeviceAddress) editor.putString(FullscreenActivity.PREF_DEVICE_ADDRESS, savedDeviceAddress);
            else editor.remove(FullscreenActivity.PREF_DEVICE_ADDRESS);
            editor.commit();
        }
    }

    @Test
    public void failureFromStoppedScanCannotClearConnectedWeapon() {
        scenario.onActivity(current -> {
            ScanCallback callback = beginFakeScan();
            invoke("stopBLEScan");
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            assertSame(bluetooth, get("mBluetoothLeService"));
            assertEquals("unchanged", status().getText().toString());
            assertTrue((boolean) get("mCommunicating"));
        });
    }

    @Test
    public void singleResultFromStoppedScanCannotStartBinding() {
        assertStoppedResultIgnored(false);
    }

    @Test
    public void batchedResultFromStoppedScanCannotStartBinding() {
        assertStoppedResultIgnored(true);
    }

    private void assertStoppedResultIgnored(boolean batch) {
        scenario.onActivity(current -> {
            ScanCallback callback = beginFakeScan();
            // Prevent the old implementation from making a real test binding.
            set("mBLEServiceBound", true);
            invoke("stopBLEScan");
            deliver(callback, batch);
            assertEquals("unchanged", status().getText().toString());
            assertSame(bluetooth, get("mBluetoothLeService"));
        });
    }

    @Test
    public void failureFromPreviousScanCannotStopReplacementScan() {
        scenario.onActivity(current -> {
            ScanCallback old = beginFakeScan();
            ScanCallback replacement = beginFakeScan();
            old.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            assertTrue((boolean) get("mScanning"));
            assertSame(replacement, get("mLeScanCallback"));
            assertSame(bluetooth, get("mBluetoothLeService"));
        });
    }

    @Test
    public void singleResultFromPreviousScanCannotStopReplacementScan() {
        assertPreviousResultIgnored(false);
    }

    @Test
    public void batchedResultFromPreviousScanCannotStopReplacementScan() {
        assertPreviousResultIgnored(true);
    }

    private void assertPreviousResultIgnored(boolean batch) {
        scenario.onActivity(current -> {
            ScanCallback old = beginFakeScan();
            ScanCallback replacement = beginFakeScan();
            set("mBLEServiceBound", true);
            deliver(old, batch);
            assertTrue((boolean) get("mScanning"));
            assertSame(replacement, get("mLeScanCallback"));
            assertEquals("unchanged", status().getText().toString());
        });
    }

    @Test
    public void currentMatchingScanResultIsHandledOnlyOnce() {
        scenario.onActivity(current -> {
            ScanCallback callback = beginFakeScan();
            set("mBLEServiceBound", true);
            deliver(callback, false);
            assertFalse((boolean) get("mScanning"));
            assertEquals(current.getString(R.string.connect_status_connecting), status().getText().toString());
            status().setText("connection is established");
            deliver(callback, true);
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            assertEquals("connection is established", status().getText().toString());
            assertSame(bluetooth, get("mBluetoothLeService"));
        });
    }

    @Test
    public void currentScanFailureStillResetsTheConnection() {
        scenario.onActivity(current -> {
            ScanCallback callback = beginFakeScan();
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR);
            assertFalse((boolean) get("mScanning"));
            assertNull(get("mBluetoothLeService"));
            assertEquals(1, bluetooth.closes);
            assertEquals(current.getString(R.string.connect_status_not_connected), status().getText().toString());
        });
    }

    @Test
    public void staleGattDisconnectAfterReceiverUnregistrationIsIgnored() {
        scenario.onActivity(current -> {
            set("mGattReceiverRegistered", false);
            ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(current,
                    new Intent(BluetoothLeService.ACTION_GATT_DISCONNECTED));

            assertSame(bluetooth, get("mBluetoothLeService"));
            assertTrue((boolean) get("mConnected"));
            assertTrue((boolean) get("mCommunicating"));
        });
    }

    @Test
    public void connectedBlasterPromptsForHeldTriggerIfTelemetryIsStillMissing() {
        scenario.onActivity(current -> {
            set("mGattReceiverRegistered", true);
            ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(current,
                    new Intent(BluetoothLeService.ACTION_GATT_CONNECTED));
            Runnable hint = (Runnable) get("mWeaponSyncTriggerHintRunnable");
            assertTrue(hint != null);
            assertFalse((boolean) get("mCommunicating"));

            hint.run(); // Simulate the two-second delayed callback.

            assertEquals(current.getString(R.string.connect_status_hold_trigger),
                    status().getText().toString());
            assertTrue((boolean) get("mWeaponSyncTriggerHintShown"));
            ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(current,
                    new Intent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED));
            assertEquals(current.getString(R.string.connect_status_hold_trigger),
                    status().getText().toString());
        });
    }

    @Test
    public void telemetryBeforeTheDelayPreventsTheTriggerPrompt() {
        scenario.onActivity(current -> {
            set("mGattReceiverRegistered", true);
            ((BroadcastReceiver) get("mGattUpdateReceiver")).onReceive(current,
                    new Intent(BluetoothLeService.ACTION_GATT_CONNECTED));
            Runnable hint = (Runnable) get("mWeaponSyncTriggerHintRunnable");
            set("mCommunicating", true);

            hint.run();

            assertEquals(current.getString(R.string.connect_status_communicating),
                    status().getText().toString());
            assertFalse((boolean) get("mWeaponSyncTriggerHintShown"));
        });
    }

    @Test
    public void startWizardAppearsOnlyOnceAfterWeaponIsCommunicating() {
        scenario.onActivity(current -> {
            set("mCommunicating", false);
            invoke("maybeShowStartWizard");
            assertNull(get("mStartWizardDialog"));

            set("mCommunicating", true);
            invoke("maybeShowStartWizard");
            AlertDialog wizard = (AlertDialog) get("mStartWizardDialog");
            assertTrue(wizard.isShowing());
            assertEquals(2, wizard.getListView().getCount());
            wizard.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(current -> {
            invoke("maybeShowStartWizard");
            assertNull(get("mStartWizardDialog"));
        });
    }

    @Test
    public void lobbyHostCanChangeModeButJoinedPlayerCannot() {
        scenario.onActivity(current -> {
            PopupMenu menu = new PopupMenu(current, current.findViewById(R.id.use_network_button));
            set("mNetworkPopup", menu);
            invokeNetworkMenu(4); // Hosting a lobby.
            assertTrue(menu.getMenu().findItem(R.id.game_mode_item) != null);
            invokeNetworkMenu(3); // Joined as a player.
            assertNull(menu.getMenu().findItem(R.id.game_mode_item));
        });
    }

    @Test
    public void destroyingActivityDismissesTheWeaponDisconnectDialog() {
        scenario.onActivity(current -> {
            invoke("showWeaponDisconnect");
            assertTrue(get("mBlasterDisconnectDialog") != null);
        });

        scenario.close();
        scenario = null;

        assertNull(get("mBlasterDisconnectDialog"));
    }

    @Test
    public void destroyingActivityCancelsTheScanAndConnectionTimeout() {
        scenario.onActivity(current -> {
            beginFakeScan();
            invoke("startConnectFailTest");
        });
        scenario.close();
        scenario = null;
        assertFalse((boolean) get("mScanning"));
        assertNull(get("mLeScanCallback"));
        assertNull(get("mConnectFailTimer"));
    }

    @Test
    public void staleBindingCannotReplaceTheCurrentBluetoothService() {
        scenario.onActivity(current -> {
            ServiceConnection old = beginFakeBinding();
            ServiceConnection replacement = beginFakeBinding();
            RecordingBluetoothService staleService = new RecordingBluetoothService();
            old.onServiceConnected(null, staleService.onBind(new Intent()));
            assertSame(bluetooth, get("mBluetoothLeService"));
            assertSame(replacement, get("mBLEServiceConnection"));
            assertEquals(0, staleService.connections);
        });
    }

    @Test
    public void staleBindingDisconnectCannotClearTheCurrentBluetoothService() {
        scenario.onActivity(current -> {
            ServiceConnection old = beginFakeBinding();
            beginFakeBinding();
            old.onServiceDisconnected(null);
            assertSame(bluetooth, get("mBluetoothLeService"));
        });
    }

    @Test
    public void cancelledBindingCannotRestartTheBluetoothConnection() {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginFakeBinding();
            set("mBLEServiceBound", false);
            callback.onServiceConnected(null, bluetooth.onBind(new Intent()));
            assertEquals(0, bluetooth.connections);
        });
    }

    @Test
    public void currentBindingStillInitializesAndConnectsTheWeapon() {
        scenario.onActivity(current -> {
            ServiceConnection callback = beginFakeBinding();
            callback.onServiceConnected(null, bluetooth.onBind(new Intent()));
            assertEquals(1, bluetooth.initializations);
            assertEquals(1, bluetooth.connections);
            assertEquals(ADDRESS, bluetooth.address);
            callback.onServiceDisconnected(null);
            assertNull(get("mBluetoothLeService"));
        });
    }

    private ScanCallback beginFakeScan() {
        ScanCallback callback = (ScanCallback) invoke("createLEScanCallback");
        set("mLeScanCallback", callback);
        set("mScanning", true);
        return callback;
    }

    private ServiceConnection beginFakeBinding() {
        ServiceConnection callback = (ServiceConnection) invoke("createBLEServiceConnection");
        set("mBLEServiceConnection", callback);
        set("mBLEServiceBound", true);
        return callback;
    }

    private static void deliver(ScanCallback callback, boolean batch) {
        ScanResult result = new ScanResult(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(ADDRESS), null, -60, 0);
        if (batch) callback.onBatchScanResults(Collections.singletonList(result));
        else callback.onScanResult(0, result);
    }

    private TextView status() { return activity.findViewById(R.id.connect_status_tv); }

    private Object get(String name) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void set(String name, Object value) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private Object invoke(String name) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(activity);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void invokeNetworkMenu(int state) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod("setNetworkMenu", int.class);
            method.setAccessible(true);
            method.invoke(activity, state);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static final class RecordingBluetoothService extends BluetoothLeService {
        int initializations;
        int connections;
        int closes;
        String address;

        @Override public boolean initialize() { initializations++; return true; }
        @Override public synchronized boolean connect(String address) {
            connections++;
            this.address = address;
            return true;
        }
        @Override public synchronized void close() { closes++; super.close(); }
    }
}
