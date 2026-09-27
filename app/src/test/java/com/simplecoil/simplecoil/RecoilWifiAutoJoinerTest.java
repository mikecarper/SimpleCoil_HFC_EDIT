package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class RecoilWifiAutoJoinerTest {
    @Test public void scanResultDoesNotImmediatelyStartAnotherRadioScan() {
        assertEquals(0, RecoilWifiAutoJoiner.scanRetryDelay(10_000, -1));
        assertEquals(3_000, RecoilWifiAutoJoiner.scanRetryDelay(10_000, 10_000));
        assertEquals(2_400, RecoilWifiAutoJoiner.scanRetryDelay(10_600, 10_000));
        assertEquals(1, RecoilWifiAutoJoiner.scanRetryDelay(12_999, 10_000));
        assertEquals(0, RecoilWifiAutoJoiner.scanRetryDelay(13_000, 10_000));
        assertEquals(0, RecoilWifiAutoJoiner.scanRetryDelay(20_000, 10_000));
    }

    @Test public void acceptsRecoilPrefixWithoutCaringAboutCaseOrPlatformQuotes() {
        assertTrue(RecoilWifiAutoJoiner.isRecoilSsid("Recoil Game Hub"));
        assertTrue(RecoilWifiAutoJoiner.isRecoilSsid("  \"reCOIL-field\"  "));
        assertFalse(RecoilWifiAutoJoiner.isRecoilSsid("field recoil"));
        assertFalse(RecoilWifiAutoJoiner.isRecoilSsid("<unknown ssid>"));
    }

    @Test public void onlyOpenRecoilNetworksAreEligibleForAutomaticJoining() {
        assertTrue(RecoilWifiAutoJoiner.isEligibleNetwork("Recoil Game Hub", "[ESS]"));
        assertTrue(RecoilWifiAutoJoiner.isEligibleNetwork("recoil", ""));
        assertFalse(RecoilWifiAutoJoiner.isEligibleNetwork("Recoil Game Hub", "[WPA2-PSK-CCMP][ESS]"));
        assertFalse(RecoilWifiAutoJoiner.isEligibleNetwork("Recoil Game Hub", "[WPA3-SAE-CCMP][ESS]"));
        assertFalse(RecoilWifiAutoJoiner.isEligibleNetwork("Other Hub", "[ESS]"));
    }
}
