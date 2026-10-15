// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin.recovery;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import java.nio.charset.StandardCharsets;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;

public final class ControlReceiver extends BroadcastReceiver {
  static final String PREFIX = "com.termux.admin.recovery.";
  static boolean wifi(Context c) {
    ConnectivityManager cm = (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
    for (Network n : cm.getAllNetworks()) {
      NetworkCapabilities cap = cm.getNetworkCapabilities(n);
      if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
          && !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
    }
    return false;
  }
  static PendingIntent cleanup(Context c) {
    return PendingIntent.getBroadcast(c, 1, new Intent(c, CleanupReceiver.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  @Override public void onReceive(Context c, Intent intent) {
    JSONObject state = new JSONObject();
    try {
      String action = intent.getAction();
      CleanupReceiver.clearExpired(c);
      boolean connected = wifi(c);
      state.put("wifi", connected);
      if ((PREFIX + "ENABLE").equals(action)) {
        if (!connected) throw new IllegalStateException("wifi_required");
        AlarmManager am = (AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        // Record and schedule cleanup before enabling. The alarm is best-effort
        // under Doze; normal cleanup also performs verified setting readback.
        long expiry = SystemClock.elapsedRealtime()+45000;
        if (!CleanupReceiver.lease(c).edit().putLong("expiry", expiry).commit())
          throw new IllegalStateException("lease_record_failed");
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            expiry, cleanup(c));
        if (!Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1)
            || Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1)
          throw new IllegalStateException("setting_write_failed");
        state.put("lease_seconds", 45);
      } else if ((PREFIX + "DISABLE").equals(action)) {
        if (!CleanupReceiver.disable(c))
          throw new IllegalStateException("setting_disable_failed");
      } else if (!(PREFIX + "STATE").equals(action) && !(PREFIX + "DISCOVER").equals(action)) {
        throw new IllegalArgumentException("unknown_action");
      }
      state.put("wireless", Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0));
      state.put("ok", true);
      if (isOrderedBroadcast()) setResultCode(0);
    } catch (Throwable e) {
      try { state.put("ok", false); state.put("error", e.toString()); } catch (Exception ignored) {}
      if (isOrderedBroadcast()) setResultCode(1);
    }
    if (isOrderedBroadcast()) setResultData(state.toString());
    final PendingResult pending = goAsync();
    new Thread(new Runnable() { public void run() {
    if ((PREFIX+"DISCOVER").equals(intent.getAction())) {
      try { state.put("ports", Discovery.ports(c)); }
      catch (Exception e) { try { state.put("ok",false); state.put("error",e.toString()); } catch (Exception ignored) {} }
    }
    int port = intent.getIntExtra("reply_port", 0);
    String key = intent.getStringExtra("reply_key");
    if (port > 0 && port <= 65535 && key != null && key.matches("[0-9a-f]{64}")) {
      try (java.net.Socket socket = new java.net.Socket()) {
        byte[] secret = new byte[32];
        for (int i=0; i<32; i++) secret[i]=(byte)Integer.parseInt(key.substring(i*2,i*2+2),16);
        byte[] body = state.toString().getBytes(StandardCharsets.UTF_8);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body)) hex.append(String.format("%02x", b & 255));
        JSONObject reply = new JSONObject();
        reply.put("body", state.toString()); reply.put("mac", hex.toString());
        socket.connect(new java.net.InetSocketAddress("127.0.0.1",port), 1000);
        socket.getOutputStream().write((reply.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
      } catch (Exception e) { android.util.Log.e("PhoneRecovery", "Reply failed", e); }
    }
    pending.finish();
    }}, "phone-recovery-reply").start();
  }
}
