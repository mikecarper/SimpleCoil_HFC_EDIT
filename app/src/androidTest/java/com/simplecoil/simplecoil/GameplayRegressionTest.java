package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.graphics.drawable.AnimationDrawable;
import android.os.CountDownTimer;
import android.os.SystemClock;
import android.widget.Chronometer;
import android.widget.PopupMenu;
import android.view.View;
import android.widget.TextView;

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
    private byte originalPairedGrenade;

    @Before
    public void setUp() {
        originalPairedGrenade = Globals.getInstance().mPairedGrenadeID;
        Globals.getInstance().mPairedGrenadeID = 0;
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
                globals.mPlayerSettings.put((byte) 11, first);
                Globals.PlayerSettings second = new Globals.PlayerSettings();
                second.damage = -7;
                globals.mPlayerSettings.put((byte) 12, second);
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
        Globals.getInstance().mPairedGrenadeID = originalPairedGrenade;
    }

    @Test
    public void cancellingPeerHostAllowsCancellationToFlushBeforeStopping() {
        scenario.onActivity(activity -> {
            int[] calls = new int[3];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
                @Override public void stopTcpServer() { calls[1]++; }
                @Override public void sendTCPMessageAll(String message) { calls[2]++; }
            });
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mIsServer", true);
            set(activity, "mReady", true);
            PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
            assertTrue(activity.onMenuItemClick(menu.getMenu().add(0, R.id.cancel_server_item, 0, "Cancel")));
            assertEquals(1, calls[0]);
            assertEquals("The host must let cancellation close the connection", 0, calls[1]);
            assertEquals("Cancellation must not use a separate racing broadcast", 0, calls[2]);
            assertEquals(false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void cancellingPeerHostEndsAnActiveRoundLocally() {
        scenario.onActivity(activity -> {
            int[] calls = new int[1];
            set(activity, "mTcpServer", new TcpServer() {
                @Override public void cancelServer() { calls[0]++; }
            });
            set(activity, "mIsServer", true);
            set(activity, "mReady", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            PopupMenu menu = new PopupMenu(activity, activity.findViewById(android.R.id.content));
            assertTrue(activity.onMenuItemClick(menu.getMenu().add(0, R.id.cancel_server_item, 0, "Cancel")));
            assertEquals(1, calls[0]);
            assertEquals("Cancelling the host left its own round active", Globals.GAME_STATE_NONE,
                    Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mIsServer"));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void firstGrenadeDisarmNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(false, 0x3D);
    }

    @Test
    public void secondGrenadeDisarmNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(true, 0x3D);
    }

    @Test
    public void firstGrenadeResetNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(false, 0x3E);
    }

    @Test
    public void secondGrenadeResetNotifiesDedicatedServer() {
        assertGrenadeUnpairNotifiesServer(true, 0x3E);
    }

    private void assertGrenadeUnpairNotifiesServer(boolean secondSlot, int command) {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            Globals.getInstance().mPairedGrenadeID = 3;
            byte[] packet = grenadePacket(secondSlot, command);
            receiveTelemetry(activity, packet);
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertEquals(1, tcp.messages.size());
            assertTrue(tcp.messages.get(0).contains("\"" + TcpServer.JSON_PAIRED_GRENADE_ID + "\":0"));
            receiveTelemetry(activity, packet);
            assertEquals("Repeated disarm telemetry should not resend the update", 1, tcp.messages.size());
            assertEquals(20, get(activity, "mHealth"));
        });
    }

    @Test
    public void grenadePairingWithReservedZeroIdIsIgnored() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            receiveTelemetry(activity, grenadePacket(false, 0x0F));
            receiveTelemetry(activity, grenadePacket(true, 0x0F));
            assertEquals(0, Globals.getInstance().mPairedGrenadeID);
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void validGrenadePairingStillNotifiesDedicatedServer() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            receiveTelemetry(activity, grenadePacket(true, 0x3F));
            assertEquals(3, Globals.getInstance().mPairedGrenadeID);
            assertEquals(1, tcp.messages.size());
            assertTrue(tcp.messages.get(0).contains("\"" + TcpServer.JSON_PAIRED_GRENADE_ID + "\":3"));
        });
    }

    private static byte[] grenadePacket(boolean secondSlot, int command) {
        byte[] packet = telemetryPacket(0, 0, 0, 0);
        packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2 : FullscreenActivity.RECOIL_OFFSET_HIT_BY1]
                = (byte) Globals.GRENADE_PLAYER_ID;
        packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID : FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID]
                = (byte) command;
        return packet;
    }

    @Test
    public void fatalHitKeepsKillerVisibleAfterEarlierHitFeedbackExpires() throws InterruptedException {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            set(activity, "mHealth", 6);
            telemetry(activity, 11, 1, 0, 0);
            assertEquals(1, get(activity, "mHealth"));
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitIV")).getVisibility());
            telemetry(activity, 12, 1, 0, 0);
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(View.VISIBLE, ((View) get(activity, "mEliminatedByTV")).getVisibility());
        });
        Thread.sleep(600);
        scenario.onActivity(activity -> {
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals("The earlier hit hid the respawn screen's killer name", View.VISIBLE,
                    ((View) get(activity, "mEliminatedByTV")).getVisibility());
        });
    }

    @Test
    public void fatalHitCancelsTheEarlierNamesFadeAnimation() {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            set(activity, "mHealth", 6);
            telemetry(activity, 11, 1, 0, 0);
            telemetry(activity, 12, 1, 0, 0);
            assertNull("The killer name must not inherit the nonfatal hit's fade",
                    ((View) get(activity, "mEliminatedByTV")).getAnimation());
            assertNull(get(activity, "mHitAnimation"));
        });
    }

    @Test
    public void endingRoundStopsIncomingAndOutgoingHitAnimations() {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            telemetry(activity, 11, 1, 0, 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            AnimationDrawable incoming = (AnimationDrawable) get(activity, "mHitAnimation");
            AnimationDrawable outgoing = (AnimationDrawable) get(activity, "mHitPlayerAnimation");
            assertTrue(incoming.isRunning());
            assertTrue(outgoing.isRunning());
            invoke(activity, "endGame");
            assertNull("The finished round retained its incoming animation", get(activity, "mHitAnimation"));
            assertNull("The finished round retained its hit confirmation", get(activity, "mHitPlayerAnimation"));
            assertEquals(false, incoming.isRunning());
            assertEquals(false, outgoing.isRunning());
        });
    }

    @Test
    public void nextRoundCanShowHitConfirmationBeforeThePreviousCleanupDeadline() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            invoke(activity, "endGame");
            set(activity, "mUseNetwork", false);
            invoke(activity, "startGame");
            CountDownTimer spawn = (CountDownTimer) get(activity, "mSpawnTimer");
            spawn.cancel();
            spawn.onFinish();
            set(activity, "mUseNetwork", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_OUT));
            assertEquals("The old animation suppressed this round's hit confirmation", View.VISIBLE,
                    ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
    }

    @Test
    public void endingRoundImmediatelyClearsScoreFeedback() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(View.VISIBLE, ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            invoke(activity, "endGame");
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mScoreIncreasePlayerNameTV")).getVisibility());
        });
    }

    @Test
    public void newestScoreFeedbackGetsItsOwnFullDisplayInterval() throws InterruptedException {
        scenario.onActivity(activity -> receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED)));
        Thread.sleep(500);
        scenario.onActivity(activity -> receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED)));
        Thread.sleep(400);
        scenario.onActivity(activity -> {
            assertEquals(2, get(activity, "mScore"));
            assertEquals("The first kill's cleanup hid the newer score feedback", View.VISIBLE,
                    ((View) get(activity, "mScoreIncreaseIV")).getVisibility());
            assertEquals(View.VISIBLE, ((View) get(activity, "mScoreIncreasePlayerNameTV")).getVisibility());
        });
    }

    @Test
    public void ordinaryHitFeedbackStillExpires() throws InterruptedException {
        scenario.onActivity(activity -> {
            set(activity, "mHitAnimation", null);
            telemetry(activity, 11, 1, 0, 0);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_HIT));
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitIV")).getVisibility());
            assertEquals(View.VISIBLE, ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
        Thread.sleep(600);
        scenario.onActivity(activity -> {
            assertNull(get(activity, "mHitAnimation"));
            assertNull(get(activity, "mHitPlayerAnimation"));
            assertEquals(View.GONE, ((View) get(activity, "mHitIV")).getVisibility());
            assertEquals(View.GONE, ((View) get(activity, "mHitPlayerIV")).getVisibility());
        });
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
            byte[] first = telemetryPacket(11, 1, 0, 0);
            byte[] second = telemetryPacket(11, 2, 0, 0);
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
    public void unchangedSettingsPreserveRemainingLivesInTheHud() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 3);
        });
    }

    @Test
    public void increasingLifeLimitPreservesDeathsAlreadyTaken() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 8);
            assertLifeCount(activity, 6);
            assertEquals(8, get(activity, "mLives"));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 6);
        });
    }

    @Test
    public void decreasingLifeLimitPreservesDeathsAlreadyTaken() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 4);
            assertLifeCount(activity, 2);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void addingLifeLimitConvertsDeathsToRemainingLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 0, 2);
            changeLifeOverride(activity, 5);
            assertLifeCount(activity, 3);
            assertEquals(true, get(activity, "mHasLivesLimit"));
        });
    }

    @Test
    public void unlimitedOverrideConvertsRemainingLivesBackToDeaths() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 2);
            changeLifeOverride(activity, 0);
            assertLifeCount(activity, 2);
            assertEquals(false, get(activity, "mHasLivesLimit"));
            changeLifeOverride(activity, 6);
            assertLifeCount(activity, 4);
        });
    }

    @Test
    public void removingOverrideRestoresGlobalLimitWithoutRestoringSpentLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 8, 2);
            Globals.getInstance().mLivesLimit = 5;
            Globals.getInstance().mOverrideLives = false;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertLifeCount(activity, 3);
        });
    }

    @Test
    public void exhaustedNewLimitCancelsRespawnAndLeavesDedicatedRound() {
        scenario.onActivity(activity -> {
            tcp.dedicated = true;
            prepareRoundLives(activity, 5, 3);
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Player 11");
            CountDownTimer pendingSpawn = (CountDownTimer) get(activity, "mSpawnTimer");
            changeLifeOverride(activity, 3);
            assertLifeCount(activity, 0);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertEquals(Collections.singletonList(TcpServer.TCPMESSAGE_PREFIX
                    + TcpServer.TCPPREFIX_MESG + NetMsg.NETMSG_LEAVE), tcp.messages);
            pendingSpawn.onFinish();
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
            assertEquals("Repeated settings must not leave again", 1, tcp.messages.size());
        });
    }

    @Test
    public void exhaustedNewLimitLeavesPeerRoundWithoutEndingOtherPlayersGame() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 0, 4);
            changeLifeOverride(activity, 3);
            assertLifeCount(activity, 0);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(Collections.singletonList(NetMsg.NETMSG_LEAVE + ":all"), udp.messages);
            assertEquals(0, udp.endRequests);
        });
    }

    @Test
    public void exhaustedNewLimitEndsOfflineRoundWithoutSendingNetworkMessages() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            prepareRoundLives(activity, 5, 3);
            changeLifeOverride(activity, 2);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertLifeCount(activity, 0);
            assertTrue(udp.messages.isEmpty());
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void changingLivesInTheLobbyDoesNotSpendThePreviousRoundsLives() {
        scenario.onActivity(activity -> {
            prepareRoundLives(activity, 5, 3);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            changeLifeOverride(activity, 2);
            assertEquals("2", ((TextView) get(activity, "mEliminationCountTV")).getText().toString());
            assertEquals(2, get(activity, "mLives"));
            assertTrue(tcp.messages.isEmpty());
            assertTrue(udp.messages.isEmpty());
        });
    }

    private void prepareRoundLives(FullscreenActivity activity, int limit, int deaths) {
        Globals globals = Globals.getInstance();
        globals.mGameState = Globals.GAME_STATE_RUNNING;
        globals.mGameLimit = limit == 0 ? Globals.GAME_LIMIT_NONE : Globals.GAME_LIMIT_LIVES;
        globals.mLivesLimit = limit;
        globals.mOverrideLives = false;
        set(activity, "mHasLivesLimit", limit != 0);
        set(activity, "mLives", limit);
        set(activity, "mEliminationCount", limit == 0 ? deaths : limit - deaths);
        ((TextView) get(activity, "mEliminationCountTV")).setText(
                String.valueOf(limit == 0 ? deaths : limit - deaths));
    }

    private void changeLifeOverride(FullscreenActivity activity, int limit) {
        Globals.getInstance().mOverrideLives = true;
        Globals.getInstance().mOverrideLivesVal = limit;
        receiveNetwork(activity, new Intent(NetMsg.NETMSG_PLAYERSETTINGSUPDATE));
    }

    private void assertLifeCount(FullscreenActivity activity, int count) {
        assertEquals(count, get(activity, "mEliminationCount"));
        assertEquals(String.valueOf(count), ((TextView) get(activity, "mEliminationCountTV")).getText().toString());
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

    @Test
    public void ownKillReachingCombinedTeamLimitEndsPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_2TEAMS);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(2, get(activity, "mScore"));
            assertEquals(5, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void teammateKillReachingLimitEndsFourTeamPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_4TEAMS);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(1, get(activity, "mScore"));
            assertEquals(5, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void freeForAllIgnoresTeamScoreNotifications() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(4, get(activity, "mTeamScore"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void dedicatedScoreLimitDoesNotSendAnEndOrLeaveRequest() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            tcp.dedicated = true;
            Globals.getInstance().mOnlyServerSettings = true;
            set(activity, "mScore", 4);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertTrue(tcp.messages.isEmpty());
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void disabledOrInvalidScoreLimitDoesNotEndRound() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            Globals.getInstance().mScoreLimit = 0;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            Globals.getInstance().mScoreLimit = 1;
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(0, udp.endRequests);
        });
    }

    @Test
    public void individualLimitStillEndsFreeForAllPeerGame() {
        scenario.onActivity(activity -> {
            prepareScoreLimit(activity, Globals.GAME_MODE_FFA);
            set(activity, "mScore", 4);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(1, udp.endRequests);
        });
    }

    @Test
    public void roundEndReceivedWhilePausedIsAppliedOnResume() {
        receiveWhilePaused(NetMsg.NETMSG_ENDGAME);
        scenario.onActivity(activity -> assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState));
    }

    @Test
    public void serverCancellationReceivedWhilePausedIsAppliedOnResume() {
        receiveWhilePaused(NetMsg.NETMSG_SERVERCANCEL);
        scenario.onActivity(activity -> assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState));
    }

    @Test
    public void versionRejectionReceivedWhilePausedClearsLobbyReadiness() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
        });
        receiveWhilePaused(NetMsg.NETMSG_VERSIONERROR);
        scenario.onActivity(activity -> assertEquals(false, get(activity, "mReady")));
    }

    @Test
    public void duplicateTerminalNotificationCannotEndAnotherRound() {
        scenario.onActivity(activity -> {
            finishTcpSession(NetMsg.NETMSG_ENDGAME);
            Intent notification = tcp.broadcasts.get(tcp.broadcasts.size() - 1);
            receiveNetwork(activity, notification);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            receiveNetwork(activity, notification);
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void lateNetworkEndCannotStopAnOfflineRound() {
        scenario.onActivity(activity -> {
            set(activity, "mUseNetwork", false);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_SERVERCANCEL));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_VERSIONERROR));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
        });
    }

    @Test
    public void roundEndClearsReadinessEvenBeforeThePlayerHasSpawned() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            set(activity, "mReady", true);
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            assertEquals(false, get(activity, "mReady"));
        });
    }

    @Test
    public void versionRejectionAlsoStopsAnActiveRound() {
        scenario.onActivity(activity -> {
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_VERSIONERROR));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertEquals(false, get(activity, "mReady"));
        });
    }

    private void prepareScoreLimit(FullscreenActivity activity, int mode) {
        Globals globals = Globals.getInstance();
        globals.mGameMode = mode;
        globals.mGameLimit = Globals.GAME_LIMIT_SCORE;
        globals.mScoreLimit = 5;
        set(activity, "mScore", 1);
        set(activity, "mTeamScore", 4);
    }

    private void finishTcpSession(String action) {
        try {
            Method finish = TcpClient.class.getDeclaredMethod("finishServerSession", String.class);
            finish.setAccessible(true);
            finish.invoke(tcp, action);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void receiveWhilePaused(String action) {
        // Exercise the actual registration/lifecycle callbacks on the main thread.
        // ActivityScenario's helper activity cannot reliably restore this API-22
        // device's foreground task after moveToState(STARTED).
        scenario.onActivity(activity -> {
            boolean wasRegistered = (boolean) get(activity, "mNetworkReceiverRegistered");
            if (wasRegistered)
                activity.onPause();
            try {
                finishTcpSession(action);
            } finally {
                activity.onResume();
                // The device may already have backgrounded the scenario. Restore
                // its original receiver state after exercising the resume callback.
                if (!wasRegistered)
                    activity.onPause();
            }
        });
    }

    @Test
    public void initialCountdownUsesHostDeadlineInsteadOfPersonalRespawnTime() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Globals.getInstance().mRespawnTime = 90;
            long deadline = SystemClock.elapsedRealtime() + 4000;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            String countdown = ((TextView) get(activity, "mSpawnInTV")).getText().toString();
            assertTrue(countdown, countdown.startsWith("Game starts in "));
            assertTrue(countdown, !countdown.contains("90"));
            assertEquals(deadline, get(activity, "mSynchronizedStartAt"));
            assertEquals(true, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void laterRespawnKeepsPersonalDelayAndCannotResetRoundTimer() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() - 1000;
            receiveNetwork(activity, synchronizedStart(deadline, deadline + 60000));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            Object roundTimer = get(activity, "mGameCountdownTimer");
            Globals.getInstance().mRespawnTime = 90;
            invoke(activity, "startSpawn", new Class<?>[]{String.class}, "Hit");
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertTrue(((TextView) get(activity, "mSpawnInTV")).getText().toString().contains("90"));
            assertSame(roundTimer, get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void lateUnlimitedStartUsesOriginalChronometerBase() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() - 5000;
            receiveNetwork(activity, synchronizedStart(deadline, 0));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertEquals(deadline, ((Chronometer) get(activity, "mGameTimer")).getBase());
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void staleZeroSecondsCannotOverrideSynchronizedRejoinDeadline() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            receiveNetwork(activity, gameUpdate(7, 2, 12, 0)
                    .putExtra(NetMsg.INTENT_START_AT, deadline).putExtra(NetMsg.INTENT_END_AT, deadline + 60000));
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(7, get(activity, "mScore"));
            assertEquals(true, get(activity, "mGameTimerRunning"));
        });
    }

    @Test
    public void expiredSynchronizedStartNeverArmsBlaster() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, synchronizedStart(SystemClock.elapsedRealtime() - 5000,
                    SystemClock.elapsedRealtime() - 1000));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
            assertNull(get(activity, "mGameCountdownTimer"));
        });
    }

    @Test
    public void initialCountdownCannotReceivePointsAndCancelledTimerCannotStartGame() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            receiveNetwork(activity, synchronizedStart(SystemClock.elapsedRealtime() + 10000, 0));
            CountDownTimer countdown = (CountDownTimer) get(activity, "mSpawnTimer");
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ELIMINATED));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_TEAMELIMINATED));
            assertEquals(0, get(activity, "mScore"));
            assertEquals(0, get(activity, "mTeamScore"));
            receiveNetwork(activity, new Intent(NetMsg.NETMSG_ENDGAME));
            countdown.onFinish();
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void startIsRetainedUntilActivityServicesAreBound() {
        scenario.onActivity(activity -> {
            prepareDedicatedJoin(activity);
            Intent start = synchronizedStart(SystemClock.elapsedRealtime() + 10000, 0)
                    .putExtra(TcpClient.EXTRA_START_EVENT_ID, 42L);
            try {
                Field pending = TcpClient.class.getDeclaredField("mPendingGameStartEvent");
                pending.setAccessible(true);
                pending.set(tcp, start);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            Object server = get(activity, "mTcpServer");
            set(activity, "mTcpServer", null);
            receiveNetwork(activity, start);
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            set(activity, "mTcpServer", server);
            boolean registered = (boolean) get(activity, "mNetworkReceiverRegistered");
            set(activity, "mNetworkReceiverRegistered", true);
            try { invoke(activity, "consumePendingServerEvent"); }
            finally { set(activity, "mNetworkReceiverRegistered", registered); }
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertNull(tcp.consumePendingGameStart(0));
            Object timer = get(activity, "mSpawnTimer");
            receiveNetwork(activity, start);
            assertSame(timer, get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void pausedPeerHostRecoversItsOriginalDeadlineAndRejectsCancelledStart() {
        scenario.onActivity(activity -> {
            set(activity, "mReady", true);
            set(activity, "mIsServer", true);
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            TcpServer server = (TcpServer) get(activity, "mTcpServer");
            try {
                for (String fieldName : new String[]{"mScheduledStart", "mRoundSequence", "mStartAnnounced"}) {
                    Field field = TcpServer.class.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    if (fieldName.equals("mStartAnnounced")) field.setBoolean(server, true);
                    else field.setLong(server, fieldName.equals("mRoundSequence") ? 1 : deadline);
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            boolean registered = (boolean) get(activity, "mNetworkReceiverRegistered");
            set(activity, "mNetworkReceiverRegistered", true);
            try { invoke(activity, "consumePendingServerEvent"); }
            finally { set(activity, "mNetworkReceiverRegistered", registered); }
            assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState);
            assertEquals(deadline, get(activity, "mSynchronizedStartAt"));
            invoke(activity, "endGame");
            set(activity, "mReady", true);
            set(activity, "mIsServer", true);
            receiveNetwork(activity, synchronizedStart(deadline, 0));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    private static Intent synchronizedStart(long startAt, long endAt) {
        return new Intent(NetMsg.NETMSG_STARTGAME).putExtra(NetMsg.INTENT_START_AT, startAt)
                .putExtra(NetMsg.INTENT_END_AT, endAt).putExtra(NetMsg.INTENT_ROUND_ID, 1L);
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
            telemetry(activity, 0, 0, 11, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void twoDifferentAttackersApplyTheirOwnDamageOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 11, 1, 12, 1);
            assertEquals(8, get(activity, "mHealth"));
        });
    }

    @Test
    public void repeatedShotInBothSlotsAndNextPacketCountsOnce() {
        scenario.onActivity(activity -> {
            telemetry(activity, 11, 1, 11, 1);
            assertEquals(15, get(activity, "mHealth"));
            telemetry(activity, 11, 1, 11, 1);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void differentShotsFromSameAttackerBothCount() {
        scenario.onActivity(activity -> {
            telemetry(activity, 11, 1, 11, 2);
            assertEquals(10, get(activity, "mHealth"));
        });
    }

    @Test
    public void friendlyHitDoesNotAddDamageToEnemyHit() {
        scenario.onActivity(activity -> {
            telemetry(activity, 2, 1, 11, 1);
            assertEquals(15, get(activity, "mHealth"));
        });
    }

    @Test
    public void lastNetworkLifeLeavesWithoutRespawning() {
        scenario.onActivity(activity -> {
            set(activity, "mHasLivesLimit", true);
            set(activity, "mEliminationCount", 1);
            set(activity, "mHealth", 5);
            telemetry(activity, 11, 1, 0, 0);
            assertTrue(udp.messages.contains(NetMsg.NETMSG_ELIMINATED + ":11"));
            assertTrue(udp.messages.contains(NetMsg.NETMSG_LEAVE + ":all"));
            assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState);
            assertNull(get(activity, "mSpawnTimer"));
        });
    }

    @Test
    public void twentiethPlayerCanBeConfiguredOnTheBlasterAndScoreHits() {
        scenario.onActivity(activity -> {
            Globals.getInstance().mPlayerID = 20;
            Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
            invoke(activity, "setTeam");
            assertEquals(20, bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
            Globals.getInstance().mPlayerID = 1;
            Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
            set(activity, "mNetworkTeam", 1);
            telemetry(activity, 20, 1, 0, 0);
            assertEquals(19, get(activity, "mHealth"));
        });
    }

    @Test
    public void zeroBlasterStatusCannotEraseTheSelectedPlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(0);
    }

    @Test
    public void unsupportedBlasterStatusCannotReplaceTheSelectedPlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(21);
    }

    @Test
    public void unsignedBlasterStatusCannotInstallANegativePlayerId() {
        assertInvalidTelemetryPlayerIdIgnored(255);
    }

    private void assertInvalidTelemetryPlayerIdIgnored(int playerID) {
        scenario.onActivity(activity -> {
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = (byte) playerID;
            packet[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 29;
            receiveTelemetry(activity, packet);
            assertEquals("Invalid telemetry replaced our registered player ID", 1, Globals.getInstance().mPlayerID);
            assertEquals((byte) 1, get(activity, "mLastTeam"));
            assertEquals(1, get(activity, "mNetworkTeam"));
            assertEquals("Other valid fields in the packet should still be processed", (byte) 29,
                    get(activity, "mLastShotCount"));
            invoke(activity, "startReload");
            assertEquals("Reload must still configure our selected ID", 1,
                    bluetooth.writes.get(bluetooth.writes.size() - 1)[4]);
        });
    }

    @Test
    public void unsupportedFirstHitCannotTakeTheLastLife() {
        assertUnsupportedHitIgnored(false);
    }

    @Test
    public void unsupportedSecondHitCannotTakeTheLastLife() {
        assertUnsupportedHitIgnored(true);
    }

    private void assertUnsupportedHitIgnored(boolean secondSlot) {
        scenario.onActivity(activity -> {
            set(activity, "mHealth", 1);
            set(activity, "mHasLivesLimit", true);
            set(activity, "mEliminationCount", 1);
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[secondSlot ? FullscreenActivity.RECOIL_OFFSET_HIT_BY2
                    : FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) (21 << 2);
            receiveTelemetry(activity, packet);
            assertEquals("An unsupported attacker consumed our last life", 1, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mEliminationCount"));
            assertEquals(0, get(activity, "mHitsTaken"));
            assertEquals(Globals.GAME_STATE_RUNNING, Globals.getInstance().mGameState);
            assertTrue(udp.messages.isEmpty());
            assertTrue(tcp.messages.isEmpty());
        });
    }

    @Test
    public void invalidHitSlotDoesNotHideAValidHitInTheSamePacket() {
        scenario.onActivity(activity -> {
            telemetry(activity, 21, 1, 11, 1);
            assertEquals(15, get(activity, "mHealth"));
            assertEquals(1, get(activity, "mHitsTaken"));
        });
    }

    @Test
    public void reservedAndUnsignedHitSourcesCannotCauseDamage() {
        scenario.onActivity(activity -> {
            for (int source : new int[]{1, 2, 3, 84, 127, 128, 164, 166, 168, 255}) {
                byte[] packet = telemetryPacket(0, 0, 0, 0);
                packet[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) source;
                receiveTelemetry(activity, packet);
                assertEquals("Invalid source " + source + " caused damage", 20, get(activity, "mHealth"));
                assertEquals("Invalid source " + source + " counted as a hit", 0, get(activity, "mHitsTaken"));
            }
            assertTrue(udp.messages.isEmpty());
        });
    }

    @Test
    public void validTwentiethPlayerStatusStillUpdatesTheDisplayedTeam() {
        scenario.onActivity(activity -> {
            byte[] packet = telemetryPacket(0, 0, 0, 0);
            packet[FullscreenActivity.RECOIL_OFFSET_TEAM] = 20;
            receiveTelemetry(activity, packet);
            assertEquals(20, Globals.getInstance().mPlayerID);
            assertEquals((byte) 20, get(activity, "mLastTeam"));
            assertEquals(2, get(activity, "mNetworkTeam"));
        });
    }

    @Test
    public void grenadeDamageStillUsesThePairedOwnersSettings() {
        scenario.onActivity(activity -> {
            Globals globals = Globals.getInstance();
            Globals.getmGrenadePairingsSemaphore();
            int previousOwner;
            try {
                previousOwner = globals.mGrenadePairings[3];
                globals.mGrenadePairings[3] = 11;
            } finally { globals.mGrenadePairingsSemaphore.release(); }
            try {
                receiveTelemetry(activity, grenadePacket(false, 0x31));
                assertEquals(15, get(activity, "mHealth"));
                assertEquals(1, get(activity, "mHitsTaken"));
            } finally {
                Globals.getmGrenadePairingsSemaphore();
                try { globals.mGrenadePairings[3] = previousOwner; }
                finally { globals.mGrenadePairingsSemaphore.release(); }
            }
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
        final List<Intent> broadcasts = new ArrayList<>();

        @Override
        public void sendBroadcast(Intent intent) { broadcasts.add(intent); }

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
        int endRequests;

        @Override
        public void endGame() { endRequests++; }

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
