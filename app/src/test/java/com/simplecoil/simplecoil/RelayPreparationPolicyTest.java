package com.simplecoil.simplecoil;

import org.junit.Test;
import static org.junit.Assert.*;

public class RelayPreparationPolicyTest {
    @Test public void weakSignalPreparesOneBuddyWhileTheHostIsStillReachable() {
        RelayPreparationPolicy policy = new RelayPreparationPolicy(1000);
        assertFalse(policy.shouldConnect(2000, true, -55, true, false));
        assertFalse(policy.shouldConnect(3000, true, -66, true, false));
        assertTrue(policy.shouldConnect(4000, true, -67, true, false));
        assertFalse(policy.shouldConnect(5000, true, -80, true, true));
    }

    @Test public void weakSignalMustRecoverFiveDbBeforePreparationStops() {
        RelayPreparationPolicy policy = new RelayPreparationPolicy(0);
        assertTrue(policy.shouldConnect(1000, true, -70, true, false));
        assertTrue(policy.shouldConnect(2000, true, -65, true, false));
        assertFalse(policy.shouldConnect(3000, true, -62, true, false));
        assertFalse(policy.shouldConnect(4000, true, -65, true, false));
    }

    @Test public void missingReadingsDoNotPretendHealthyWifiIsWeak() {
        for (int rssi : new int[] {-127, -200, 0, 10}) {
            RelayPreparationPolicy policy = new RelayPreparationPolicy(0);
            assertFalse(policy.shouldConnect(1000, true, rssi, true, false));
        }
    }

    @Test public void wifiLossDoesNotWaitForTheHostHeartbeatOrStartupGrace() {
        RelayPreparationPolicy policy = new RelayPreparationPolicy(1000);
        assertTrue(policy.shouldConnect(1001, false, -127, true, false));
        assertFalse(policy.shouldConnect(1002, false, -127, true, true));
    }

    @Test public void missingHostTriesOtherBuddiesWithoutAnotherFiveSecondWait() {
        RelayPreparationPolicy policy = new RelayPreparationPolicy(0);
        assertFalse(policy.shouldConnect(1000, true, -55, true, true));
        assertTrue(policy.shouldConnect(2000, true, -55, false, true));
    }

    @Test public void strongWifiGetsTimeToEstablishTheFirstHostLink() {
        RelayPreparationPolicy policy = new RelayPreparationPolicy(1000);
        assertFalse(policy.shouldConnect(1000, true, -55, false, false));
        assertFalse(policy.shouldConnect(4499, true, -127, false, false));
        assertTrue(policy.shouldConnect(4500, true, -55, false, false));
    }
}
