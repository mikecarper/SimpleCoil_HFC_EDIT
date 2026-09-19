package com.simplecoil.simplecoil;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** Exercises result handling, stopping at the connection guard before any radio operations. */
@RunWith(AndroidJUnit4.class)
public class ConnectionResultRegressionTest {
    private static final String ORIGINAL_ADDRESS = "00:11:22:33:44:55";
    private ResultActivity activity;

    @Before
    public void setUp() {
        onMain(() -> {
            activity = new ResultActivity();
            setAddress(ORIGINAL_ADDRESS);
        });
    }

    @Test
    public void cancellingBluetoothEnableDoesNotRetryTheConnection() {
        assertEnableResult(Activity.RESULT_CANCELED, 0);
    }

    @Test
    public void unsuccessfulBluetoothEnableDoesNotRetryTheConnection() {
        assertEnableResult(Activity.RESULT_FIRST_USER, 0);
    }

    @Test
    public void acceptingBluetoothEnableStillContinuesTheConnection() {
        assertEnableResult(Activity.RESULT_OK, 1);
    }

    private void assertEnableResult(int result, int expectedAttempts) {
        onMain(() -> {
            activity.onActivityResult(1, result, null);
            assertEquals(expectedAttempts, activity.connectionGuards);
            assertEquals(ORIGINAL_ADDRESS, address());
        });
    }

    @Test
    public void unrelatedActivityResultDoesNotStartAConnection() {
        onMain(() -> {
            activity.onActivityResult(999, Activity.RESULT_OK, null);
            assertEquals(0, activity.connectionGuards);
        });
    }

    @Test
    public void validQrAddressStillSelectsTheRequestedWeapon() {
        assertValidQrAddress("AA:BB:CC:DD:EE:FF");
    }

    @Test
    public void lowercaseQrAddressMatchesAndroidsCanonicalDeviceAddress() {
        assertValidQrAddress("aa:bb:cc:dd:ee:ff");
    }

    @Test
    public void surroundingQrWhitespaceDoesNotPreventFindingTheWeapon() {
        assertValidQrAddress(" \tAA:BB:CC:DD:EE:FF\r\n");
    }

    private void assertValidQrAddress(String value) {
        onMain(() -> {
            activity.onActivityResult(2, Activity.RESULT_OK, qrResult(value));
            assertEquals("AA:BB:CC:DD:EE:FF", address());
            assertEquals(1, activity.connectionGuards);
        });
    }

    @Test
    public void malformedQrAddressDoesNotReplaceThePreviousWeaponOrStartScanning() {
        onMain(() -> {
            for (String value : new String[]{"not a blaster", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:GG",
                    "AA:BB:CC:DD:EE:FF:00", "https://example.com/"}) {
                activity.onActivityResult(2, Activity.RESULT_OK, qrResult(value));
                assertEquals(ORIGINAL_ADDRESS, address());
                assertEquals(0, activity.connectionGuards);
            }
        });
    }

    @Test
    public void emptyQrAddressDoesNotForgetThePreviousWeapon() {
        onMain(() -> {
            for (String value : new String[]{"", " \t\r\n"}) {
                activity.onActivityResult(2, Activity.RESULT_OK, qrResult(value));
                assertEquals(ORIGINAL_ADDRESS, address());
                assertEquals(0, activity.connectionGuards);
            }
        });
    }

    @Test
    public void missingQrAddressDoesNotForgetThePreviousWeapon() {
        assertIgnoredQrResult(Activity.RESULT_OK, new Intent());
    }

    @Test
    public void nullQrResultDoesNotForgetThePreviousWeapon() {
        assertIgnoredQrResult(Activity.RESULT_OK, null);
    }

    @Test
    public void cancelledQrScanCannotChangeTheSelectedWeapon() {
        assertIgnoredQrResult(Activity.RESULT_CANCELED, qrResult("AA:BB:CC:DD:EE:FF"));
    }

    private void assertIgnoredQrResult(int result, Intent data) {
        onMain(() -> {
            activity.onActivityResult(2, result, data);
            assertEquals(ORIGINAL_ADDRESS, address());
            assertEquals(0, activity.connectionGuards);
        });
    }

    private static Intent qrResult(String address) {
        return new Intent().putExtra("SCAN_RESULT", address);
    }

    private String address() {
        try { return (String) addressField().get(activity); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void setAddress(String address) {
        try { addressField().set(activity, address); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Field addressField() throws NoSuchFieldException {
        Field field = FullscreenActivity.class.getDeclaredField("mDeviceAddress");
        field.setAccessible(true);
        return field;
    }

    private static void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); }
            catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class ResultActivity extends FullscreenActivity {
        int connectionGuards;

        @Override public boolean isFinishing() {
            connectionGuards++;
            return true; // connectWeapon must stop here, without scanning or requesting permissions.
        }

        @Override public Context getApplicationContext() {
            return InstrumentationRegistry.getInstrumentation().getTargetContext();
        }
    }
}
