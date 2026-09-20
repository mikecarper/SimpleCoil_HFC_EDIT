package com.simplecoil.simplecoil;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Exercises Android's WifiInfo IPv4 representation on a real target device. */
@RunWith(AndroidJUnit4.class)
public class NetworkAddressRegressionTest {
    @Test public void wifiAddressOrderMatchesWifiManagerWhenConnected() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        WifiManager manager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        assertNotNull(manager);

        WifiInfo info = manager.getConnectionInfo();
        assertNotNull(info);
        InetAddress wifiAddress = Globals.fromWifiIPv4Address(info.getIpAddress());
        if (wifiAddress == null) {
            // This device is not associated with Wi-Fi. The production method will safely
            // use its legacy interface fallback instead of treating 0.0.0.0 as a peer address.
            assertNull(Globals.fromWifiIPv4Address(0));
        } else {
            assertEquals(wifiAddress, Globals.getIPAddress(context));
        }
    }
}
