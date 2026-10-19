package com.simplecoil.simplecoil;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/** Optional device-owner component for dedicated event phones. */
public final class EventDeviceAdminReceiver extends DeviceAdminReceiver {
    static ComponentName component(Context context) {
        return new ComponentName(context, EventDeviceAdminReceiver.class);
    }
}
