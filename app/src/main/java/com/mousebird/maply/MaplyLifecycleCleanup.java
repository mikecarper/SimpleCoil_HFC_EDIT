package com.mousebird.maply;

import android.content.Context;

/** Compatibility cleanup for the pinned app/libs/WhirlyGlobeMaply.aar. */
public final class MaplyLifecycleCleanup {
    private static final String ANALYTICS_PREFERENCES = "WGMaplyPrefs";
    private static final String ANALYTICS_LAST_REGISTRATION = "wgmaplyanalytictime2";

    private MaplyLifecycleCleanup() { }

    public static void suppressLegacyAnalytics(Context context) {
        if (context == null) return;
        // Maply 2.6.2 has no opt-out setting. Its private startup analytics uses
        // an untracked HTTP client which controller shutdown cannot cancel.
        // The bundled guard reads this long in epoch seconds and skips requests
        // for 30 days. Refresh it before construction to disable optional reports.
        context.getSharedPreferences(ANALYTICS_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putLong(ANALYTICS_LAST_REGISTRATION, System.currentTimeMillis() / 1000).apply();
    }

    public static void stopUnrenderedLayerThread(MaplyBaseController controller) {
        if (controller == null) return;
        // surfaceCreated() takes this same monitor and checks startupAborted.
        // Prevent a late GL surface from initializing a controller being retired.
        synchronized (controller) {
            if (controller.rendererAttached || controller.layerThreads == null) return;
            LayerThread thread = controller.getLayerThread();
            if (thread == null || thread.renderer != null || thread.startLock == null
                    || !thread.startLock.isHeldByCurrentThread()) return;
            // The bundled constructor holds this lock until the first surface.
            // A hidden map's startup task blocks on it, so shutdown's queued quit
            // cannot run. Quit first to discard queued work, then let startup exit.
            controller.startupAborted = true;
            thread.valid = false;
            thread.quit();
            thread.startLock.unlock();
        }
    }
}
