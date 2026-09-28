package com.simplecoil.simplecoil;

/** Per-gun recoil protection. Never changes the weapon's firing profile. */
final class GunPowerMode {
    static final int LOW_POWER_MILLIVOLTS = 4000;
    static final int RECOVER_MILLIVOLTS = 4800;
    private boolean lowPower;
    private boolean freshConnection = true;

    void reset(boolean rememberedLowPower) {
        lowPower = rememberedLowPower;
        freshConnection = true;
    }

    boolean isLowPower() {
        return lowPower;
    }

    /** True only on a transition, so steady telemetry cannot flood BLE writes. */
    boolean updateVoltage(int millivolts) {
        if (millivolts < 0) return false;
        boolean before = lowPower;
        if (freshConnection) {
            freshConnection = false;
            // A charged/replaced pack can restore normal recoil after reconnect.
            // Do not let the voltage rebound caused by stopping recoil do this
            // during the same connection, even across rounds or rule changes.
            if (millivolts >= RECOVER_MILLIVOLTS) lowPower = false;
        }
        if (millivolts <= LOW_POWER_MILLIVOLTS) lowPower = true;
        return before != lowPower;
    }

    boolean recoilEnabled(boolean requested, boolean tournament) {
        return (requested || tournament) && !lowPower;
    }

    byte[] recoilCommand(boolean requested, boolean tournament) {
        byte[] command = new byte[20];
        command[0] = 0x10;
        command[2] = 0x02;
        command[3] = recoilEnabled(requested, tournament) ? (byte) 0x03 : (byte) 0x02;
        command[4] = (byte) 0xff; // Leave shot mode, cadence, and muzzle flash unchanged.
        return command;
    }
}
