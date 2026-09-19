package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.os.CountDownTimer;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the real activity's timer and telemetry paths, with no blaster or network traffic. */
@RunWith(AndroidJUnit4.class)
public class GameplayRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private RecordingBluetoothService bluetooth;
    private RecordingUDPService udp;
    private BluetoothLeService originalBluetooth;
    private UDPListenerService originalUDP;
    private TcpClient originalTcpClient;
    private TcpServer originalTcpServer;
    private RecordingTcpClient tcp;

    @Before
    public void setUp() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mUseGPS = false;
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(activity -> {
            originalBluetooth = (BluetoothLeService) get(activity, "mBluetoothLeService");
            originalUDP = (UDPListenerService) get(activity, "mUDPListenerService");
            originalTcpClient = (TcpClient) get(activity, "mTcpClient");
            originalTcpServer = (TcpServer) get(activity, "mTcpServer");
            bluetooth = new RecordingBluetoothService();
            udp = new RecordingUDPService();
            tcp = new RecordingTcpClient();
            set(activity, "mBluetoothLeService", bluetooth);
            set(activity, "mUDPListenerService", udp);
            set(activity, "mTcpClient", tcp);
            set(activity, "mTcpServer", new TcpServer());
            set(activity, "mCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mTelemetryCharacteristic", characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            set(activity, "mUseNetwork", true);
            set(activity, "mCommunicating", true);
            set(activity, "mReloading", 0);
            set(activity, "mNetworkTeam", 1);
            set(activity, "mHealth", 20);
            set(activity, "mShield", 0);
            set(activity, "mLastTeam", (byte) 1);
            set(activity, "mLastShotCount", (byte) 30);
            set(activity, "mHasLivesLimit", false);
            set(activity, "mStartGameTimer", false);
            set(activity, "mReady", false);
            set(activity, "mIsServer", false);
            // Skip the cosmetic animation so damage tests do not leave delayed UI work.
            set(activity, "mHitAnimation", new AnimationDrawable());
            Globals globals = Globals.getInstance();
            globals.mPlayerID = 1;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mFullShields = 0;
            globals.mFullReload = 30;
            globals.mRespawnTime = 10;
            globals.mGameLimit = Globals.GAME_LIMIT_NONE;
            globals.mOverrideLives = false;
            globals.mOnlyServerSettings = false;
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            Globals.getmPlayerSettingsSemaphore();
            try {
                globals.mPlayerSettings.clear();
                Globals.PlayerSettings first = new Globals.PlayerSettings();
                first.damage = -5;
                globals.mPlayerSettings.put((byte) 9, first);
                Globals.PlayerSettings second = new Globals.PlayerSettings();
                second.damage = -7;
                globals.mPlayerSettings.put((byte) 10, second);
            } finally {
                globals.mPlayerSettingsSemaphore.release();
            }
        });
    }

    @After
    public void tearDown() {
        if (scenario == null) return;
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            invoke(activity, "endGame");
            set(activity, "mBluetoothLeService", originalBluetooth);
            set(activity, "mUDPListenerService", originalUDP);
            set(activity, "mTcpClient", originalTcpClient);
            set(activity, "mTcpServer", originalTcpServer);
            Globals.getInstance().mFullShields = Globals.MAX_SHIELDS;
            Globals.getInstance().mReloadTime = Globals.RELOAD_TIME_MILLISECONDS;
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            Globals.getInstance().mOverrideLives = false;
            set(activity, "mHasLivesLimit", false);
        });
        scenario.close();
    }

    @Test
    public void zeroDelayReloadRefillsImmediately() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 0;
            invoke(activity, "startReload");
            completeWrite(activity);
            assertEquals("The refill command must be sent even with a zero delay", 2, bluetooth.writes.size());
            assertEquals(4, bluetooth.writes.get(1)[2]);
            assertEquals(30, bluetooth.writes.get(1)[6]);
            assertNull(get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void replacedReloadTimerCannotRefillNewReload() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer oldTimer = (CountDownTimer) get(activity, "mReloadTimer");
            set(activity, "mUseNetwork", false);
            invoke(activity, "endGame");
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mReloading", 0);
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer currentTimer = (CountDownTimer) get(activity, "mReloadTimer");
            int writesBeforeStaleCallback = bluetooth.writes.size();
            oldTimer.onFinish();
            assertEquals(writesBeforeStaleCallback, bluetooth.writes.size());
            assertSame(currentTimer, get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void configurationWriteCannotStartReloadTimer() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            completeWrite(activity, GattAttributes.RECOIL_CONFIG_UUID,
                    bluetooth.writes.get(0), BluetoothGatt.GATT_SUCCESS);
            assertNull(get(activity, "mReloadTimer"));
            assertEquals(1, get(activity, "mReloading"));
        });
    }

    @Test
    public void differentCommandCannotStartReloadTimer() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            byte[] otherCommand = bluetooth.writes.get(0).clone();
            otherCommand[0] += 0x10;
            completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                    otherCommand, BluetoothGatt.GATT_SUCCESS);
            assertNull(get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void duplicateWriteCompletionCannotRestartReloadTimer() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer timer = (CountDownTimer) get(activity, "mReloadTimer");
            completeWrite(activity);
            assertSame(timer, get(activity, "mReloadTimer"));
        });
    }

    @Test
    public void failedRefillDoesNotInventAmmoAndAllowsRetry() {
        scenario.onActivity(activity -> {
            set(activity, "mLastShotCount", (byte) 0);
            invoke(activity, "startReload");
            invoke(activity, "finishReload");
            completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                    bluetooth.writes.get(1), BluetoothGatt.GATT_FAILURE);
            assertEquals((byte) 0, get(activity, "mLastShotCount"));
            assertEquals(0, get(activity, "mReloading"));
            invoke(activity, "startReload");
            assertEquals(3, bluetooth.writes.size());
        });
    }

    @Test
    public void eliminationOverridesPendingRefillAndIgnoresItsCompletion() {
        scenario.onActivity(activity -> {
            invoke(activity, "startReload");
            invoke(activity, "finishReload");
            byte[] oldRefill = bluetooth.writes.get(1);
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Test attacker");
            assertEquals(3, get(activity, "mReloading"));
            assertEquals(2, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
            completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                    oldRefill, BluetoothGatt.GATT_SUCCESS);
            assertEquals(3, get(activity, "mReloading"));
            completeWrite(activity);
            assertNull(get(activity, "mReloadTimer"));
            ((CountDownTimer) get(activity, "mSpawnTimer")).onFinish();
            assertEquals(4, bluetooth.writes.get(bluetooth.writes.size() - 1)[2]);
            completeWrite(activity);
            assertEquals(0, get(activity, "mReloading"));
        });
    }

    @Test
    public void telemetryUsesEachBroadcastPacketEvenAfterCharacteristicChanges() {
        scenario.onActivity(activity -> {
            byte[] first = telemetryPacket(9, 1, 0, 0);
            byte[] second = telemetryPacket(9, 2, 0, 0);
            ((BluetoothGattCharacteristic) get(activity, "mTelemetryCharacteristic")).setValue(second);
            receiveTelemetry(activity, first);
            receiveTelemetry(activity, second);
            assertEquals(10, get(activity, "mHealth"));
        });
    }

    @Test
    public void bluetoothResetCancelsReloadAndDiscardsOldCharacteristics() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mReloadTime = 10000;
            invoke(activity, "startReload");
            completeWrite(activity);
            CountDownTimer timer = (CountDownTimer) get(activity, "mReloadTimer");
            invoke(activity, "resetBluetoothServices");
            assertNull(get(activity, "mReloadTimer"));
            assertNull(get(activity, "mPendingReloadCommand"));
            assertNull(get(activity, "mCommandCharacteristic"));
            assertNull(get(activity, "mTelemetryCharacteristic"));
            assertNull(get(activity, "mConfigCharacteristic"));
            assertEquals(3, get(activity, "mReloading"));
            int sent = bluetooth.writes.size();
            timer.onFinish();
            assertEquals(sent, bluetooth.writes.size());
        });
    }

    @Test
    public void rediscoveringBlasterStartsFreshReloadAfterInterruptedRefill() {
        scenario.onActivity(activity -> {
            set(activity, "mReloading", 2);
            BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
            receiver.onReceive(activity, new Intent(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED));
            assertEquals(1, get(activity, "mReloading"));
            assertEquals(1, bluetooth.writes.size());
            assertEquals(2, bluetooth.writes.get(0)[2]);
        });
    }

    @Test
    public void lateJoinPreservesServerScoresAndRemainingLives() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 2, 12, 60));
            assertEquals(7, get(activity, "mScore"));
            assertEquals(12, get(activity, "mTeamScore"));
            assertEquals(3, get(activity, "mEliminationCount"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertTrue((Boolean) get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void reconnectTranslatesDeathsToRemainingLives() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            invoke(activity, "setGameLimit");
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receiveNetwork(activity, gameUpdate(7, 2, 12, 60));
            assertEquals(3, get(activity, "mEliminationCount"));
        });
    }

    @Test
    public void unlimitedLifeOverrideKeepsTheDeathCountOnReconnect() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Globals.getInstance().mOverrideLives = true;
            Globals.getInstance().mOverrideLivesVal = 0;
            receiveNetwork(activity, gameUpdate(7, 8, 12, 60));
            assertEquals(8, get(activity, "mEliminationCount"));
            assertEquals(false, get(activity, "mHasLivesLimit"));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void exhaustedPlayerCannotRespawnByRejoining() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 5, 12, 60));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(0, get(activity, "mEliminationCount"));
            assertNull(get(activity, "mSpawnTimer"));
            assertTrue(tcp.messages.contains(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE));
        });
    }

    @Test
    public void joiningExpiredRoundDoesNotStartAnotherSpawnOrCountdown() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 2, 12, 0));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertNull(get(activity, "mGameCountdownTimer"));
            assertEquals(false, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void dedicatedCountdownEndsLocallyWithoutWaitingForServerReply() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            invoke(activity, "startGameCountdown", new Class<?>[]{long.class}, 0L);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertTrue(tcp.messages.contains(TcpServer.TCPMESSAGE_PREFIX + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_ENDGAME));
        });
    }

    @Test
    public void playerListUpdateCannotExposeLobbyControlsDuringRound() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(0, 0, 0, 60));
            assertEquals(View.GONE, ((View) get(activity, "mStartGameButton")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mPlayerSettingsButton")).getVisibility());
            assertTrue(((View) get(activity, "mFiringModeButton")).getVisibility() != View.VISIBLE);
        });
    }

    @Test
    public void finishedServerSnapshotDoesNotStartLobbyCountdown() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, gameUpdate(7, 5, 12, 0)
                    .putExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_NONE));
            assertNull(get(activity, "mGameCountdownTimer"));
            assertEquals(false, get(activity, "mGameTimerRunning"));
            assertEquals(7, get(activity, "mScore"));
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void lateStartAnnouncementCannotRestartRoundAfterLeaving() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            set(activity, "mReady", true);
            invoke(activity, "endGame");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_STARTGAME));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void lateScoreMessagesCannotChangeFinishedRound() {
        scenario.onActivity(activity -> {
            set(activity, "mScore", 7);
            set(activity, "mTeamScore", 12);
            invoke(activity, "endGame");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(7, get(activity, "mScore"));
            assertEquals(12, get(activity, "mTeamScore"));
        });
    }

    @Test
    public void readyLobbyStillAcceptsServerStart() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            invoke(activity, "setGameLimit");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_STARTGAME));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void respawningPlayerStillReceivesEarnedPoints() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
            set(activity, "mScore", 0);
            set(activity, "mTeamScore", 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(1, get(activity, "mScore"));
            assertEquals(1, get(activity, "mTeamScore"));
        });
    }

    private void prepareDedicatedJoin(FullscreenActivity activity) {
        tcp.dedicated = true;
        set(activity, "mReady", true);
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_LIVES | Globals.GAME_LIMIT_TIME;
        Globals.getInstance().mLivesLimit = 5;
        Globals.getInstance().mTimeLimit = 5;
    }

    private static Intent gameUpdate(int score, int deaths, int teamScore, long remainingSeconds) {
        return new Intent(NetMsg.NETMSG_LISTPLAYERS)
                .putExtra(NetMsg.INTENT_GAMESTATE, Globals.GAME_STATE_RUNNING)
                .putExtra(NetMsg.INTENT_HASGAMEUPDATE, true)
                .putExtra(NetMsg.INTENT_SCORE, score)
                .putExtra(NetMsg.INTENT_ELIMINATIONS, deaths)
                .putExtra(NetMsg.INTENT_TEAMSCORE, teamScore)
                .putExtra(NetMsg.INTENT_TIMEREMAINING, remainingSeconds);
    }

    private static void receiveNetwork(FullscreenActivity activity, Intent intent) {
        ((BroadcastReceiver) get(activity, "mUDPUpdateReceiver")).onReceive(activity, intent);
    }

    @Test
    public void zeroRemainingGameTimeEndsRound() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            invoke(activity, "startGameCountdown", new Class<?>[]{long.class}, 0L);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void hitInSecondSlotUsesConfiguredDamageOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 0, 0, 9, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void twoDifferentAttackersApplyTheirOwnDamageOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 9, 1, 10, 1);
            assertEquals(8, get(activity, "mHealth"));
        });
    }

    @Test
    public void repeatedShotInBothSlotsAndNextPacketCountsOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 9, 1, 9, 1);
            assertEquals(15, get(activity, "mHealth"));
            telemetry(activity, 9, 1, 9, 1);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void differentShotsFromSameAttackerBothCount() {
        scenario.onActivity(activity -> {
            telemetry(activity, 9, 1, 9, 2);
            assertEquals(10, get(activity, "mHealth"));
        });
    }

    @Test
    public void friendlyHitDoesNotAddDamageToEnemyHit() {
        scenario.onActivity(activity -> {
            telemetry(activity, 2, 1, 9, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void lastNetworkLifeLeavesWithoutRespawning() {
        scenario.onActivity(activity -> {
            set(activity, "mHasLivesLimit", true);
            set(activity, "mEliminationCount", 1);
            set(activity, "mHealth", 5);
            telemetry(activity, 9, 1, 0, 0);
            assertTrue(udp.messages.contains(NetMsg.NETMSG_ELIMINATED + ":9"));
            assertTrue(udp.messages.contains(NetMsg.NETMSG_LEAVE + ":all"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    private void completeWrite(FullscreenActivity activity) {
        completeWrite(activity, GattAttributes.RECOIL_COMMAND_UUID,
                bluetooth.writes.get(bluetooth.writes.size() - 1), BluetoothGatt.GATT_SUCCESS);
    }

    private static void completeWrite(FullscreenActivity activity, String uuid, byte[] data, int status) {
        BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
        receiver.onReceive(activity, new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                .putExtra(BluetoothLeService.EXTRA_UUID, uuid)
                .putExtra(BluetoothLeService.EXTRA_STATUS, status)
                .putExtra(BluetoothLeService.EXTRA_DATA, data));
    }

    private static void telemetry(FullscreenActivity activity, int firstPlayer, int firstShot,
                                  int secondPlayer, int secondShot) {
        byte[] packet = telemetryPacket(firstPlayer, firstShot, secondPlayer, secondShot);
        ((BluetoothGattCharacteristic) get(activity, "mTelemetryCharacteristic")).setValue(packet);
        receiveTelemetry(activity, packet);
    }

    private static void receiveTelemetry(FullscreenActivity activity, byte[] packet) {
        BroadcastReceiver receiver = (BroadcastReceiver) get(activity, "mGattUpdateReceiver");
        receiver.onReceive(activity, new Intent(BluetoothLeService.TELEMETRY_DATA_AVAILABLE)
                .putExtra(BluetoothLeService.EXTRA_DATA, packet));
    }

    private static byte[] telemetryPacket(int firstPlayer, int firstShot, int secondPlayer, int secondShot) {
        byte[] packet = new byte[20];
        packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = 1;
        packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 30;
        packet[FullscreenActivity.RECOIL_OFFSET_BATTERY_LEVEL] = 16;
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) (firstPlayer << 2);
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID] = (byte) firstShot;
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2] = (byte) (secondPlayer << 2);
        packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID] = (byte) secondShot;
        return packet;
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), 0, 0);
    }

    private static Object get(FullscreenActivity activity, String name) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(FullscreenActivity activity, String name, Object value) {
        try {
            Field field = FullscreenActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(activity, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(FullscreenActivity activity, String name) {
        invoke(activity, name, new Class<?>[0]);
    }

    private static void invoke(FullscreenActivity activity, String name, Class<?>[] types, Object... args) {
        try {
            Method method = FullscreenActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(activity, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RecordingBluetoothService extends BluetoothLeService {
        final List<byte[]> writes = new ArrayList<>();

        @Override
        public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
            writes.add(characteristic.getValue().clone());
        }

        @Override
        public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            writes.add(value.clone());
        }

        @Override
        public List<BluetoothGattService> getSupportedGattServices() {
            BluetoothGattService service = new BluetoothGattService(
                    UUID.fromString(GattAttributes.RECOIL_MAIN_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_TELEMETRY_UUID));
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            service.addCharacteristic(characteristic(GattAttributes.RECOIL_CONFIG_UUID));
            return Collections.singletonList(service);
        }

        @Override
        public synchronized void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
            // Discovery tests use only simulated characteristics.
        }
    }

    private static final class RecordingTcpClient extends TcpClient {
        boolean dedicated;
        final List<String> messages = new ArrayList<>();

        @Override
        public boolean isDedicatedServer() {
            return dedicated;
        }

        @Override
        public void sendTCPMessage(String message, boolean queueMessage) {
            messages.add(message);
        }

        @Override
        public void stopTcpClient() {
            // No network connection is opened by this test client.
        }
    }

    private static final class RecordingUDPService extends UDPListenerService {
        final List<String> messages = new ArrayList<>();

        @Override
        public void sendUDPMessage(String message, Byte playerID) {
            messages.add(message + ":" + playerID);
        }

        @Override
        public void sendUDPMessageAll(String message) {
            messages.add(message + ":all");
        }

        @Override
        void stopListen() {
            // No sockets are opened by this test service.
        }
    }
}
