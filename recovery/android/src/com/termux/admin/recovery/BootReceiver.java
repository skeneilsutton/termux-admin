// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin.recovery;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())
        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(i.getAction()))
      CleanupReceiver.disable(c);
  }
}
