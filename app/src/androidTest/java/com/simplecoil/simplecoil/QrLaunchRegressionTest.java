package com.simplecoil.simplecoil;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Captures launch requests from the real QR button; no external app is opened. */
@RunWith(AndroidJUnit4.class)
public class QrLaunchRegressionTest {
    private ActivityScenario<FullscreenActivity> scenario;
    private Field instrumentationField;
    private Instrumentation originalInstrumentation;
    private LaunchRecorder launches;
    private boolean originalUseGPS;
    private int originalGameState;

    @Before
    public void setUp() throws Exception {
        originalUseGPS = Globals.getInstance().mUseGPS;
        originalGameState = Globals.getInstance().mGameState;
        Globals.getInstance().mUseGPS = false;
        Globals.getInstance().mGameState = Globals.GAME_STATE_NONE;
        instrumentationField = Activity.class.getDeclaredField("mInstrumentation");
        instrumentationField.setAccessible(true);
        scenario = ActivityScenario.launch(FullscreenActivity.class);
        scenario.onActivity(current -> {
            try {
                originalInstrumentation = (Instrumentation) instrumentationField.get(current);
                launches = new LaunchRecorder();
                instrumentationField.set(current, launches);
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        });
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.onActivity(current -> {
                try { instrumentationField.set(current, originalInstrumentation); }
                catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
            scenario.close();
        }
        Globals.getInstance().mUseGPS = originalUseGPS;
        Globals.getInstance().mGameState = originalGameState;
    }

    @Test
    public void availableScannerStillReceivesTheQrRequest() {
        clickQrButton();
        assertEquals(1, launches.intents.size());
        assertEquals("com.google.zxing.client.android.SCAN", launches.intents.get(0).getAction());
        assertEquals("QR_CODE_MODE", launches.intents.get(0).getStringExtra("SCAN_MODE"));
        assertEquals(2, (int) launches.requestCodes.get(0));
    }

    @Test
    public void missingScannerStillOpensAnAvailableAppStore() {
        launches.scannerFailure = new ActivityNotFoundException("Scanner is not installed");
        clickQrButton();
        assertStoreWasRequested();
    }

    @Test
    public void inaccessibleScannerCanStillUseTheAppStoreFallback() {
        launches.scannerFailure = new SecurityException("Scanner is not accessible");
        clickQrButton();
        assertStoreWasRequested();
    }

    @Test
    public void missingScannerAndStoreDoNotCrashTheConnectionScreen() {
        assertUnavailableAppsHandled(new ActivityNotFoundException("Scanner is not installed"),
                new ActivityNotFoundException("App store is not installed"));
    }

    @Test
    public void inaccessibleStoreDoesNotCrashTheConnectionScreen() {
        assertUnavailableAppsHandled(new ActivityNotFoundException("Scanner is not installed"),
                new SecurityException("App store is not accessible"));
    }

    @Test
    public void inaccessibleScannerAndMissingStoreDoNotCrashTheConnectionScreen() {
        assertUnavailableAppsHandled(new SecurityException("Scanner is not accessible"),
                new ActivityNotFoundException("App store is not installed"));
    }

    private void assertUnavailableAppsHandled(RuntimeException scannerFailure, RuntimeException storeFailure) {
        launches.scannerFailure = scannerFailure;
        launches.storeFailure = storeFailure;
        clickQrButton();
        assertStoreWasRequested();
        scenario.onActivity(current -> {
            assertFalse(current.isFinishing());
            assertTrue(current.findViewById(R.id.connect_weapon_button).isEnabled());
            assertTrue(current.findViewById(R.id.connect_qr_weapon_button).isEnabled());
        });
    }

    private void clickQrButton() {
        scenario.onActivity(current -> current.findViewById(R.id.connect_qr_weapon_button).performClick());
    }

    private void assertStoreWasRequested() {
        assertEquals(2, launches.intents.size());
        Intent store = launches.intents.get(1);
        assertEquals(Intent.ACTION_VIEW, store.getAction());
        assertEquals("market://details?id=com.google.zxing.client.android", store.getDataString());
    }

    private static final class LaunchRecorder extends Instrumentation {
        final List<Intent> intents = new ArrayList<>();
        final List<Integer> requestCodes = new ArrayList<>();
        RuntimeException scannerFailure;
        RuntimeException storeFailure;

        // This is the framework entry point used by Activity on the target API 22 device.
        // It is hidden from the public SDK, so @Override cannot be used here.
        public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
                                                Activity target, Intent intent, int requestCode, Bundle options) {
            intents.add(new Intent(intent));
            requestCodes.add(requestCode);
            RuntimeException failure = "com.google.zxing.client.android.SCAN".equals(intent.getAction())
                    ? scannerFailure : storeFailure;
            if (failure != null) throw failure;
            return null;
        }
    }
}
