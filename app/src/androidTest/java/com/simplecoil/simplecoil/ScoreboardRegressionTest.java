package com.simplecoil.simplecoil;

import android.app.Activity;
import android.content.Context;
import android.database.DataSetObserver;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Binds the real host and client row layouts without starting networking services. */
@RunWith(AndroidJUnit4.class)
public class ScoreboardRegressionTest {
    private RowActivity activity;
    private FrameLayout parent;
    private int originalGameMode;
    private int originalGameLimit;
    private int originalLivesLimit;

    @Before
    public void setUp() {
        onMain(() -> {
            Globals globals = Globals.getInstance();
            originalGameMode = globals.mGameMode;
            originalGameLimit = globals.mGameLimit;
            originalLivesLimit = globals.mLivesLimit;
            globals.mGameMode = Globals.GAME_MODE_FFA;
            globals.mGameLimit = Globals.GAME_LIMIT_LIVES;
            globals.mLivesLimit = 5;
            activity = new RowActivity();
            parent = new FrameLayout(activity);
        });
    }

    @After
    public void tearDown() {
        onMain(() -> {
            Globals globals = Globals.getInstance();
            globals.mGameMode = originalGameMode;
            globals.mGameLimit = originalGameLimit;
            globals.mLivesLimit = originalLivesLimit;
        });
    }

    @Test
    public void replacingDataUpdatesItemLookupAndRenderingTogether() {
        onMain(() -> {
            PlayerDisplayDataListAdapter adapter = adapter(player("Original", 1), false);
            PlayerDisplayData replacement = player("Replacement", 7);
            adapter.setData(new PlayerDisplayData[]{null, replacement});
            assertEquals(2, adapter.getCount());
            assertSame(replacement, adapter.getItem(1));
            View row = adapter.getView(1, null, parent);
            assertEquals("Replacement", text(row, R.id.player_name_tv));
            assertEquals("7", text(row, R.id.player_points_tv));
        });
    }

    @Test
    public void replacingDataNotifiesOnceWithTheCompleteNewDataset() {
        onMain(() -> {
            PlayerDisplayDataListAdapter adapter = adapter(player("Original", 1), false);
            PlayerDisplayData replacement = player("Replacement", 7);
            int[] changes = {0};
            adapter.registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() {
                    changes[0]++;
                    assertEquals(3, adapter.getCount());
                    assertSame(replacement, adapter.getItem(2));
                }
            });
            adapter.setData(new PlayerDisplayData[]{null, null, replacement});
            assertEquals(1, changes[0]);
        });
    }

    @Test
    public void refreshingTheSameSourceArrayReplacesItsPlayerObjects() {
        onMain(() -> {
            PlayerDisplayData[] source = {null, player("Original", 1)};
            PlayerDisplayDataListAdapter adapter = new PlayerDisplayDataListAdapter(activity, source, false);
            source[1] = player("Replacement", 9);
            adapter.setData(source);
            assertSame(source[1], adapter.getItem(1));
            assertEquals("9", text(adapter.getView(1, null, parent), R.id.player_points_tv));
        });
    }

    @Test
    public void clearingDataDoesNotLeaveStaleRowsInTheAdapter() {
        onMain(() -> {
            PlayerDisplayDataListAdapter adapter = adapter(player("Original", 1), false);
            adapter.setData(new PlayerDisplayData[0]);
            assertEquals(0, adapter.getCount());
            assertTrue(adapter.isEmpty());
        });
    }

    @Test
    public void hostGlobalLivesCannotBecomeNegative() { assertExhaustedLives(false, false); }

    @Test
    public void clientGlobalLivesCannotBecomeNegative() { assertExhaustedLives(true, false); }

    @Test
    public void hostPersonalLivesCannotBecomeNegative() { assertExhaustedLives(false, true); }

    @Test
    public void clientPersonalLivesCannotBecomeNegative() { assertExhaustedLives(true, true); }

    private void assertExhaustedLives(boolean isClient, boolean personalLimit) {
        onMain(() -> {
            PlayerDisplayData player = player("Player", 0);
            player.eliminated = 7;
            player.overrideLives = personalLimit;
            player.lives = 3;
            assertEquals("0", text(adapter(player, isClient).getView(1, null, parent), R.id.player_eliminated_tv));
        });
    }

    @Test
    public void personalUnlimitedLivesStillDisplayEliminations() {
        onMain(() -> {
            PlayerDisplayData player = player("Player", 0);
            player.eliminated = 7;
            player.overrideLives = true;
            player.lives = 0;
            assertLivesTextForBothLayouts(player, "7");
        });
    }

    @Test
    public void personalLifeLimitStillOverridesTheGlobalLimit() {
        onMain(() -> {
            PlayerDisplayData player = player("Player", 0);
            player.eliminated = 2;
            player.overrideLives = true;
            player.lives = 10;
            assertLivesTextForBothLayouts(player, "8");
        });
    }

    @Test
    public void globalUnlimitedLivesStillDisplayEliminations() {
        onMain(() -> {
            Globals.getInstance().mGameLimit = Globals.GAME_LIMIT_NONE;
            PlayerDisplayData player = player("Player", 0);
            player.eliminated = 7;
            assertLivesTextForBothLayouts(player, "7");
        });
    }

    @Test
    public void refreshKeepsHeadersMissingPlayersAndTeamTotalsIntact() {
        onMain(() -> {
            for (boolean isClient : new boolean[]{false, true}) {
                PlayerDisplayDataListAdapter adapter = adapter(player("Original", 1), isClient);
                PlayerDisplayData[] replacement = new PlayerDisplayData[Globals.MAX_PLAYER_ID + 2];
                replacement[Globals.MAX_PLAYER_ID + 1] = player("Team 1: 10, Team 2: 20", 0);
                adapter.setData(replacement);
                assertEquals(replacement.length, adapter.getCount());
                View header = adapter.getView(0, null, parent);
                assertEquals(activity.getString(R.string.player_list_id_label), text(header, R.id.player_id_tv));
                assertEquals(activity.getString(R.string.game_limit_lives), text(header, R.id.player_eliminated_tv));
                View empty = adapter.getView(1, null, parent);
                assertEquals(activity.getString(R.string.player_name_not_connected), text(empty, R.id.player_name_tv));
                assertEquals("", text(empty, R.id.player_points_tv));
                assertEquals("", text(empty, R.id.player_eliminated_tv));
                View footer = adapter.getView(Globals.MAX_PLAYER_ID + 1, null, parent);
                assertEquals("Team 1: 10, Team 2: 20", text(footer, R.id.player_id_tv));
            }
        });
    }

    private void assertLivesTextForBothLayouts(PlayerDisplayData player, String expected) {
        for (boolean isClient : new boolean[]{false, true})
            assertEquals(expected, text(adapter(player, isClient).getView(1, null, parent), R.id.player_eliminated_tv));
    }

    private PlayerDisplayDataListAdapter adapter(PlayerDisplayData player, boolean isClient) {
        return new PlayerDisplayDataListAdapter(activity, new PlayerDisplayData[]{null, player}, isClient);
    }

    private static PlayerDisplayData player(String name, int points) {
        PlayerDisplayData data = new PlayerDisplayData();
        data.playerID = 1;
        data.playerName = name;
        data.points = points;
        return data;
    }

    private static String text(View row, int id) {
        return ((TextView) row.findViewById(id)).getText().toString();
    }

    private static void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class RowActivity extends Activity {
        RowActivity() {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            attachBaseContext(new ContextThemeWrapper(context, R.style.AppTheme));
        }

        @Override public LayoutInflater getLayoutInflater() {
            return LayoutInflater.from(getBaseContext());
        }

        @Override public Object getSystemService(String name) {
            return getBaseContext().getSystemService(name);
        }
    }
}
