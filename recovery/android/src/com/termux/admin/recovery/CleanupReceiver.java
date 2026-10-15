// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin.recovery;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
public final class CleanupReceiver extends BroadcastReceiver {
  static android.content.SharedPreferences lease(Context c) {
    return c.createDeviceProtectedStorageContext().getSharedPreferences("recovery-lease", 0);
  }
  static boolean disable(Context c) {
    try {
      if (Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0)
          && Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 0) {
        lease(c).edit().clear().commit();
        ((android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE))
            .cancel(ControlReceiver.cleanup(c));
        return true;
      }
    } catch (RuntimeException failure) {
      android.util.Log.w("PhoneRecovery", "Debugging cleanup failed; retry scheduled");
    }
    // Alarms may be delayed by Doze. Each attempt is bounded and checks readback.
    try {
      ((android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE))
          .setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
              android.os.SystemClock.elapsedRealtime()+30000, ControlReceiver.cleanup(c));
    } catch (RuntimeException failure) {
      android.util.Log.e("PhoneRecovery", "Could not schedule debugging cleanup retry");
    }
    return false;
  }
  static void clearExpired(Context c) {
    long expiry = lease(c).getLong("expiry", 0);
    if (expiry != 0 && android.os.SystemClock.elapsedRealtime() >= expiry) disable(c);
  }
  @Override public void onReceive(Context c, Intent i) {
    disable(c);
  }
}
