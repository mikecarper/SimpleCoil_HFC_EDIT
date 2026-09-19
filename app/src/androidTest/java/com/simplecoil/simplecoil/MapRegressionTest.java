package com.simplecoil.simplecoil;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mousebird.maply.ComponentObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Exercises the real map controller with cached locations and controlled callbacks. */
@RunWith(AndroidJUnit4.class)
public class MapRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private FullscreenActivity activity;
    private MapFragment map;
    private boolean originalUseGPS;
    private byte originalPlayerID;
    private int originalGameMode;
    private int originalGPSMode;
    private int originalGameState;
    private Map<Byte, Globals.GPSData> originalLocations;

    @Before
    public void setUp() throws Exception {
        // A sleeping API 22 device leaves the activity stopped even though
        // ActivityScenario can already invoke its callbacks. Wake it before launch.
        try (InputStream wake = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation()
                        .executeShellCommand("input keyevent KEYCODE_WAKEUP"))) {
            while (wake.read() != -1) { }
        }
        Globals globals = Globals.getInstance();
        originalUseGPS = globals.mUseGPS;
        originalPlayerID = globals.mPlayerID;
        originalGameMode = globals.mGameMode;
        originalGPSMode = globals.mGPSMode;
        originalGameState = globals.mGameState;
        Globals.getmGPSDataSemaphore();
        try {
            originalLocations = new HashMap<>(globals.mGPSData);
            globals.mGPSData.clear();
        } finally { globals.mGPSDataSemaphore.release(); }
        globals.mUseGPS = false;
        globals.mGameState = Globals.GAME_STATE_NONE;
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            current.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            current.findViewById(R.id.connect_layout).setVisibility(View.GONE);
            current.findViewById(R.id.play_layout).setVisibility(View.VISIBLE);
            current.getSupportFragmentManager().executePendingTransactions();
        });
        long deadline = SystemClock.elapsedRealtime() + 5000;
        boolean[] ready = new boolean[1];
        String[] mapState = new String[1];
        do {
            scenario.onActivity(current -> {
                activity = current;
                map = (MapFragment) current.getSupportFragmentManager().findFragmentById(R.id.map_fragment);
                ready[0] = map != null && get("mPlayerMarkers") != null;
                View view = map == null ? null : map.getView();
                mapState[0] = map == null ? "fragment missing" : view == null ? "view missing"
                        : "shown=" + view.isShown() + ", size=" + view.getWidth() + "x" + view.getHeight()
                        + ", resumed=" + map.isResumed() + ", lifecycle=" + map.getLifecycle().getCurrentState()
                        + ", attached=" + view.isAttachedToWindow() + ", parent=" + view.getParent()
                        + ", activity=" + current.getLifecycle().getCurrentState()
                        + ", window=" + current.getWindow().getDecorView().getWidth() + "x"
                        + current.getWindow().getDecorView().getHeight();
            });
            if (!ready[0]) Thread.sleep(20);
        } while (!ready[0] && SystemClock.elapsedRealtime() < deadline);
        assertTrue("Map controller did not finish starting: " + mapState[0], ready[0]);
        scenario.onActivity(current -> {
            globals.mPlayerID = 1;
            globals.mGameMode = Globals.GAME_MODE_2TEAMS;
            globals.mGPSMode = Globals.GPS_ALL;
            globals.mUseGPS = true;
            set("currentBestLocation", null);
            useDummyLocationListener();
            map.enableGPS(true);
        });
    }

    @After
    public void tearDown() {
        Globals globals = Globals.getInstance();
        globals.mUseGPS = false;
        if (scenario != null) {
            scenario.onActivity(current -> {
                if (map != null) map.enableGPS(false);
            });
            scenario.close();
        }
        globals.mUseGPS = originalUseGPS;
        globals.mPlayerID = originalPlayerID;
        globals.mGameMode = originalGameMode;
        globals.mGPSMode = originalGPSMode;
        globals.mGameState = originalGameState;
        if (originalLocations != null) {
            Globals.getmGPSDataSemaphore();
            try {
                globals.mGPSData.clear();
                globals.mGPSData.putAll(originalLocations);
            } finally { globals.mGPSDataSemaphore.release(); }
        }
    }

    @Test
    public void reenablingGpsRestoresStationaryPlayerMarkers() {
        scenario.onActivity(current -> {
            showPlayers();
            map.enableGPS(false);
            assertNull(marker(2));
            assertNull(marker(9));
            useDummyLocationListener();
            map.enableGPS(true);
            assertNotNull("Cached teammate was not restored", marker(2));
            assertNotNull("Cached enemy was not restored", marker(9));
        });
    }

    @Test
    public void teammateOnlyModeRemovesStationaryEnemiesImmediately() {
        scenario.onActivity(current -> {
            showPlayers();
            Globals.getInstance().mGPSMode = Globals.GPS_TEAMMATE;
            receive(new Intent(NetMsg.NETMSG_GPSSETTING));
            assertNotNull(marker(2));
            assertNull("Enemy remained visible after teammate-only mode was selected", marker(9));
        });
    }

    @Test
    public void allPlayersModeRestoresStationaryEnemiesImmediately() {
        scenario.onActivity(current -> {
            Globals.getInstance().mGPSMode = Globals.GPS_TEAMMATE;
            putLocation(2, 10);
            putLocation(9, 20);
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
            assertNotNull(marker(2));
            assertNull(marker(9));
            Globals.getInstance().mGPSMode = Globals.GPS_ALL;
            receive(new Intent(NetMsg.NETMSG_GPSSETTING));
            assertNotNull("Cached enemy was not restored after changing visibility", marker(9));
        });
    }

    @Test
    public void gameModeChangeRecomputesMarkerTeamsWithoutMovement() {
        scenario.onActivity(current -> {
            Globals.getInstance().mGPSMode = Globals.GPS_TEAMMATE;
            putLocation(5, 10); // A teammate in two-team mode, an enemy in four-team mode.
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
            assertNotNull(marker(5));
            Globals.getInstance().mGameMode = Globals.GAME_MODE_4TEAMS;
            receive(new Intent(NetMsg.NETMSG_LISTPLAYERS));
            assertNull("Old team assignment kept an enemy marker visible", marker(5));
        });
    }

    @Test
    public void repeatedFullRefreshDoesNotEraseStationaryPlayers() {
        scenario.onActivity(current -> {
            showPlayers();
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE).putExtra(NetMsg.INTENT_FULLUPDATE, true));
            assertNotNull(marker(2));
            assertNotNull(marker(9));
        });
    }

    @Test
    public void disablingGpsRemovesMarkersWithoutALocationSubscription() {
        scenario.onActivity(current -> {
            showPlayers();
            set("mLocationListener", null);
            map.enableGPS(false);
            assertNull("Teammate survived GPS disable without a local subscription", marker(2));
            assertNull(marker(9));
        });
    }

    @Test
    public void disabledGpsIgnoresQueuedPlayerUpdates() {
        scenario.onActivity(current -> {
            showPlayers();
            Globals.getInstance().mUseGPS = false;
            map.enableGPS(false);
            putLocation(2, 15);
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
            assertNull("Queued update recreated a marker after GPS was disabled", marker(2));
            assertTrue(Globals.getInstance().mGPSData.get((byte) 2).hasUpdate);
        });
    }

    @Test
    public void pausedMapIgnoresQueuedPlayerUpdates() {
        scenario.onActivity(current -> {
            showPlayers();
            whilePaused(() -> {
                putLocation(2, 15);
                receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
                assertNull("Queued update recreated a paused map's marker", marker(2));
                assertTrue(Globals.getInstance().mGPSData.get((byte) 2).hasUpdate);
            });
        });
    }

    @Test
    public void permissionGrantedAfterDisableDoesNotRestartLocationUpdates() {
        scenario.onActivity(current -> {
            map.enableGPS(false);
            grantPermission();
            assertNull("Delayed permission result restarted disabled GPS", get("mLocationListener"));
        });
    }

    @Test
    public void permissionGrantedWhilePausedDoesNotRestartLocationUpdates() {
        scenario.onActivity(current -> whilePaused(() -> {
            grantPermission();
            assertNull("Delayed permission result restarted paused GPS", get("mLocationListener"));
        }));
    }

    @Test
    public void permissionGrantedWhileActiveStillRegistersLocationUpdates() {
        scenario.onActivity(current -> {
            set("mLocationListener", null);
            grantPermission();
            assertNotNull(get("mLocationListener"));
        });
    }

    @Test
    public void unchangedIncrementalUpdateKeepsExistingMarkers() {
        scenario.onActivity(current -> {
            showPlayers();
            ComponentObject teammate = marker(2);
            ComponentObject enemy = marker(9);
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
            assertSame(teammate, marker(2));
            assertSame(enemy, marker(9));
        });
    }

    @Test
    public void incrementalUpdateReplacesOnlyTheMovedPlayersMarker() {
        scenario.onActivity(current -> {
            showPlayers();
            ComponentObject teammate = marker(2);
            ComponentObject enemy = marker(9);
            putLocation(2, 15);
            receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
            assertNotNull(marker(2));
            assertNotSame(teammate, marker(2));
            assertSame(enemy, marker(9));
        });
    }

    private void whilePaused(Runnable action) {
        // Keep Android's receiver registrations balanced while exercising the
        // actual fragment callbacks without restarting the whole activity.
        map.onPause();
        try { action.run(); }
        finally { map.onResume(); }
    }

    private void showPlayers() {
        putLocation(2, 10);
        putLocation(9, 20);
        receive(new Intent(NetMsg.NETMSG_GPSDATAUPDATE));
        assertNotNull("Teammate marker was not created", marker(2));
        assertNotNull("Enemy marker was not created", marker(9));
        assertFalse(Globals.getInstance().mGPSData.get((byte) 2).hasUpdate);
        assertFalse(Globals.getInstance().mGPSData.get((byte) 9).hasUpdate);
    }

    private void putLocation(int playerID, double longitude) {
        Globals.GPSData location = new Globals.GPSData();
        location.longitude = longitude;
        location.latitude = 20;
        location.team = Globals.getInstance().calcNetworkTeam((byte) playerID);
        location.hasUpdate = true;
        Globals.getmGPSDataSemaphore();
        try { Globals.getInstance().mGPSData.put((byte) playerID, location); }
        finally { Globals.getInstance().mGPSDataSemaphore.release(); }
    }

    private void useDummyLocationListener() {
        set("mLocationListener", new LocationListener() {
            @Override public void onLocationChanged(Location location) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        });
    }

    private void grantPermission() {
        map.onRequestPermissionsResult(1, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                new int[]{PackageManager.PERMISSION_GRANTED});
    }

    private ComponentObject marker(int playerID) { return ((ComponentObject[]) get("mPlayerMarkers"))[playerID]; }

    private void receive(Intent intent) {
        ((BroadcastReceiver) get("mGPSDataReceiver")).onReceive(activity, intent);
    }

    private Object get(String name) {
        try {
            Field field = MapFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(map);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void set(String name, Object value) {
        try {
            Field field = MapFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(map, value);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
