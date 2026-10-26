package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.simplecoil.protocol.Powerups;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the actual reload receivers and timers without a radio or Activity.onCreate(). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReloadRecoveryTest {
    private static final long RELOAD_WAIT_MS = 1_000;

    @Test public void ordinaryReloadWaitsThenRefillsOnlyItsOwnGun() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                assertEquals(1, fixture.state(secondary));
                fixture.assertLatestCommand(secondary, 2);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertNotNull(fixture.timer(secondary));
                advance(RELOAD_WAIT_MS - 1);
                assertEquals("The refill bypassed the configured reload wait", 1,
                        fixture.service(secondary).writes.size());
                advance(1);
                fixture.finishWait(secondary);
                fixture.assertLatestCommand(secondary, 4);
                assertEquals(2, fixture.state(secondary));
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
                assertNull(fixture.pending(secondary));
                assertTrue("Reloading one gun wrote to the other gun",
                        fixture.service(!secondary).writes.isEmpty());
                assertEquals(0, fixture.state(!secondary));
            }
        }
    }

    @Test public void failedRefillAutomaticallyRetriesWithoutAnotherReloadWait() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                advance(RELOAD_WAIT_MS);
                fixture.finishWait(secondary);
                byte[] failed = fixture.latest(secondary).clone();
                fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                assertEquals("A failed refill abandoned the waiting player", 2,
                        fixture.state(secondary));
                advance(250);
                assertEquals("A failed refill was never retried", 3,
                        fixture.service(secondary).writes.size());
                fixture.assertLatestCommand(secondary, 4);
                assertRetryPayload(failed, fixture.latest(secondary));
                assertNull("Retrying a refill restarted the reload wait", fixture.timer(secondary));
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
                assertTrue(fixture.service(!secondary).writes.isEmpty());
            }
        }
    }

    @Test public void failedDisableRetriesThenStillWaitsTheConfiguredReloadTime() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                byte[] failed = fixture.latest(secondary).clone();
                fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                advance(250);
                assertEquals("A failed disable was never retried", 2,
                        fixture.service(secondary).writes.size());
                assertRetryPayload(failed, fixture.latest(secondary));
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                advance(RELOAD_WAIT_MS - 1);
                assertEquals("Retrying the disable shortened the reload wait", 2,
                        fixture.service(secondary).writes.size());
                advance(1);
                fixture.finishWait(secondary);
                fixture.assertLatestCommand(secondary, 4);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
            }
        }
    }

    @Test public void expiredNinetyRoundBuffKeepsRecoveryForTheReplacementNormalRefill() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                long[] powers = (long[]) get(fixture.activity, "mPowerupUntil");
                powers[Powerups.FULL_AUTO_90] = SystemClock.elapsedRealtime() + 10_000;
                fixture.start(secondary);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                advance(RELOAD_WAIT_MS);fixture.finishWait(secondary);
                byte[] staleNinety = fixture.latest(secondary).clone();
                assertEquals(90, staleNinety[6] & 255);

                powers[Powerups.FULL_AUTO_90] = 0;
                invoke(fixture.activity, "capPowerupMagazine", new Class<?>[] {boolean.class}, secondary);
                byte[] capped = fixture.latest(secondary).clone();
                fixture.assertLatestCommand(secondary, 4);
                assertEquals(30, capped[6] & 255);
                assertNotEquals(staleNinety[0], capped[0]);
                fixture.complete(secondary, staleNinety, BluetoothGatt.GATT_SUCCESS);
                assertEquals("An old buffed refill completed the normal replacement", 2, fixture.state(secondary));
                assertArrayEquals(capped, fixture.pending(secondary));

                fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                advance(250);
                assertEquals("Expiry dropped recovery for an ordinary reload", 4,
                        fixture.service(secondary).writes.size());
                assertRetryPayload(capped, fixture.latest(secondary));
                assertEquals(30, fixture.latest(secondary)[6] & 255);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
                assertNull(fixture.pending(secondary));
                assertTrue(fixture.service(!secondary).writes.isEmpty());
            }
        }
    }

    @Test public void eliminationSupersedesFailedReloadAndCancelsItsRetry() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                Globals.getInstance().mGameState = Globals.GAME_STATE_ELIMINATED;
                invoke(fixture.activity, "startReload", new Class<?>[] {int.class}, 3);
                int writes = fixture.service(secondary).writes.size();
                advance(2_000);
                assertEquals("An ordinary retry survived elimination", writes,
                        fixture.service(secondary).writes.size());
                assertEquals(3, fixture.state(secondary));
                fixture.assertLatestCommand(secondary, 2);
                assertNull(fixture.timer(secondary));
            }
        }
    }

    @Test public void reloadWaitThatFinishesWhileBlockedCanResumeWithoutAnotherButtonPress() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                invoke(fixture.activity, "finishUnblockedReloads", new Class<?>[0]);
                assertEquals("Recovery bypassed an unfinished reload wait", 1,
                        fixture.service(secondary).writes.size());
                long[] powers = (long[]) get(fixture.activity, "mPowerupUntil");
                powers[Powerups.EMP] = SystemClock.elapsedRealtime() + 10_000;
                advance(RELOAD_WAIT_MS);
                fixture.finishWait(secondary);
                assertEquals("A blocked reload enabled the gun", 1, fixture.service(secondary).writes.size());
                assertEquals(1, fixture.state(secondary));
                assertNull(fixture.timer(secondary));
                assertEquals(true, get(fixture.activity,
                        secondary ? "mSecondaryReloadWaitFinished" : "mReloadWaitFinished"));
                invoke(fixture.activity, "finishUnblockedReloads", new Class<?>[0]);
                assertEquals("Recovery enabled a gun while EMP was active", 1,
                        fixture.service(secondary).writes.size());
                powers[Powerups.EMP] = 0;
                // This is the real periodic pulse/resume recovery entry point.
                invoke(fixture.activity, "recoverCompletedReloadWrites", new Class<?>[0]);
                fixture.assertLatestCommand(secondary, 4);
                assertEquals(2, fixture.service(secondary).writes.size());
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
                assertTrue(fixture.service(!secondary).writes.isEmpty());
            }
        }
    }

    @Test public void retryExhaustionStopsAutomaticallyAndAllowsANewManualReload() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            for (boolean refill : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture()) {
                    fixture.start(secondary);
                    if (refill) {
                        fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                        advance(RELOAD_WAIT_MS);fixture.finishWait(secondary);
                    }
                    for (int retry = 0; retry < 2; retry++) {
                        byte[] previous = fixture.latest(secondary).clone();
                        int writes = fixture.service(secondary).writes.size();
                        fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                        advance(250);
                        assertEquals("The bounded automatic retry was not sent", writes + 1,
                                fixture.service(secondary).writes.size());
                        assertRetryPayload(previous, fixture.latest(secondary));
                    }
                    int writes = fixture.service(secondary).writes.size();
                    fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                    advance(2_000);
                    assertEquals("Retries continued after their limit", writes,
                            fixture.service(secondary).writes.size());
                    assertEquals("Exhausting retries prevented a manual reload", 0, fixture.state(secondary));
                    assertNull(fixture.pending(secondary));
                    assertNull(fixture.timer(secondary));
                    fixture.start(secondary);
                    assertEquals(writes + 1, fixture.service(secondary).writes.size());
                    fixture.assertLatestCommand(secondary, 2);
                    assertEquals(1, fixture.state(secondary));
                }
            }
        }
    }

    @Test public void completionForFailedOldCommandCannotAdvanceItsReplacement() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                byte[] stale = fixture.latest(secondary).clone();
                fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                // Other commands can wrap the four-bit sequence before retry.
                set(fixture.activity, secondary ? "mSecondaryCommandID" : "mCommandID", stale[0]);
                advance(250);
                byte[] retry = fixture.latest(secondary).clone();
                assertRetryPayload(stale, retry);
                fixture.complete(secondary, stale, BluetoothGatt.GATT_SUCCESS);
                assertEquals(1, fixture.state(secondary));
                assertArrayEquals("The old command consumed its replacement's pending ACK", retry,
                        fixture.pending(secondary));
                assertNull("The old command started a reload timer", fixture.timer(secondary));
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertNotNull(fixture.timer(secondary));
                advance(RELOAD_WAIT_MS);fixture.finishWait(secondary);
                fixture.assertLatestCommand(secondary, 4);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
            }
        }
    }

    @Test public void duplicateFailureAcknowledgementsDoNotConsumeTheRetryBudget() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.start(secondary);
                for (int retry = 0; retry < 2; retry++) {
                    byte[] failed = fixture.latest(secondary).clone();
                    int writes = fixture.service(secondary).writes.size();
                    for (int duplicate = 0; duplicate < 4; duplicate++)
                        fixture.complete(secondary, failed, BluetoothGatt.GATT_FAILURE);
                    assertEquals("Duplicate failures immediately resent the command", writes,
                            fixture.service(secondary).writes.size());
                    advance(250);
                    assertEquals("Duplicate failures exhausted the retry budget", writes + 1,
                            fixture.service(secondary).writes.size());
                    assertRetryPayload(failed, fixture.latest(secondary));
                    assertEquals(1, fixture.state(secondary));
                }
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                advance(RELOAD_WAIT_MS);fixture.finishWait(secondary);
                fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                assertEquals(0, fixture.state(secondary));
                assertEquals(30, fixture.shots(secondary));
            }
        }
    }

    @Test public void empAndRoundEndCannotBeOvertakenByAnOrdinaryReloadRetry() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) {
            for (boolean emp : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture()) {
                    fixture.start(secondary);
                    fixture.completeLatest(secondary, BluetoothGatt.GATT_SUCCESS);
                    advance(RELOAD_WAIT_MS);fixture.finishWait(secondary);
                    byte[] staleRefill = fixture.latest(secondary).clone();
                    fixture.completeLatest(secondary, BluetoothGatt.GATT_FAILURE);
                    if (emp) {
                        ((long[]) get(fixture.activity, "mPowerupUntil"))[Powerups.EMP]
                                = SystemClock.elapsedRealtime() + 10_000;
                    } else {
                        set(fixture.activity, "mClientRoundEndPending", true);
                    }
                    invoke(fixture.activity, "startReload", new Class<?>[] {int.class}, 3);
                    int writes = fixture.service(secondary).writes.size();
                    fixture.complete(secondary, staleRefill, BluetoothGatt.GATT_SUCCESS);
                    advance(2_000);
                    assertEquals("A retry bypassed a protected disable", writes,
                            fixture.service(secondary).writes.size());
                    assertEquals(3, fixture.state(secondary));
                    fixture.assertLatestCommand(secondary, 2);
                    assertNull(fixture.timer(secondary));
                }
            }
        }
    }

    private static void assertRetryPayload(byte[] original, byte[] retry) {
        assertNotEquals("A retry reused an ID the gun may already have ignored", original[0], retry[0]);
        byte[] expected = original.clone();expected[0] = retry[0];
        assertArrayEquals("A retry changed the requested magazine or gun identity", expected, retry);
    }

    private static final class Fixture implements AutoCloseable {
        final BareActivity activity = new BareActivity();
        final RecordingBluetoothService primary = new RecordingBluetoothService();
        final RecordingBluetoothService secondary = new RecordingBluetoothService();
        final Object previousGlobals;
        final byte previousCommandId;

        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            previousGlobals = singleton.get(null);singleton.set(null, null);
            previousCommandId = (byte) get(activity, "mCommandID");
            Globals globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mBossMode = true;globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            globals.mFullReload = 30;globals.mReloadTime = RELOAD_WAIT_MS;
            globals.mReloadOnEmpty = false;globals.mUseGPS = false;
            globals.mInfectionMode = false;globals.mPowerupQrRequired = 0;
            set(activity, "mBluetoothLeService", primary);
            set(activity, "mSecondaryBluetoothLeService", secondary);
            set(activity, "mCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mSecondaryCommandCharacteristic", characteristic(GattAttributes.RECOIL_COMMAND_UUID));
            set(activity, "mDeviceAddress", "02:00:00:00:00:01");
            set(activity, "mSecondaryDeviceAddress", "02:00:00:00:00:02");
            set(activity, "mConnected", true);set(activity, "mSecondaryConnected", true);
            set(activity, "mCommunicating", true);set(activity, "mSecondaryCommunicating", true);
            set(activity, "mGattReceiverRegistered", true);
            set(activity, "mReloading", 0);set(activity, "mSecondaryReloading", 0);
            set(activity, "mLastShotCount", (byte) 5);set(activity, "mSecondaryLastShotCount", (byte) 7);
            set(activity, "mShotsRemainingTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mReloadBar", new ProgressBar(RuntimeEnvironment.getApplication()));
            set(activity, "mHealthLabelTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mLobbyHealthLabelLayoutParams", new RelativeLayout.LayoutParams(100, 40));
        }

        void start(boolean second) throws Exception {
            if (second) invoke(activity, "startSecondaryReload", new Class<?>[] {int.class}, 1);
            else invoke(activity, "startReload", new Class<?>[0]);
        }
        RecordingBluetoothService service(boolean second) { return second ? secondary : primary; }
        int state(boolean second) throws Exception {
            return (int) get(activity, second ? "mSecondaryReloading" : "mReloading");
        }
        int shots(boolean second) throws Exception {
            return ((byte) get(activity, second ? "mSecondaryLastShotCount" : "mLastShotCount")) & 255;
        }
        CountDownTimer timer(boolean second) throws Exception {
            return (CountDownTimer) get(activity, second ? "mSecondaryReloadTimer" : "mReloadTimer");
        }
        void finishWait(boolean second) throws Exception {
            CountDownTimer waiting = timer(second);assertNotNull(waiting);
            waiting.cancel();waiting.onFinish();
        }
        byte[] pending(boolean second) throws Exception {
            return (byte[]) get(activity, second ? "mSecondaryPendingReloadCommand" : "mPendingReloadCommand");
        }
        byte[] latest(boolean second) { List<byte[]> writes = service(second).writes;return writes.get(writes.size() - 1); }
        void assertLatestCommand(boolean second, int command) {
            assertEquals(command, latest(second)[2] & 255);
            assertEquals(Globals.BOSS_PLAYER_ID, latest(second)[4]);
        }
        void completeLatest(boolean second, int status) throws Exception {
            complete(second, latest(second), status);
        }
        void complete(boolean second, byte[] command, int status) throws Exception {
            Intent event = new Intent(BluetoothLeService.CHARACTERISTIC_WRITE_FINISHED)
                    .putExtra(BluetoothLeService.EXTRA_WEAPON_SLOT, second ? 1 : 0)
                    .putExtra(BluetoothLeService.EXTRA_UUID, GattAttributes.RECOIL_COMMAND_UUID)
                    .putExtra(BluetoothLeService.EXTRA_STATUS, status)
                    .putExtra(BluetoothLeService.EXTRA_COMPLETED_AT, SystemClock.elapsedRealtime())
                    .putExtra(BluetoothLeService.EXTRA_DATA, command.clone());
            ((BroadcastReceiver) get(activity, "mGattUpdateReceiver")).onReceive(activity, event);
        }
        @Override public void close() throws Exception {
            for (Field field : FullscreenActivity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
                else if (value instanceof CountDownTimer) ((CountDownTimer) value).cancel();
            }
            set(activity, "mCommandID", previousCommandId);
            primary.onDestroy();secondary.onDestroy();
            Field singleton = Globals.class.getDeclaredField("mInstance");singleton.setAccessible(true);
            singleton.set(null, previousGlobals);
        }
    }

    private static final class BareActivity extends FullscreenActivity {
        @Override public <T extends View> T findViewById(int id) { return null; }
    }

    private static final class RecordingBluetoothService extends BluetoothLeService {
        final List<byte[]> writes = new ArrayList<>();
        @Override public synchronized void writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] value) {
            writes.add(value.clone());
        }
        @Override public synchronized boolean isConnected() { return true; }
    }

    private static BluetoothGattCharacteristic characteristic(String uuid) {
        return new BluetoothGattCharacteristic(UUID.fromString(uuid), BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
    }
    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(target, value);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = FullscreenActivity.class.getDeclaredMethod(name, types);method.setAccessible(true);method.invoke(target, args);
    }
}
