// SPDX-License-Identifier: Apache-2.0
package com.simplecoil.simplecoil;

/** Independent BLE service instance for the boss's optional second blaster. */
public final class BossSecondaryBluetoothLeService extends BluetoothLeService {
    @Override
    protected int weaponSlot() {
        return 1;
    }
}
