// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin.recovery;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;

final class Discovery {
  static JSONArray ports(Context context) throws Exception {
    NsdManager manager = (NsdManager)context.getSystemService(Context.NSD_SERVICE);
    Set<Integer> ports = java.util.Collections.synchronizedSet(new TreeSet<Integer>());
    NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
      public void onDiscoveryStarted(String type) { }
      public void onDiscoveryStopped(String type) { }
      public void onStartDiscoveryFailed(String type, int code) { }
      public void onStopDiscoveryFailed(String type, int code) { }
      public void onServiceLost(NsdServiceInfo service) { }
      public void onServiceFound(NsdServiceInfo service) {
        manager.resolveService(service, new NsdManager.ResolveListener() {
          public void onResolveFailed(NsdServiceInfo service, int code) { }
          public void onServiceResolved(NsdServiceInfo service) { ports.add(service.getPort()); }
        });
      }
    };
    manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
    try { Thread.sleep(6000); }
    finally { manager.stopServiceDiscovery(listener); }
    synchronized (ports) { return new JSONArray(ports); }
  }
}
