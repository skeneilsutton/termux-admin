// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.content.Context;
import android.os.Looper;
import java.security.MessageDigest;

/** app_process has no Application. Keep the tested framework adapter in one place. */
final class Compat {
    static Context context(String packageName) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
        Context own = system.createPackageContext(packageName, 0);
        if (own.getPackageManager().getApplicationInfo(packageName, 0).uid != android.os.Process.myUid())
            throw new SecurityException("Context package does not belong to this UID");
        return own;
    }
    @SuppressWarnings("deprecation")
    static String certificate(Context context, String packageName) throws Exception {
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
            packageName, android.content.pm.PackageManager.GET_SIGNATURES);
        if (info.signatures == null || info.signatures.length != 1)
            throw new SecurityException("Unsupported signing identity");
        return hex(MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray()));
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    /** Standalone app_process has no registered application thread. Obtain a handle
     * through an explicit callback, then authenticate on every real transaction. */
    static android.os.IBinder broker(Context context) throws Exception {
        int expectedUid = context.getPackageManager().getApplicationInfo(Config.HOST_PACKAGE, 0).uid;
        if (!Config.HOST_CERT.equals(certificate(context, Config.HOST_PACKAGE)))
            throw new SecurityException("Host signer mismatch");
        byte[] bytes = new byte[16]; new java.security.SecureRandom().nextBytes(bytes);
        final String discovery = hex(bytes);
        final long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<android.os.IBinder> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        android.os.Binder callback = new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                // Capture and check sender while still on the Binder transaction thread.
                int caller = android.os.Binder.getCallingUid();
                if (code != 1 || caller != expectedUid || android.os.SystemClock.elapsedRealtime() > deadline) return false;
                data.enforceInterface(Wire.CALLBACK);
                if (!discovery.equals(data.readString())) return false;
                android.os.IBinder handle = data.readStrongBinder();
                if (handle != null && accepted.compareAndSet(null, handle)) received.countDown();
                return true;
            }
        };
        android.os.Bundle payload = new android.os.Bundle();
        payload.putBinder("callback", callback); payload.putString("discovery", discovery);
        android.content.Intent intent = new android.content.Intent(Config.HOST_PACKAGE + ".DISCOVER")
            .setComponent(new android.content.ComponentName(Config.HOST_PACKAGE, HostReceiver.class.getName()))
            .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES | android.content.Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("data", payload);
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        android.os.IBinder activity = (android.os.IBinder)serviceManager.getMethod("getService", String.class).invoke(null, "activity");
        Object manager = Class.forName("android.app.IActivityManager$Stub")
            .getMethod("asInterface", android.os.IBinder.class).invoke(null, activity);
        // Exact deprecated signature used by the working pinned rish loader.
        Class<?> thread = Class.forName("android.app.IApplicationThread");
        Class<?> receiver = Class.forName("android.content.IIntentReceiver");
        Class.forName("android.app.IActivityManager").getMethod("broadcastIntent", thread,
            android.content.Intent.class, String.class, receiver, int.class, String.class,
            android.os.Bundle.class, String[].class, int.class, android.os.Bundle.class,
            boolean.class, boolean.class, int.class).invoke(manager, null, intent, null,
                null, 0, null, null, null, -1, null, false, false, 0);
        if (!received.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Host discovery timed out");
        return accepted.get();
    }
    static android.os.Bundle call(android.os.IBinder host, String method, android.os.Bundle data) {
        android.os.Parcel request = android.os.Parcel.obtain(), reply = android.os.Parcel.obtain();
        try {
            request.writeInterfaceToken(Wire.HOST); request.writeString(method); request.writeBundle(data);
            if (!host.transact(Wire.CALL, request, reply, 0)) throw new IllegalStateException("Unsupported host protocol");
            reply.readException(); android.os.Bundle result = reply.readBundle(Compat.class.getClassLoader());
            if (result == null) throw new IllegalStateException("Missing host response");
            return result;
        } catch (android.os.RemoteException lost) { throw new IllegalStateException("Host response lost", lost); }
        finally { request.recycle(); reply.recycle(); }
    }
}
