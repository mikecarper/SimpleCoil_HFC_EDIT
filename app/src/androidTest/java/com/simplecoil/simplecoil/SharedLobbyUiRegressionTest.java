package com.simplecoil.simplecoil;

import android.graphics.Rect;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SharedLobbyUiRegressionTest {
    @Test public void pairingInstructionsAndCancelAreOnTheVisibleLobby() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mCommunicating", false);
                set(activity, "mConnected", true);
                set(activity, "mWeaponSyncTriggerHintShown", true);
                invoke(activity, "renderLobby");
                assertEquals(activity.getString(R.string.connect_status_hold_trigger),
                        ((TextView) activity.findViewById(R.id.lobby_gun_status)).getText().toString());
                assertEquals(activity.getString(R.string.cancel),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                assertTrue(activity.findViewById(R.id.lobby_primary_button).isEnabled());
                activity.findViewById(R.id.lobby_primary_button).performClick();
                assertFalse((Boolean) get(activity, "mConnected"));
                assertEquals(activity.getString(R.string.lobby_gun_action),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
            });
        }
    }

    @Test public void thirtyTwoPlayersAreGroupedAndSittingOutCannotStart() {
        Globals g = Globals.getInstance();
        boolean oldBench = g.mLocalLobbyBenched;
        byte oldID = g.mPlayerID;
        Map<Byte, LobbyPlayer> oldPlayers = g.mLobbyPlayers;
        g.mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", true);
                set(activity, "mReady", true);
                set(activity, "mLobbyJoined", true);
                set(activity, "mIsServer", false);
                set(activity, "mCommunicating", false);
                g.mGameMode = Globals.GAME_MODE_2TEAMS;
                g.mPlayerID = 1;
                g.mLocalLobbyBenched = true;
                Map<Byte, LobbyPlayer> players = new HashMap<>();
                for (byte id = 1; id <= 32; id++)
                    players.put(id, new LobbyPlayer(id, "Player", true, true, true, id == 1, id == 17));
                g.mLobbyPlayers = players;
                invoke(activity, "renderLobby");
                assertEquals(35, ((LinearLayout) activity.findViewById(R.id.lobby_roster)).getChildCount());
                assertFalse(activity.findViewById(R.id.lobby_primary_button).isEnabled());
                assertEquals(activity.getString(R.string.lobby_benched),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.lobby_roster_title)).getText()
                        .toString().contains("31"));
            });
        } finally {
            g.mLocalLobbyBenched = oldBench;
            g.mPlayerID = oldID;
            g.mLobbyPlayers = oldPlayers;
        }
    }

    @Test public void lobbyWorksBeforeGunPairingAndKeepsActionVisible() {
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        try (ActivityScenario<FullscreenActivity> scenario = ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue((Boolean) get(activity, "mUseNetwork"));
                ((Handler) get(activity, "mLobbyHandler")).removeCallbacksAndMessages(null);
                invoke(activity, "leaveSharedLobby");
                set(activity, "mUseNetwork", false);
                set(activity, "mCommunicating", false);
                invoke(activity, "renderLobby");
                assertEquals(View.VISIBLE, activity.findViewById(R.id.lobby_panel).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.connect_layout).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.play_layout).getVisibility());
                assertEquals(activity.getString(R.string.lobby_gun_action),
                        ((TextView) activity.findViewById(R.id.lobby_primary_button)).getText().toString());
            });
            scenario.onActivity(activity -> {
                View primary = activity.findViewById(R.id.lobby_primary_button);
                Rect visible = new Rect();
                assertTrue("Main action is off screen", primary.getGlobalVisibleRect(visible));
                assertEquals("Main action is clipped", primary.getHeight(), visible.height());
                View dock = activity.findViewById(R.id.lobby_action_bar);
                assertEquals(dock.getHeight(), activity.findViewById(R.id.scrollView).getPaddingBottom());
                Globals.getInstance().mGameState = Globals.GAME_STATE_RUNNING;
                invoke(activity, "renderLobby");
                assertEquals(View.GONE, dock.getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.play_layout).getVisibility());
                assertEquals(0, activity.findViewById(R.id.scrollView).getPaddingBottom());
                Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
                invoke(activity, "renderLobby");
                assertEquals(View.VISIBLE, dock.getVisibility());
            });
        } finally { Globals.getInstance().mGameState = Globals.GAME_STATE_NONE; }
    }

    private static Object get(Object target, String name) {
        try { Field f = FullscreenActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field f = FullscreenActivity.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void invoke(Object target, String name) {
        try { Method m = FullscreenActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
}
