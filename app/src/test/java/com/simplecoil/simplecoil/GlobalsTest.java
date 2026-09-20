package com.simplecoil.simplecoil;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GlobalsTest {
    @Test public void wifiIpv4UsesAndroidLittleEndianOctets() {
        InetAddress address = Globals.fromWifiIPv4Address(0x2A01A8C0);
        assertEquals("192.168.1.42", address.getHostAddress());
    }

    @Test public void wifiWithoutAnAddressDoesNotProduceAnyLocalAddress() {
        assertNull(Globals.fromWifiIPv4Address(0));
    }
}
