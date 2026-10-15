// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

/** Publishes only a handle. Holding this handle never authorizes a privileged call. */
public final class HostReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
        if (!(Config.HOST_PACKAGE + ".DISCOVER").equals(intent.getAction())) return;
        try {
        android.os.Bundle data = intent.getBundleExtra("data");
        if (data == null) return;
        String discovery = data.getString("discovery");
        android.os.IBinder callback = data.getBinder("callback");
        if (callback == null || discovery == null || !discovery.matches("[0-9a-f]{32}")) return;
        android.os.Parcel result = android.os.Parcel.obtain();
        try {
            result.writeInterfaceToken(Wire.CALLBACK); result.writeString(discovery);
            result.writeStrongBinder(Host.get(context));
            // An untrusted callback cannot hold up the receiver's main thread.
            callback.transact(1, result, null, android.os.IBinder.FLAG_ONEWAY);
        } catch (android.os.RemoteException ignored) {} finally { result.recycle(); }
        } catch (RuntimeException malformed) {
            // Discovery is exported. Invalid extras must not crash the app host.
            android.util.Log.w("TermuxAdmin", "Rejected malformed discovery request");
        }
    }
}
