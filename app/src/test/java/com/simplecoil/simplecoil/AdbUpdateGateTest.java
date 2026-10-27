package com.simplecoil.simplecoil;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdbUpdateGateTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "abcdef0123456789abcdef0123456789";
    private static final String HOST = "192.168.1.44";

    @Test public void prepareReservesOnlyAnEligiblePhoneAndStatusRechecksItsSafety() {
        AdbUpdateGate gate = new AdbUpdateGate(() -> 1000L);
        assertEquals("busy", gate.prepare(NONCE, HOST, false));
        assertFalse(gate.held());
        assertEquals("ready", gate.prepare(NONCE, HOST, true));
        assertTrue(gate.held());
        assertEquals("busy", gate.status(NONCE, HOST, false));
        assertTrue("A transient unsafe state must not reopen local controls", gate.held());
        assertEquals("ready", gate.status(NONCE, HOST, true));
    }

    @Test public void anotherRequestCannotHijackOrReleaseTheReservedPhone() {
        AdbUpdateGate gate = new AdbUpdateGate(() -> 1000L);
        assertEquals("ready", gate.prepare(NONCE, HOST, true));
        assertEquals("busy", gate.prepare(OTHER, HOST, true));
        assertEquals("busy", gate.status(NONCE, "192.168.1.45", true));
        assertEquals("busy", gate.release(OTHER, HOST));
        assertEquals("busy", gate.release(NONCE, "192.168.1.45"));
        assertEquals("ready", gate.status(NONCE, HOST, true));
        assertEquals("released", gate.release(NONCE, HOST));
        assertFalse(gate.held());
        assertEquals("absent", gate.status(NONCE, HOST, true));
    }

    @Test public void retriesCannotExtendTheDeadlineAndControlsRecoverAfterLaptopLoss() {
        AtomicLong now = new AtomicLong(1000);
        AdbUpdateGate gate = new AdbUpdateGate(now::get);
        assertEquals("ready", gate.prepare(NONCE, HOST, true));
        now.addAndGet(AdbUpdateGate.LEASE_MILLIS - 1);
        assertEquals("ready", gate.prepare(NONCE, HOST, true));
        assertEquals(1, gate.remaining());
        now.incrementAndGet();
        assertFalse(gate.held());
        assertEquals(0, gate.remaining());
        assertEquals("expired", gate.status(NONCE, HOST, true));
        assertEquals("ready", gate.prepare(OTHER, HOST, true));
        assertEquals(AdbUpdateGate.LEASE_MILLIS, gate.remaining());
    }

    @Test public void anExpiredLeaseCanStillBeReleasedWithoutBlockingControls() {
        AtomicLong now = new AtomicLong(1000);
        AdbUpdateGate gate = new AdbUpdateGate(now::get);
        assertEquals("ready", gate.prepare(NONCE, HOST, true));
        now.addAndGet(AdbUpdateGate.LEASE_MILLIS);
        assertEquals("released", gate.release(NONCE, HOST));
        assertFalse(gate.held());
        assertEquals("absent", gate.status(NONCE, HOST, true));
    }

    @Test public void malformedAndNonNumericRequestsNeverChangeTheReservation() {
        AdbUpdateGate gate = new AdbUpdateGate(() -> 1000L);
        for (String host : new String[] {"github.com", "192.168.1.256", "192.168.1", "", "1.2.3.4\n"}) {
            assertEquals("invalid", gate.prepare(NONCE, host, true));
        }
        assertEquals("invalid", gate.prepare("too-short", HOST, true));
        assertEquals("invalid", gate.prepare(null, HOST, true));
        assertEquals("invalid", gate.release(NONCE, null));
        assertFalse(gate.held());
        assertTrue(AdbUpdateGate.valid(NONCE, "fe80::12%3"));
    }
}
