package com.simplecoil.simplecoil;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecoilWifiAutoJoinerTest {
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
