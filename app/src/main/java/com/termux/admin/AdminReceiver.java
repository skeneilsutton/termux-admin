// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.os.PersistableBundle;

public final class AdminReceiver extends DeviceAdminReceiver {
    @Override public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
        // Android retains policy state. Never replay policies from a transfer payload.
        context.getSharedPreferences("admin", Context.MODE_PRIVATE).edit()
            .putBoolean("transfer_received", true).apply();
    }
}
