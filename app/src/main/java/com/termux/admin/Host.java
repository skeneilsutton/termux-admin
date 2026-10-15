// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.*;
import java.util.*;

/** Authenticate first, then parse. Shell registration and terminal calls have separate roles. */
final class Host extends Binder {
    private static Host instance;
    private final Context context;
    private IBinder shell;
    private final RegistrationChallenge challenge = new RegistrationChallenge();
    private int clientUid;
    private final IBinder epochGuard = new Binder();
    static synchronized Host get(Context context) {
        if (instance == null) instance = new Host(context.getApplicationContext());
        return instance;
    }
    private Context getContext() { return context; }
    private Host(Context context) {
        this.context = context;
        try {
            clientUid = context.getPackageManager().getApplicationInfo(Config.CLIENT_PACKAGE, 0).uid;
            if (clientUid != android.os.Process.myUid() && !Config.CLIENT_CERT.equals(Compat.certificate(context, Config.CLIENT_PACKAGE)))
                throw new SecurityException("Terminal signer mismatch");
        } catch (Exception failure) { throw new IllegalStateException("Admin client identity unavailable", failure); }
    }
    private void admit() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        try {
            if (uid != clientUid || getContext().getPackageManager().getApplicationInfo(Config.CLIENT_PACKAGE, 0).uid != uid
                || !Config.CLIENT_CERT.equals(Compat.certificate(getContext(), Config.CLIENT_PACKAGE)))
                throw new SecurityException("Admin caller UID refused");
        } catch (Exception refused) { throw new SecurityException("Admin caller UID refused"); }
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(Wire.HOST); return true; }
        if (code != Wire.CALL) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(Wire.HOST);
        String method = data.readString();
        if (method == null || method.length() > 64) throw new IllegalArgumentException("Invalid operation");
        if ("register".equals(method) || "bootstrap".equals(method) || "start-ssh".equals(method)) {
            if (Binder.getCallingUid() != 2000) throw new SecurityException("Shell registration UID refused");
        } else admit();
        Bundle extras = data.readBundle(Host.class.getClassLoader());
        Bundle result = call(method, extras);
        reply.writeNoException(); reply.writeBundle(result);
        return true;
    }
    private synchronized Bundle call(String method, Bundle extras) {
        if ("register".equals(method)) {
            if (Binder.getCallingUid() != 2000) throw new SecurityException("Shell registration UID refused");
            IBinder incoming = extras == null ? null : extras.getBinder("service");
            String supplied = extras == null ? null : extras.getString("challenge");
            if (incoming == null || !challenge.matches(supplied, SystemClock.elapsedRealtime()))
                throw new SecurityException("Shell registration challenge refused");
            if (probe(incoming) != 2000) throw new SecurityException("Shell identity proof failed");
            try {
                incoming.linkToDeath(() -> { synchronized (Host.this) { if (shell == incoming) shell = null; } }, 0);
            } catch (RemoteException dead) { throw new IllegalStateException("Shell already dead"); }
            if (shell != null && shell != incoming) revoke(shell);
            shell = incoming; challenge.clear();
            Bundle out = new Bundle(); out.putBoolean("registered", true); out.putBinder("epoch_guard", epochGuard); return out;
        }
        if ("challenge".equals(method) || "bootstrap".equals(method)) {
            if ("bootstrap".equals(method) && probe(shell) == 2000) {
                Bundle ready = new Bundle(); ready.putBoolean("ready", true); return ready;
            }
            Bundle out = new Bundle(); out.putString("challenge", challenge.issue(SystemClock.elapsedRealtime())); return out;
        }
        if ("shell".equals(method)) {
            Bundle out = new Bundle(); if (probe(shell) == 2000) out.putBinder("service", shell); return out;
        }
        if ("status".equals(method)) {
            long identity = Binder.clearCallingIdentity();
            try {
                DevicePolicyManager manager = manager(); Bundle out = new Bundle();
                out.putBoolean("owner", manager.isDeviceOwnerApp(getContext().getPackageName()));
                out.putBoolean("active", manager.isAdminActive(admin()));
                out.putInt("host_uid", android.os.Process.myUid()); out.putInt("shell_uid", probe(shell));
                return out;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
        if ("policy".equals(method)) {
            Admin.PolicyChange change = Admin.PolicyChange.parse(Objects.requireNonNull(extras));
            long identity = Binder.clearCallingIdentity();
            try { return apply(change); } finally { Binder.restoreCallingIdentity(identity); }
        }
        if ("start-ssh".equals(method)) {
            long identity = Binder.clearCallingIdentity();
            try { return Maintenance.startSsh(context); }
            catch (Exception failure) { throw new IllegalStateException("Maintenance failed", failure); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        if ("transfer".equals(method)) {
            ComponentName target = ComponentName.unflattenFromString(extras.getString("target", ""));
            if (target == null || target.equals(admin())) throw new IllegalArgumentException("Transfer target refused");
            Admin.PackageName.parse(target.getPackageName());
            long identity = Binder.clearCallingIdentity();
            try {
                DevicePolicyManager manager = manager();
                if (!manager.isDeviceOwnerApp(context.getPackageName())) throw new SecurityException("Device ownership required");
                // Android validates the receiver and transfer-support metadata itself.
                // Android 16 can activate an eligible receiver as part of the transfer.
                manager.transferOwnership(admin(), target, null);
                Bundle receipt = new Bundle(); receipt.putBoolean("transferred", manager.isDeviceOwnerApp(target.getPackageName()));
                return receipt;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
        throw new IllegalArgumentException("Unknown admin operation");
    }
    private int probe(IBinder value) {
        if (value == null || !value.isBinderAlive()) return -1;
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(Wire.SHELL);
            if (!value.transact(Wire.PROBE, request, reply, 0)) return -1;
            reply.readException(); return reply.readInt();
        } catch (Exception unavailable) { return -1; }
        finally { request.recycle(); reply.recycle(); }
    }
    private void revoke(IBinder value) {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try { request.writeInterfaceToken(Wire.SHELL); value.transact(Wire.REVOKE, request, reply, 0); reply.readException(); }
        catch (Exception ignored) {} finally { request.recycle(); reply.recycle(); }
    }
    private DevicePolicyManager manager() { return (DevicePolicyManager)getContext().getSystemService(Context.DEVICE_POLICY_SERVICE); }
    private ComponentName admin() { return new ComponentName(getContext(), AdminReceiver.class); }
    private Bundle apply(Admin.PolicyChange change) {
        DevicePolicyManager manager = manager(); Bundle out = new Bundle();
        if (!manager.isDeviceOwnerApp(getContext().getPackageName())) {
            out.putString("disposition", "rejected"); out.putString("detail", "not_device_owner"); return out;
        }
        List<String> refused = new ArrayList<>(); List<String> observed = new ArrayList<>();
        boolean enabled = change.enabled;
        try {
            switch (change.kind) {
                case USER_CONTROL_ADD: case USER_CONTROL_REMOVE:
                    if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("Android 11 required");
                    Set<String> requested = new LinkedHashSet<>(manager.getUserControlDisabledPackages(admin()));
                    if (change.kind == Admin.PolicyChange.Kind.USER_CONTROL_ADD) requested.addAll(change.packages);
                    else requested.removeAll(change.packages);
                    manager.setUserControlDisabledPackages(admin(), new ArrayList<>(requested));
                    observed.addAll(manager.getUserControlDisabledPackages(admin()));
                    for (String name : change.packages) if (observed.contains(name) != enabled) refused.add(name);
                    break;
                case SUSPEND:
                    Collections.addAll(refused, manager.setPackagesSuspended(admin(), change.packages.toArray(new String[0]), enabled));
                    for (String name : change.packages) {
                        if (manager.isPackageSuspended(admin(), name) == enabled) observed.add(name);
                        else if (!refused.contains(name)) refused.add(name);
                    }
                    break;
                case HIDE: case BLOCK_UNINSTALL:
                    for (String name : change.packages) {
                        boolean actual;
                        if (change.kind == Admin.PolicyChange.Kind.HIDE) {
                            manager.setApplicationHidden(admin(), name, enabled); actual = manager.isApplicationHidden(admin(), name);
                        } else { manager.setUninstallBlocked(admin(), name, enabled); actual = manager.isUninstallBlocked(admin(), name); }
                        if (actual == enabled) observed.add(name); else refused.add(name);
                    }
                    break;
                case RESTRICTION:
                    if (enabled) manager.addUserRestriction(admin(), change.restriction.androidName);
                    else manager.clearUserRestriction(admin(), change.restriction.androidName);
                    if (manager.getUserRestrictions(admin()).getBoolean(change.restriction.androidName) != enabled)
                        throw new IllegalStateException("Policy readback disagrees");
                    break;
                case CAMERA_DISABLED:
                    manager.setCameraDisabled(admin(), enabled); enabled = manager.getCameraDisabled(admin());
                    if (enabled != change.enabled) throw new IllegalStateException("Policy readback disagrees"); break;
                case SCREEN_CAPTURE_DISABLED:
                    manager.setScreenCaptureDisabled(admin(), enabled); enabled = manager.getScreenCaptureDisabled(admin());
                    if (enabled != change.enabled) throw new IllegalStateException("Policy readback disagrees"); break;
                case BACKUP_ENABLED:
                    manager.setBackupServiceEnabled(admin(), enabled); enabled = manager.isBackupServiceEnabled(admin());
                    if (enabled != change.enabled) throw new IllegalStateException("Policy readback disagrees"); break;
            }
            out.putString("disposition", refused.isEmpty() ? "observed" : "partial");
            out.putStringArrayList("packages", new ArrayList<>(observed));
            out.putStringArrayList("refused", new ArrayList<>(refused)); out.putBoolean("enabled", enabled);
        } catch (Throwable uncertain) {
            // A call may have applied before readback failed. Never repeat it here.
            out.putString("disposition", "unknown"); out.putString("detail", uncertain.getClass().getSimpleName());
        }
        return out;
    }
}
