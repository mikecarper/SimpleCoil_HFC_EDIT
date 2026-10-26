package com.simplecoil.simplecoil;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Real lobby button policy and scanner-entry guard, without camera or pinning side effects. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class EventModeLateJoinUiTest {
    private static final int[] ORGANIZER_CONTROLS = {R.id.lobby_team_button, R.id.lobby_mode_button,
            R.id.lobby_rules_button, R.id.lobby_host_button};
    private static final String[] GAME_CONTROLS = {"mStartGameButton", "mEndGameButton",
            "mEndNetworkGameButton", "mPlayerSettingsButton"};

    @Test public void restrictedRunningRoundObserverCanUseBothQrJoinControls() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // Observing a running round can already have locked this host. It
            // must not require an organizer unlock to make the QR join request.
            set(fixture.activity, "mMatchSession", new MatchSessionLock("192.0.2.1", null));
            fixture.globals.mLobbyRoundActive = true;
            fixture.assertJoinAllowed();
        }
    }

    @Test public void restrictedWaitingPhoneCanJoinEvenBeforeTheNextPlayingBeacon() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.globals.mLobbyRoundActive = false;
            fixture.globals.mWaitingForLobbyRoundEnd = true;
            fixture.assertJoinAllowed();
        }
    }

    @Test public void ordinaryEventLobbyCannotOpenTheManualTeamScanner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.assertJoinBlocked();
            assertFalse((boolean) invoke(fixture.activity, "assignTeamFromQrCode",
                    new Class<?>[]{String.class}, "SIMPLECOIL:RESPAWN:2"));
            assertTrue("Event Mode was bypassed", fixture.restricted());
        }
    }

    @Test public void liveJoinWaitsForTheGunAndCurrentJoinToBeReady() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.globals.mLobbyRoundActive = true;
            BluetoothGattCharacteristic command = (BluetoothGattCharacteristic) get(
                    FullscreenActivity.class, fixture.activity, "mCommandCharacteristic");
            set(fixture.activity, "mCommandCharacteristic", null);
            fixture.assertJoinBlocked();
            set(fixture.activity, "mCommandCharacteristic", command);
            set(fixture.activity, "mCommunicating", false);
            fixture.applyPolicy();
            assertFalse(fixture.activity.findViewById(R.id.lobby_scan_button).isEnabled());
            assertTrue("Gun pairing was blocked by Event Mode",
                    fixture.activity.findViewById(R.id.lobby_primary_button).isEnabled());
            fixture.assertScannerBlocked();
            set(fixture.activity, "mCommunicating", true);
            set(fixture.activity, "mLobbyTakeoverPending", true);
            fixture.assertJoinBlocked();
            set(fixture.activity, "mLobbyTakeoverPending", false);
            fixture.globals.mRequestedJoinTeam = 1;
            fixture.assertJoinBlocked();
            fixture.globals.mRequestedJoinTeam = 0;
            fixture.globals.mGameState = Globals.GAME_STATE_RUNNING;
            fixture.assertJoinBlocked();
            fixture.globals.mGameState = Globals.GAME_STATE_NONE;
            set(fixture.activity, "mUseNetwork", false);
            fixture.assertJoinBlocked();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Object previousGlobals;
        final RecordingActivity activity;
        final Globals globals;
        final EventKioskController policy;
        final TcpClient client;
        final TcpServer server;
        final UDPListenerService udp;

        Fixture() throws Exception {
            previousGlobals = get(Globals.class, null, "mInstance");
            put(Globals.class, null, "mInstance", null);
            globals = Globals.getInstance();
            globals.mGameState = Globals.GAME_STATE_NONE;
            activity = new RecordingActivity();
            policy = new EventKioskController(activity, () -> { });
            globals.mEventMode = true;
            globals.mEventPinning = true;
            globals.mEventRevision = "test-policy";
            globals.mHostPlayerIdentity = UUID.randomUUID().toString();
            put(EventKioskController.class, policy, "enabled", true);
            put(EventKioskController.class, policy, "pinning", true);
            put(EventKioskController.class, policy, "revision", globals.mEventRevision);
            put(EventKioskController.class, policy, "identity", globals.mHostPlayerIdentity);
            set(activity, "mEventKiosk", policy);
            set(activity, "mUseNetwork", true);
            set(activity, "mCommunicating", true);
            set(activity, "mCommandCharacteristic", new BluetoothGattCharacteristic(UUID.randomUUID(),
                    BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
            // Call the actual scanner-entry path while paused. It must request
            // opening on resume, without needing a physical camera or permission dialog.
            set(activity, "mActivityResumed", false);
            client = new TcpClient();server = new TcpServer();udp = new UDPListenerService();
            set(activity, "mTcpClient", client);
            set(activity, "mTcpServer", server);
            set(activity, "mUDPListenerService", udp);
            for (String field : GAME_CONTROLS) set(activity, field, new Button(RuntimeEnvironment.getApplication()));
        }

        void applyPolicy() throws Exception {
            activity.controls.values().forEach(view -> view.setEnabled(true));
            invoke(activity, "syncEventControls");
            assertTrue(restricted());
            invoke(activity, "applyRestrictedLobbyControls");
            for (int id : ORGANIZER_CONTROLS)
                assertFalse("Organizer control was unlocked", activity.findViewById(id).isEnabled());
            for (String field : GAME_CONTROLS)
                assertFalse("Local game control was unlocked", ((View) get(FullscreenActivity.class, activity, field)).isEnabled());
            assertTrue(globals.mEventMode);
            assertTrue(globals.mEventPinning);
        }

        void assertJoinAllowed() throws Exception {
            applyPolicy();
            assertTrue(activity.findViewById(R.id.lobby_primary_button).isEnabled());
            assertTrue(activity.findViewById(R.id.lobby_scan_button).isEnabled());
            invoke(activity, "openTeamAssignmentQrScanner");
            assertTrue("The real QR entry guard still rejected an Event Mode late join",
                    (boolean) get(FullscreenActivity.class, activity, "mTeamAssignmentQrOpenWhenResumed"));
            assertTrue("Late join changed the organizer restriction", restricted());
        }

        void assertJoinBlocked() throws Exception {
            applyPolicy();
            assertFalse(activity.findViewById(R.id.lobby_primary_button).isEnabled());
            assertFalse(activity.findViewById(R.id.lobby_scan_button).isEnabled());
            assertScannerBlocked();
        }

        void assertScannerBlocked() throws Exception {
            set(activity, "mTeamAssignmentQrOpenWhenResumed", false);
            invoke(activity, "openTeamAssignmentQrScanner");
            assertFalse((boolean) get(FullscreenActivity.class, activity, "mTeamAssignmentQrOpenWhenResumed"));
        }

        boolean restricted() throws Exception { return (boolean) invoke(activity, "eventControlsRestricted"); }

        @Override public void close() throws Exception {
            client.onDestroy();server.onDestroy();udp.onDestroy();
            put(Globals.class, null, "mInstance", previousGlobals);
        }
    }

    private static final class RecordingActivity extends FullscreenActivity {
        final Map<Integer, View> controls = new HashMap<>();
        RecordingActivity() {
            for (int id : ORGANIZER_CONTROLS) controls.put(id, new Button(RuntimeEnvironment.getApplication()));
            controls.put(R.id.lobby_primary_button, new Button(RuntimeEnvironment.getApplication()));
            controls.put(R.id.lobby_scan_button, new Button(RuntimeEnvironment.getApplication()));
        }
        @Override public boolean hasWindowFocus() { return false; }
        @Override public Object getSystemService(String name) {
            return RuntimeEnvironment.getApplication().getSystemService(name);
        }
        @Override @SuppressWarnings("unchecked") public <T extends View> T findViewById(int id) {
            return (T) controls.get(id);
        }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return RuntimeEnvironment.getApplication().getSharedPreferences("EventModeLateJoinUiTest", Context.MODE_PRIVATE);
        }
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = FullscreenActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        put(FullscreenActivity.class, target, name, value);
    }
    private static Object get(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void put(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
