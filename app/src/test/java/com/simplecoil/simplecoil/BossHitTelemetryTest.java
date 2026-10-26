package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowTextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/** Real telemetry damage/counter handling with two boss guns and no radio, network or Activity UI. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class BossHitTelemetryTest {
    @Test public void anIdleOtherGunCannotReplayEitherGunsCachedHit() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) try (Fixture fixture = new Fixture()) {
            byte[] hit = packet(17, 1, 0, 0);
            fixture.receive(hit, secondary);
            fixture.assertDamage(95, 1);
            fixture.receive(hit, secondary);
            fixture.receive(packet(0, 0, 0, 0), !secondary);
            fixture.receive(hit, secondary);
            fixture.assertDamage(95, 1);
        }
    }

    @Test public void alternatingDifferentHitsRetainEachGunsHistoryForBothPacketChannels() throws Exception {
        for (boolean secondChannel : new boolean[] {false, true}) try (Fixture fixture = new Fixture()) {
            byte[] primary = secondChannel ? packet(0, 0, 17, 1) : packet(17, 1, 0, 0);
            byte[] secondary = secondChannel ? packet(0, 0, 18, 2) : packet(18, 2, 0, 0);
            fixture.receive(primary, false);
            fixture.receive(secondary, true);
            fixture.assertDamage(90, 2);
            for (int repeat = 0; repeat < 4; repeat++) {
                advance(1_000);
                fixture.receive(primary, false);
                fixture.receive(secondary, true);
                fixture.assertDamage(90, 2);
            }
        }
    }

    @Test public void crossGunDuplicateRemainsRememberedAfterItsShortWindowExpires() throws Exception {
        for (boolean firstSecondary : new boolean[] {false, true}) try (Fixture fixture = new Fixture()) {
            byte[] hit = packet(17, 1, 17, 1);
            fixture.receive(hit, firstSecondary);
            fixture.receive(hit, !firstSecondary);
            fixture.assertDamage(95, 1);
            for (int repeat = 0; repeat < 3; repeat++) {
                advance(1_000);
                // The receiving gun remembers a suppressed physical hit, too.
                fixture.receive(hit, !firstSecondary);
                fixture.receive(packet(0, 0, 0, 0), firstSecondary);
                fixture.receive(hit, !firstSecondary);
                fixture.assertDamage(95, 1);
            }
        }
    }

    @Test @Config(shadows = ModeLabel.class)
    public void theSecondGunsFirstTelemetryCannotRepeatAnAlreadyReceivedHit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            set(fixture.activity, "mSecondaryCommunicating", false);
            byte[] hit = packet(17, 1, 0, 0);
            fixture.receive(hit, false);
            fixture.assertDamage(95, 1);
            fixture.receive(hit, true);
            fixture.assertDamage(95, 1);
            advance(1_000);
            fixture.receive(hit, true);
            fixture.assertDamage(95, 1);
        }
    }

    @Test public void sameGunIdleAndNewShotIdsStillAllowRealHits() throws Exception {
        for (boolean secondary : new boolean[] {false, true}) try (Fixture fixture = new Fixture()) {
            fixture.receive(packet(17, 1, 0, 0), secondary);
            fixture.receive(packet(17, 2, 0, 0), secondary);
            fixture.assertDamage(90, 2);
            fixture.receive(packet(0, 0, 0, 0), secondary);
            fixture.receive(packet(17, 2, 0, 0), secondary);
            fixture.assertDamage(85, 3);
            fixture.receive(packet(17, 2, 0, 0), secondary);
            fixture.assertDamage(85, 3);
        }
    }

    @Test public void grenadeHistoryPreservesFullEventBytesOnBothGuns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] first = grenade(0x91), different = grenade(0x11);
            fixture.receive(first, false);
            fixture.receive(first, true);
            advance(1_000);
            fixture.receive(first, true);
            fixture.assertDamage(95, 1);
            fixture.receive(different, true);
            fixture.assertDamage(90, 2);
            fixture.receive(packet(0, 0, 0, 0), false);
            fixture.receive(different, true);
            fixture.assertDamage(90, 2);
        }
    }

    @Test public void aNewRoundClearsBothGunsAndTheCrossGunHistory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] primary = packet(17, 1, 0, 0), secondary = packet(18, 2, 0, 0);
            fixture.receive(primary, false);
            fixture.receive(secondary, true);
            fixture.assertDamage(90, 2);
            invoke(fixture.activity, "resetRoundTelemetryState", new Class<?>[0]);
            fixture.receive(primary, false);
            fixture.receive(secondary, true);
            fixture.assertDamage(80, 2);
            invoke(fixture.activity, "resetRoundTelemetryState", new Class<?>[0]);
            // The previous round saw this same shot on the opposite gun.
            fixture.receive(primary, true);
            fixture.assertDamage(75, 1);
        }
    }

    @Test public void hitFilteringKeepsMagazineAndButtonCountersIndependent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] primary = packet(17, 1, 0, 0), secondary = packet(17, 1, 0, 0);
            primary[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 29;
            primary[FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 1;
            secondary[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 28;
            secondary[FullscreenActivity.RECOIL_OFFSET_RELOAD_TRIGGER_COUNTER] = 2;
            fixture.receive(primary, false);
            fixture.receive(secondary, true);
            advance(1_000);
            fixture.receive(primary, false);
            fixture.receive(secondary, true);
            fixture.assertDamage(95, 1);
            assertEquals((byte) 29, get(fixture.activity, "mLastShotCount"));
            assertEquals((byte) 28, get(fixture.activity, "mSecondaryLastShotCount"));
            assertEquals((byte) 1, get(fixture.activity, "mLastTriggerCount"));
            assertEquals((byte) 2, get(fixture.activity, "mSecondaryLastTriggerCount"));
            assertEquals(3, get(fixture.activity, "mRoundShots"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BareActivity activity = new BareActivity();
        final Object previousGlobals;

        Fixture() throws Exception {
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);
            previousGlobals = singleton.get(null);
            singleton.set(null, null);
            Globals globals = Globals.getInstance();
            globals.mBossMode = true;
            globals.mPlayerID = Globals.BOSS_PLAYER_ID;
            globals.mGameState = Globals.GAME_STATE_RUNNING;
            globals.mFullHealth = 100;
            globals.mFullShields = 0;
            globals.mDamage = globals.mGrenadeDamage = -5;
            globals.mReloadOnEmpty = false;
            set(activity, "mUseNetwork", false);
            set(activity, "mStartGameTimer", false);
            set(activity, "sharedPreferences", RuntimeEnvironment.getApplication()
                    .getSharedPreferences("BossHitTelemetryTest", Context.MODE_PRIVATE));
            set(activity, "mLastTeam", Globals.BOSS_PLAYER_ID);
            set(activity, "mCommunicating", true);
            set(activity, "mSecondaryCommunicating", true);
            set(activity, "mSecondaryDeviceAddress", "02:00:00:00:00:02");
            set(activity, "mTelemetryCharacteristic", characteristic());
            set(activity, "mSecondaryTelemetryCharacteristic", characteristic());
            set(activity, "mHealth", 100);
            set(activity, "mShield", 0);
            set(activity, "mReloading", 0);
            set(activity, "mSecondaryReloading", 0);
            set(activity, "mLastShotCount", (byte) 30);
            set(activity, "mSecondaryLastShotCount", (byte) 30);
            set(activity, "mHitsTakenTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mShotsRemainingTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mShotModeTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mHealthLabelTV", new TextView(RuntimeEnvironment.getApplication()));
            set(activity, "mHealthBar", new ProgressBar(RuntimeEnvironment.getApplication()));
            set(activity, "mShieldBar", new ProgressBar(RuntimeEnvironment.getApplication()));
            set(activity, "mLobbyHealthLabelLayoutParams", new RelativeLayout.LayoutParams(100, 40));
            advance(1); // Zero is the cross-gun deduper's unset timestamp.
        }

        void receive(byte[] data, boolean secondary) throws Exception {
            invoke(activity, "processTelemetryData", new Class<?>[] {byte[].class, boolean.class}, data, secondary);
        }

        void assertDamage(int health, int hits) throws Exception {
            assertEquals(health, get(activity, "mHealth"));
            assertEquals(hits, get(activity, "mHitsTaken"));
        }

        @Override public void close() throws Exception {
            for (Field field : FullscreenActivity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(activity);
                if (value instanceof Handler) ((Handler) value).removeCallbacksAndMessages(null);
                else if (value instanceof CountDownTimer) ((CountDownTimer) value).cancel();
            }
            Field singleton = Globals.class.getDeclaredField("mInstance");
            singleton.setAccessible(true);singleton.set(null, previousGlobals);
            RuntimeEnvironment.getApplication().getSharedPreferences("BossHitTelemetryTest", Context.MODE_PRIVATE)
                    .edit().clear().commit();
        }
    }

    private static final class BareActivity extends FullscreenActivity {
        @Override public <T extends View> T findViewById(int id) { return null; }
    }

    /** The manifest-free fixture has no packaged app strings; only the mode label is cosmetic. */
    @Implements(TextView.class)
    public static class ModeLabel extends ShadowTextView {
        @RealObject private TextView view;
        @Implementation protected void setText(int resourceId) { view.setText("Shot mode"); }
    }

    private static BluetoothGattCharacteristic characteristic() {
        return new BluetoothGattCharacteristic(UUID.fromString(GattAttributes.RECOIL_TELEMETRY_UUID),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
    }

    private static byte[] packet(int firstPlayer, int firstShot, int secondPlayer, int secondShot) {
        byte[] data = new byte[20];
        data[FullscreenActivity.RECOIL_OFFSET_TEAM] = Globals.BOSS_PLAYER_ID;
        data[FullscreenActivity.RECOIL_OFFSET_SHOTS_REMAINING] = 30;
        data[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) (firstPlayer << 2);
        data[FullscreenActivity.RECOIL_OFFSET_HIT_BY1_SHOTID] = (byte) firstShot;
        data[FullscreenActivity.RECOIL_OFFSET_HIT_BY2] = (byte) (secondPlayer << 2);
        data[FullscreenActivity.RECOIL_OFFSET_HIT_BY2_SHOTID] = (byte) secondShot;
        return data;
    }

    private static byte[] grenade(int shot) {
        byte[] data = packet(0, shot, 0, 0);
        data[FullscreenActivity.RECOIL_OFFSET_HIT_BY1] = (byte) Globals.GRENADE_PLAYER_ID;
        return data;
    }

    private static void advance(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = FullscreenActivity.class.getDeclaredField(name);field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = FullscreenActivity.class.getDeclaredMethod(name, types);method.setAccessible(true);
        try { method.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }
}
