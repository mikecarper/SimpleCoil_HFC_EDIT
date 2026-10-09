package com.simplecoil.simplecoil;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mousebird.maply.ComponentObject;
import com.mousebird.maply.GlobeMapFragment;
import com.mousebird.maply.LayerThread;
import com.mousebird.maply.MaplyBaseController;
import com.mousebird.maply.MaplyLifecycleCleanup;
import com.mousebird.maply.QuadImageTileLayer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class MapLifecycleRegressionTest {
    @Test public void legacyAnalyticsSuppressionUsesTheBundledLongSecondsGuard() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("WGMaplyPrefs", Context.MODE_PRIVATE);
        String key = "wgmaplyanalytictime2";
        boolean hadPrevious = preferences.contains(key);
        long previous = preferences.getLong(key, 0);
        try {
            preferences.edit().putLong(key, 0).commit();
            long before = System.currentTimeMillis() / 1000;
            // Exercise the guard directly; this test never constructs a native controller.
            MaplyLifecycleCleanup.suppressLegacyAnalytics(context);
            long after = System.currentTimeMillis() / 1000;
            assertTrue(preferences.getAll().get(key) instanceof Long);
            long suppressedAt = preferences.getLong(key, 0);
            assertTrue(suppressedAt >= before && suppressedAt <= after);
            assertTrue(after - suppressedAt <= 30L * 24 * 60 * 60);
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadPrevious) editor.putLong(key, previous);
            else editor.remove(key);
            editor.commit();
        }
    }

    @Test public void destroyingHiddenMapBeforeSurfaceCreationTerminatesItsLayerThread()
            throws InterruptedException {
        Globals globals = Globals.getInstance();
        int previousGameState = globals.mGameState;
        boolean previousUseGps = globals.mUseGPS;
        globals.mGameState = Globals.GAME_STATE_NONE;
        globals.mUseGPS = false;
        LayerThread[] worker = new LayerThread[1];
        try (ActivityScenario<FullscreenActivity> scenario =
                     ActivityScenario.launch(FullscreenActivity.class)) {
            scenario.onActivity(activity -> {
                globals.mUseGPS = false;
                FrameLayout hiddenContainer = new FrameLayout(activity);
                hiddenContainer.setId(View.generateViewId());
                hiddenContainer.setVisibility(View.GONE);
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(hiddenContainer);
                MapFragment map = new MapFragment();
                activity.getSupportFragmentManager().beginTransaction()
                        .add(hiddenContainer.getId(), map, "hidden-map-lifecycle").commitNow();
                MaplyBaseController controller = controller(map);
                assertNotNull(controller);
                assertFalse(controller.rendererIsAttached());
                worker[0] = controller.getLayerThread();
                assertNotNull(worker[0]);
                assertTrue(worker[0].isAlive());
                assertNull(worker[0].renderer);
                assertTrue(startupLock(worker[0]).isHeldByCurrentThread());

                // Cached tile/marker handles cannot be reused by a later controller.
                // The bundled private constructor creates only a Java sentinel,
                // without allocating a native layer or starting any tile requests.
                setCachedField(map, "mBaseLayer", unattachedLayer());
                setCachedField(map, "mBaseTileUrl", "http://192.0.2.1:17512/tiles/");
                setCachedField(map, "mPlayerMarkers", new ComponentObject[Globals.MAX_PLAYER_ID + 1]);
                assertNotNull(cachedField(map, "mBaseLayer"));
                assertNotNull(cachedField(map, "mBaseTileUrl"));
                assertNotNull(cachedField(map, "mPlayerMarkers"));

                activity.getSupportFragmentManager().beginTransaction().remove(map).commitNow();
                assertNull(controller(map));
                assertNull("Destroyed controller retained its tile layer", cachedField(map, "mBaseLayer"));
                assertNull("An unchanged laptop URL must rebuild on recreation", cachedField(map, "mBaseTileUrl"));
                assertNull("Destroyed controller retained its marker handles", cachedField(map, "mPlayerMarkers"));
                ((ViewGroup) hiddenContainer.getParent()).removeView(hiddenContainer);
            });
            // Joining off the main thread allows native teardown to finish normally.
            worker[0].join(2000);
            assertFalse("A never-rendered map retained its startup worker", worker[0].isAlive());
        } finally {
            globals.mGameState = previousGameState;
            globals.mUseGPS = previousUseGps;
        }
    }

    private static QuadImageTileLayer unattachedLayer() {
        try {
            Constructor<QuadImageTileLayer> constructor = QuadImageTileLayer.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object cachedField(MapFragment map, String name) {
        try {
            Field field = MapFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(map);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setCachedField(MapFragment map, String name, Object value) {
        try {
            Field field = MapFragment.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(map, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static MaplyBaseController controller(MapFragment map) {
        try {
            Field field = GlobeMapFragment.class.getDeclaredField("baseControl");
            field.setAccessible(true);
            return (MaplyBaseController) field.get(map);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ReentrantLock startupLock(LayerThread worker) {
        try {
            Field field = LayerThread.class.getDeclaredField("startLock");
            field.setAccessible(true);
            return (ReentrantLock) field.get(worker);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
