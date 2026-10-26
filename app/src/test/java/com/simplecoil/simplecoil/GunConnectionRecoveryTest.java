package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGatt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.simplecoil.protocol.Powerups;
import com.simplecoil.protocol.RoundRoster;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Real Activity gun-recovery callbacks with only radios, networking and Toasts replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = GunConnectionRecoveryTest.QuietToast.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GunConnectionRecoveryTest {
    @Test public void oldConnectionBroadcastsCannotMutateEitherReplacementGunOrTheRound() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (boolean secondary : new boolean[] {false, true}) {
                for (String action : new String[] {BluetoothLeService.ACTION_GATT_DISCONNECTED,
                        BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED,
                        BluetoothLeService.TELEMETRY_DATA_AVAILABLE}) {
                    byte[] stale = new byte[20];
                    stale[FullscreenActivity.RECOIL_OFFSET_TEAM] = 17;
                    fixture.receive(new Intent(action)
                            .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                            .putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, 41L)
                            .putExtra(BluetoothLeService.EXTRA_DATA, stale));
                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mConnected"));
                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mCommunicating"));
                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryConnected"));
                    assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryCommunicating"));
                    assertEquals(11, fixture.shots(false));assertEquals(7, fixture.shots(true));
                    assertEquals(0, fixture.primary.closes);assertEquals(0, fixture.secondary.closes);
                    assertEquals(0, fixture.primary.discoveries);assertEquals(0, fixture.secondary.discoveries);
                    assertTrue(fixture.primary.commands.isEmpty());assertTrue(fixture.secondary.commands.isEmpty());
                    fixture.assertSessionIntact();
                }
            }
        }
    }

    @Test public void stampedEventsWithoutAnOwningGunServiceAreIgnored() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                set(FullscreenActivity.class, fixture.activity,
                        secondary ? "mSecondaryBluetoothLeService" : "mBluetoothLeService", null);
                fixture.receive(new Intent(BluetoothLeService.ACTION_GATT_DISCONNECTED)
                        .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                        .putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, 42L));
                assertTrue((boolean) get(FullscreenActivity.class, fixture.activity,
                        secondary ? "mSecondaryConnected" : "mConnected"));
                fixture.assertSessionIntact();
            }
        }
    }

    @Test public void anOldWriteAcknowledgementCannotCompleteAReplacementGunsReload() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                if (secondary) invoke(fixture.activity, "startSecondaryReload", new Class<?>[] {int.class}, 1);
                else invoke(fixture.activity, "startReload", new Class<?>[0]);
                String pendingField = secondary ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand";
                String timerField = secondary ? "mSecondaryReloadTimer" : "mReloadTimer";
                byte[] command = ((byte[]) get(FullscreenActivity.class, fixture.activity, pendingField)).clone();
                Intent stale = new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                        .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0)
                        .putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, 41L)
                        .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_COMMAND_UUID)
                        .putExtra(BluetoothLeService.EXTRA_STATUS, BluetoothGatt.GATT_SUCCESS)
                        .putExtra(BluetoothLeService.EXTRA_DATA, command);
                fixture.receive(stale);
                assertArrayEquals(command, (byte[]) get(FullscreenActivity.class, fixture.activity, pendingField));
                assertNull("An old connection started the replacement's reload timer",
                        get(FullscreenActivity.class, fixture.activity, timerField));
                fixture.receive(new Intent(stale).putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, 42L));
                assertNull(get(FullscreenActivity.class, fixture.activity, pendingField));
                assertNotNull(get(FullscreenActivity.class, fixture.activity, timerField));
                fixture.assertSessionIntact();
            }
        }
    }

    @Test public void losingThePrimaryGunPreservesTheGameHostClockAndSecondGun() throws Exception {
        for (boolean hosting : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                set(FullscreenActivity.class, fixture.activity, "mIsServer", hosting);
                fixture.receive(fixture.currentEvent(BluetoothLeService.ACTION_GATT_DISCONNECTED, false));
                assertFalse((boolean) get(FullscreenActivity.class, fixture.activity, "mConnected"));
                assertFalse((boolean) get(FullscreenActivity.class, fixture.activity, "mCommunicating"));
                assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mPrimaryReconnectActive"));
                assertNotNull(get(FullscreenActivity.class, fixture.activity, "mPrimaryReconnectRunnable"));
                assertEquals(1, fixture.primary.closes);
                assertEquals(0, fixture.secondary.closes);
                assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryConnected"));
                assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryCommunicating"));
                assertEquals(7, fixture.shots(true));
                assertEquals(hosting, get(FullscreenActivity.class, fixture.activity, "mIsServer"));
                fixture.assertSessionIntact();
            }
        }
    }

    @Test public void losingTheSecondGunPreservesTheGameAndPrimaryGun() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.receive(fixture.currentEvent(BluetoothLeService.ACTION_GATT_DISCONNECTED, true));
            assertFalse((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryConnected"));
            assertFalse((boolean) get(FullscreenActivity.class, fixture.activity, "mSecondaryCommunicating"));
            assertNotNull(get(FullscreenActivity.class, fixture.activity, "mSecondaryReconnectRunnable"));
            assertEquals(0, fixture.primary.closes);
            assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mConnected"));
            assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mCommunicating"));
            assertEquals(11, fixture.shots(false));
            fixture.assertSessionIntact();
        }
    }

    @Test public void theLegacyActivityTimerDefersToTheServicesConnectionHealthMonitor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            invoke(fixture.activity, "startConnectionTest", new Class<?>[0]);
            set(FullscreenActivity.class, fixture.activity, "mCommunicating", true);
            set(FullscreenActivity.class, fixture.activity, "mConnectionTest", false);
            Runnable legacyTimer = (Runnable) get(FullscreenActivity.class, fixture.activity, "mConnectionTestRunnable");
            assertNotNull(legacyTimer);legacyTimer.run();
            assertEquals("The old Boolean timer closed a link owned by the service watchdog", 0,
                    fixture.primary.closes);
            assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mConnected"));
            assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mCommunicating"));
            fixture.assertSessionIntact();
        }
    }

    @Test public void aServiceWithoutHealthMonitoringStillUsesTheLegacySilenceFallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.primary.healthMonitored = false;
            invoke(fixture.activity, "startConnectionTest", new Class<?>[0]);
            set(FullscreenActivity.class, fixture.activity, "mCommunicating", true);
            set(FullscreenActivity.class, fixture.activity, "mConnectionTest", true);
            Runnable legacyTimer = (Runnable) get(FullscreenActivity.class, fixture.activity, "mConnectionTestRunnable");
            assertNotNull(legacyTimer);legacyTimer.run();
            assertEquals("The fallback ignored a valid telemetry window", 0, fixture.primary.closes);
            legacyTimer.run();
            assertEquals("A silent unmonitored connection was never recovered", 1, fixture.primary.closes);
            assertTrue((boolean) get(FullscreenActivity.class, fixture.activity, "mPrimaryReconnectActive"));
            fixture.assertSessionIntact();
        }
    }

    @Test public void resetOrMismatchedGunIdentityReprogramsAndReloadsOnlyThatGun() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            for (byte reported : new byte[] {0, 17}) {
                try (Fixture fixture = new Fixture()) {
                    invoke(fixture.activity, "handleReportedGunPlayerID", new Class<?>[] {byte.class, boolean.class},
                            reported, secondary);
                    RecordingBluetooth service = secondary ? fixture.secondary : fixture.primary;
                    assertEquals("A reset gun was not reprogrammed and reloaded", 2, service.commands.size());
                    assertEquals(0x80, service.commands.get(0)[2] & 255);
                    assertEquals(2, service.commands.get(1)[2] & 255);
                    for (byte[] command : service.commands) assertEquals(Globals.BOSS_PLAYER_ID, command[4]);
                    assertTrue("Repairing one gun also changed its sibling",
                            (secondary ? fixture.primary : fixture.secondary).commands.isEmpty());
                    assertEquals(1, get(FullscreenActivity.class, fixture.activity,
                            secondary ? "mSecondaryReloading" : "mReloading"));
                    fixture.assertSessionIntact();
                }
            }
        }
    }

    @Test public void identityRepairNeverReloadsAnEmpDisabledOrEliminatedGun() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            for (boolean emp : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture()) {
                    if (emp) ((long[]) get(FullscreenActivity.class, fixture.activity, "mPowerupUntil"))[Powerups.EMP]
                            = SystemClock.elapsedRealtime() + 10_000;
                    else Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
                    invoke(fixture.activity, "handleReportedGunPlayerID", new Class<?>[] {byte.class, boolean.class},
                            (byte) 0, secondary);
                    RecordingBluetooth service = secondary ? fixture.secondary : fixture.primary;
                    assertFalse("Identity repair started a reload during a protected disable",
                            (int) get(FullscreenActivity.class, fixture.activity,
                                    secondary ? "mSecondaryReloading" : "mReloading") == 1);
                    for (byte[] command : service.commands)
                        assertEquals("Identity repair enabled a protected gun", 0x80, command[2] & 255);
                    assertTrue((secondary ? fixture.primary : fixture.secondary).commands.isEmpty());
                    assertEquals(Globals.BOSS_PLAYER_ID, Globals.getInstance().mPlayerID);
                    assertTrue(fixture.client.isClockSynchronized());
                    assertEquals(0, fixture.client.stops);assertEquals(0, fixture.udp.stops);
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BareActivity activity = new BareActivity();
        final RecordingBluetooth primary = new RecordingBluetooth();
        final RecordingBluetooth secondary = new RecordingBluetooth();
        final RecordingClient client = new RecordingClient();
        final RecordingUdp udp = new RecordingUdp();
        final RecordingServer server = new RecordingServer();
        final InetAddress host = InetAddress.getByName("192.168.1.2");
        final String token = UUID.randomUUID().toString();
        final RoundRoster roster = new RoundRoster(token);
        final Object previousGlobals;
        final byte previousCommandId;

        Fixture() throws Exception {
            previousGlobals = get(Globals.class, null, "mInstance");set(Globals.class, null, "mInstance", null);
            previousCommandId = (byte) get(FullscreenActivity.class, activity, "mCommandID");
            Globals globals = Globals.getInstance();
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;globals.mBossMode = true;
            globals.mGameState = Globals.GAME_STATE_RUNNING;globals.mServerIP = host;globals.mRoundRoster = roster;
            globals.mFullReload = 30;globals.mReloadTime = 1_000;globals.mUseGPS = false;
            set(FullscreenActivity.class, activity, "mBluetoothLeService", primary);
            set(FullscreenActivity.class, activity, "mSecondaryBluetoothLeService", secondary);
            set(FullscreenActivity.class, activity, "mTcpClient", client);
            set(FullscreenActivity.class, activity, "mUDPListenerService", udp);
            set(FullscreenActivity.class, activity, "mTcpServer", server);
            set(FullscreenActivity.class, activity, "mCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(FullscreenActivity.class, activity, "mSecondaryCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(FullscreenActivity.class, activity, "mTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(FullscreenActivity.class, activity, "mSecondaryTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(FullscreenActivity.class, activity, "mDeviceAddress", "02:00:00:00:00:01");
            set(FullscreenActivity.class, activity, "mSecondaryDeviceAddress", "02:00:00:00:00:02");
            for (String field : new String[] {"mUseNetwork", "mReady", "mLobbyJoined", "mGattReceiverRegistered",
                    "mConnected", "mCommunicating", "mSecondaryConnected", "mSecondaryCommunicating"})
                set(FullscreenActivity.class, activity, field, true);
            set(FullscreenActivity.class, activity, "mScore", 12);
            set(FullscreenActivity.class, activity, "mHealth", 37);
            set(FullscreenActivity.class, activity, "mEliminationCount", 3);
            set(FullscreenActivity.class, activity, "mActivePeerRoundToken", token);
            set(FullscreenActivity.class, activity, "mStartGameTimer", false);
            set(FullscreenActivity.class, activity, "mReloading", 0);
            set(FullscreenActivity.class, activity, "mSecondaryReloading", 0);
            set(FullscreenActivity.class, activity, "mLastShotCount", (byte) 11);
            set(FullscreenActivity.class, activity, "mSecondaryLastShotCount", (byte) 7);
            set(FullscreenActivity.class, activity, "mPrimaryTeamCommandAt", SystemClock.elapsedRealtime() - 2_501);
            set(FullscreenActivity.class, activity, "mSecondaryTeamCommandAt", SystemClock.elapsedRealtime() - 2_501);
            set(FullscreenActivity.class, activity, "mShotsRemainingTV", new TextView(RuntimeEnvironment.getApplication()));
            set(FullscreenActivity.class, activity, "mReloadBar", new ProgressBar(RuntimeEnvironment.getApplication()));
            set(FullscreenActivity.class, activity, "mHealthLabelTV", new TextView(RuntimeEnvironment.getApplication()));
            set(FullscreenActivity.class, activity, "mLobbyHealthLabelLayoutParams", new RelativeLayout.LayoutParams(100, 40));
            set(TcpClient.class, client, "keepListening", true);
            set(TcpClient.class, client, "out", new DataOutputStream(new ByteArrayOutputStream()));
            set(TcpClient.class, client, "mSessionServerAddress", host);
            set(TcpClient.class, client, "mLobbyRegistered", true);
            set(TcpClient.class, client, "mClockSynchronized", true);
            set(TcpClient.class, client, "mSessionGeneration", 7L);
            GameClock clock = (GameClock) get(TcpClient.class, client, "mGameClock");
            for (int sample = 0; sample < GameClock.SAMPLES_PER_SYNC; sample++) clock.record(100, 5_100, 5_100, 100);
        }
        int shots(boolean secondary) throws Exception {
            return ((byte) get(FullscreenActivity.class, activity,
                    secondary ? "mSecondaryLastShotCount" : "mLastShotCount")) & 255;
        }
        Intent currentEvent(String action, boolean secondary) {
            return new Intent(action).putExtra(BluetoothLeService.EXTRA_CONNECTION_ID, 42L)
                    .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, secondary ? 1 : 0);
        }
        void receive(Intent intent) throws Exception {
            ((BroadcastReceiver) get(FullscreenActivity.class, activity, "mGattUpdateReceiver")).onReceive(activity, intent);
        }
        void assertSessionIntact() throws Exception {
            Globals globals = Globals.getInstance();
            assertEquals(Globals.GAME_STATE_RUNNING, globals.mGameState);
            assertEquals(Globals.BOSS_PLAYER_ID, globals.mPlayerID);assertEquals(host, globals.mServerIP);
            assertSame(roster, globals.mRoundRoster);
            assertEquals(12, get(FullscreenActivity.class, activity, "mScore"));
            assertEquals(37, get(FullscreenActivity.class, activity, "mHealth"));
            assertEquals(3, get(FullscreenActivity.class, activity, "mEliminationCount"));
            assertEquals(token, get(FullscreenActivity.class, activity, "mActivePeerRoundToken"));
            assertEquals(true, get(FullscreenActivity.class, activity, "mReady"));
            assertEquals(true, get(FullscreenActivity.class, activity, "mLobbyJoined"));
            assertSame(client, get(FullscreenActivity.class, activity, "mTcpClient"));
            assertSame(udp, get(FullscreenActivity.class, activity, "mUDPListenerService"));
            assertTrue(client.hasLobbyConnection());assertTrue(client.isClockSynchronized());
            assertEquals(7L, get(TcpClient.class, client, "mSessionGeneration"));
            assertEquals(95_000, ((GameClock) get(TcpClient.class, client, "mGameClock")).toLocalTime(100_000));
            assertEquals(0, client.stops);assertEquals(0, udp.stops);assertEquals(0, server.stops);
        }
        @Override public void close() throws Exception {
            for (Field field : FullscreenActivity.class.getDeclaredFields()) {
                field.setAccessible(true);Object value = field.get(activity);
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
                else if (value instanceof CountDownTimer) ((CountDownTimer) value).cancel();
            }
            set(FullscreenActivity.class, activity, "mCommandID", previousCommandId);
            primary.onDestroy();secondary.onDestroy();client.onDestroy();udp.onDestroy();server.onDestroy();
            set(Globals.class, null, "mInstance", previousGlobals);
        }
    }

    private static final class BareActivity extends FullscreenActivity {
        @Override public <T extends View> T findViewById(int id) { return null; }
    }
    private static final class RecordingBluetooth extends BluetoothLeService {
        final List<byte[]> commands = new ArrayList<>();
        int closes, discoveries;
        boolean healthMonitored = true;
        @Override public synchronized boolean isCurrentConnectionEvent(Intent intent) {
            return !intent.hasExtra(EXTRA_CONNECTION_ID) || intent.getLongExtra(EXTRA_CONNECTION_ID, -1) == 42L;
        }
        @Override public synchronized boolean monitorsConnectionHealth() { return healthMonitored; }
        @Override public synchronized boolean isConnected() { return true; }
        @Override public synchronized void close() { closes++; }
        @Override public synchronized List<BluetoothGattService> getSupportedGattServices() {
            discoveries++;return Collections.emptyList();
        }
        @Override public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            commands.add(value.clone());
        }
    }
    private static final class RecordingClient extends TcpClient {
        int stops;
        @Override public void stopTcpClient() { stops++;super.stopTcpClient(); }
        @Override public void sendBroadcast(Intent intent) { }
    }
    private static final class RecordingUdp extends UDPListenerService {
        int stops;
        @Override void stopListen() { stops++;super.stopListen(); }
        @Override public void sendBroadcast(Intent intent) { }
    }
    private static final class RecordingServer extends TcpServer {
        int stops;
        @Override public void stopTcpServer() { stops++;super.stopTcpServer(); }
        @Override public void sendBroadcast(Intent intent) { }
    }
    @Implements(Toast.class)
    public static class QuietToast {
        @Implementation protected static Toast makeText(Context context, int resourceId, int duration) {
            return new Toast(RuntimeEnvironment.getApplication());
        }
        @Implementation protected void show() { }
    }
    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
    }
    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = FullscreenActivity.class.getDeclaredMethod(name, types);method.setAccessible(true);method.invoke(target, args);
    }
}
