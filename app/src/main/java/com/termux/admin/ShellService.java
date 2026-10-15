// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Narrow shell service. No arbitrary Binder proxy, root mode or network listener. */
public final class ShellService extends Binder {
    private static ShellService runningServer;
    private final int clientUid, hostUid;
    private final Context context;
    private final Semaphore capacity = new Semaphore(4);
    private final Handler lifecycle = new Handler(Looper.getMainLooper());
    private final java.lang.reflect.Field childPidField;
    private IBinder epochGuard;
    private final ProcessLease lease;
    private final File readyPid;
    private volatile boolean active = true;
    private ShellService(Context context, int clientUid, int hostUid, ProcessLease lease) throws Exception {
        this.context = context; this.clientUid = clientUid; this.hostUid = hostUid; this.lease = lease;
        readyPid = new File("/data/local/tmp/termux-admin-recovery/worker-" + Config.HOST_PACKAGE + ".pid");
        // Android's UNIXProcess inherits destroyForcibly(), which only calls destroy()/SIGTERM.
        // Resolve and test this platform adapter before admitting commands.
        childPidField = Class.forName("java.lang.UNIXProcess").getDeclaredField("pid");
        childPidField.setAccessible(true);
        java.lang.Process probe = new ProcessBuilder("/system/bin/true").start();
        if (childPidField.getInt(probe) <= 0) throw new IllegalStateException("Child PID unavailable");
        probe.waitFor();
    }
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 2000) throw new SecurityException("Shell UID required");
            if (args.length != 1 || !(args[0].matches("[0-9a-f]{64}") || "--recover".equals(args[0])))
                throw new IllegalArgumentException("Bootstrap challenge required");
            File base = new File("/data/local/tmp/termux-admin-recovery");
            if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Recovery directory unavailable");
            android.system.Os.chmod(base.getPath(), 0700);
            File path = new File(base, "worker-" + Config.HOST_PACKAGE + ".lock");
            try (ProcessLease lease = ProcessLease.acquire(path)) {
                if (lease == null) { System.out.println("termux-admin shell already starting or running"); return; }
                android.system.Os.chmod(path.getPath(), 0600);
                serve(args, lease);
            }
        } catch (Throwable failure) {
            System.err.println("termux-admin service: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.exit(1);
        }
    }
    private static void serve(String[] args, ProcessLease lease) throws Exception {
            Context context = Compat.context("com.android.shell");
            if (!Config.HOST_CERT.equals(Compat.certificate(context, Config.HOST_PACKAGE)))
                throw new SecurityException("Host signer mismatch");
            int hostUid = context.getPackageManager().getApplicationInfo(Config.HOST_PACKAGE, 0).uid;
            int clientUid = context.getPackageManager().getApplicationInfo(Config.CLIENT_PACKAGE, 0).uid;
            if (hostUid != clientUid && !Config.CLIENT_CERT.equals(Compat.certificate(context, Config.CLIENT_PACKAGE)))
                throw new SecurityException("Client signer mismatch");
            ShellService server = new ShellService(context, clientUid, hostUid, lease);
            runningServer = server;
            IBinder host = Compat.broker(context);
            String challenge = args[0];
            if ("--recover".equals(challenge)) {
                Bundle bootstrap = Compat.call(host, "bootstrap", Bundle.EMPTY);
                if (bootstrap.getBoolean("ready")) { System.exit(0); return; }
                challenge = bootstrap.getString("challenge");
            }
            Bundle data = new Bundle(); data.putBinder("service", server); data.putString("challenge", challenge);
            Bundle answer = Compat.call(host, "register", data);
            if (answer == null || !answer.getBoolean("registered")) throw new IllegalStateException("Registration failed");
            server.epochGuard = answer.getBinder("epoch_guard");
            if (server.epochGuard == null) throw new IllegalStateException("Missing host epoch");
            server.epochGuard.linkToDeath(() -> server.retire(), 0);
            synchronized (server) {
                if (!server.active) throw new IllegalStateException("Host died during registration");
                try (OutputStream out = new FileOutputStream(server.readyPid)) {
                    out.write((android.os.Process.myPid() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            System.out.println("termux-admin shell registered uid=2000 pid=" + android.os.Process.myPid());
            Looper.loop();
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(Wire.SHELL); return true; }
        data.enforceInterface(Wire.SHELL);
        int caller = Binder.getCallingUid();
        if (code == Wire.REVOKE) {
            if (caller != hostUid) throw new SecurityException("Revocation UID refused");
            retire(); reply.writeNoException(); return true;
        }
        if (code == Wire.PROBE) {
            if (caller != clientUid && caller != hostUid) throw new SecurityException("Shell probe UID refused");
            reply.writeNoException(); reply.writeInt(active ? android.os.Process.myUid() : -1); return true;
        }
        if (code != Wire.SUBMIT) return super.onTransact(code, data, reply, flags);
        admitExecution(caller);
        if (!active) { reply.writeNoException(); reply.writeInt(1); reply.writeString("shell_epoch_revoked"); return true; }
        Admin.ShellCommand command = Admin.ShellCommand.argv(data.createStringArray());
        IBinder lifetime = data.readStrongBinder();
        if (lifetime == null || !lifetime.isBinderAlive()) throw new IllegalArgumentException("Live client token required");
        ParcelFileDescriptor input = null, output = null, error = null;
        try {
            input = ParcelFileDescriptor.CREATOR.createFromParcel(data);
            output = ParcelFileDescriptor.CREATOR.createFromParcel(data);
            error = ParcelFileDescriptor.CREATOR.createFromParcel(data);
        } catch (Throwable invalid) {
            Admin.close(input); Admin.close(output); Admin.close(error); throw new IllegalArgumentException("Invalid stream descriptors");
        }
        if (!capacity.tryAcquire()) {
            Admin.close(input); Admin.close(output); Admin.close(error);
            reply.writeNoException(); reply.writeInt(1); reply.writeString("shell_busy"); return true;
        }
        if (!active) {
            capacity.release(); finishRetirement();
            Admin.close(input); Admin.close(output); Admin.close(error);
            reply.writeNoException(); reply.writeInt(1); reply.writeString("shell_epoch_revoked"); return true;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command.argv);
            builder.directory(new File("/data/local/tmp"));
            RuntimeEnvironment.populate(builder.environment(), System.getenv());
            java.lang.Process child = builder.start();
            Run run = new Run(child, input, output, error, lifetime);
            reply.writeNoException(); reply.writeInt(0); reply.writeStrongBinder(run); return true;
        } catch (IOException failure) {
            capacity.release(); finishRetirement(); Admin.close(input); Admin.close(output); Admin.close(error);
            reply.writeNoException(); reply.writeInt(1); reply.writeString("spawn_failed"); return true;
        }
    }
    private synchronized void retire() {
        if (!active) return;
        active = false;
        // Old commands may finish, but this epoch accepts no new ones. Release
        // startup ownership now so a replacement need not wait for those commands.
        if (!readyPid.delete() && readyPid.exists()) System.err.println("termux-admin worker readiness cleanup failed");
        try { lease.close(); }
        catch (IOException failure) { System.err.println("termux-admin worker lock cleanup failed"); }
        System.out.println("termux-admin shell epoch retired pid=" + android.os.Process.myPid());
        finishRetirement();
    }
    private void finishRetirement() {
        lifecycle.postDelayed(() -> {
            if (!active && capacity.availablePermits() == 4) System.exit(0);
        }, 200);
    }
    private void admitExecution(int caller) {
        try {
            if (caller != clientUid || context.getPackageManager().getApplicationInfo(Config.CLIENT_PACKAGE, 0).uid != caller)
                throw new SecurityException("Shell execution UID refused");
            String expected = hostUid == clientUid ? Config.HOST_CERT : Config.CLIENT_CERT;
            if (!expected.equals(Compat.certificate(context, Config.CLIENT_PACKAGE)))
                throw new SecurityException("Shell execution signer refused");
        } catch (Exception refused) { throw new SecurityException("Shell execution UID refused"); }
    }
    private final class Run extends Binder {
        final java.lang.Process child;
        final int childPid;
        final AtomicReference<Throwable> streamError = new AtomicReference<>();
        final Thread stdout, stderr;
        final ParcelFileDescriptor input;
        final CountDownLatch finished = new CountDownLatch(1);
        volatile int exitCode = -1; volatile boolean captureComplete, stopping;
        final java.util.concurrent.atomic.AtomicBoolean stoppingChild = new java.util.concurrent.atomic.AtomicBoolean();
        final ParcelFileDescriptor output, error;
        final IBinder lifetime;
        final IBinder.DeathRecipient clientDeath = () -> stopChild();
        Run(java.lang.Process child, ParcelFileDescriptor input, ParcelFileDescriptor output, ParcelFileDescriptor error, IBinder lifetime) {
            this.child = child; this.input = input; this.output = output; this.error = error;
            try { childPid = childPidField.getInt(child); }
            catch (IllegalAccessException unavailable) { throw new IllegalStateException("Child PID unavailable", unavailable); }
            this.lifetime = lifetime;
            try { lifetime.linkToDeath(clientDeath, 0); }
            catch (RemoteException dead) { stopChild(); }
            Admin.pump(new ParcelFileDescriptor.AutoCloseInputStream(input), child.getOutputStream(), true, streamError);
            stdout = deliver(child.getInputStream(), output);
            stderr = deliver(child.getErrorStream(), error);
            Thread wait = new Thread(() -> {
                try {
                    exitCode = child.waitFor(); stdout.join(10000); stderr.join(10000);
                    captureComplete = !stdout.isAlive() && !stderr.isAlive() && streamError.get() == null;
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); captureComplete = false; }
                finally {
                    lifetime.unlinkToDeath(clientDeath, 0);
                    stopping = true; Admin.close(input); Admin.close(output); Admin.close(error);
                    capacity.release(); finished.countDown(); finishRetirement();
                    // Inherited pipes can leave readers blocked. Never hold completion or capacity for their locks.
                    Thread cleanup = new Thread(() -> {
                        Admin.close(child.getInputStream()); Admin.close(child.getErrorStream());
                        Admin.close(child.getOutputStream());
                    }, "termux-admin-stream-close"); cleanup.setDaemon(true); cleanup.start();
                }
            }, "termux-admin-child"); wait.setDaemon(true); wait.start();
        }
        /** Nonblocking writes make client backpressure and disconnect cleanup bounded. */
        private Thread deliver(InputStream source, ParcelFileDescriptor target) {
            Thread thread = new Thread(() -> {
                try {
                    FileDescriptor descriptor = target.getFileDescriptor();
                    int current = android.system.Os.fcntlInt(descriptor, android.system.OsConstants.F_GETFL, 0);
                    android.system.Os.fcntlInt(descriptor, android.system.OsConstants.F_SETFL, current | android.system.OsConstants.O_NONBLOCK);
                    byte[] buffer = new byte[16384]; int size;
                    while ((size = source.read(buffer)) != -1) {
                        int offset = 0; long deadline = SystemClock.elapsedRealtime() + 30000;
                        while (offset < size) {
                            if (stopping) throw new IOException("Output cancelled");
                            try { offset += android.system.Os.write(descriptor, buffer, offset, size - offset); }
                            catch (android.system.ErrnoException blocked) {
                                if (blocked.errno != android.system.OsConstants.EAGAIN) throw blocked;
                                if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Output backpressure timeout");
                                android.system.StructPollfd poll = new android.system.StructPollfd();
                                poll.fd = descriptor; poll.events = (short)android.system.OsConstants.POLLOUT;
                                android.system.Os.poll(new android.system.StructPollfd[] {poll}, 250);
                            }
                        }
                    }
                } catch (Throwable failure) { streamError.compareAndSet(null, failure); stopChild(); }
                finally { Admin.close(target); }
            }, "termux-admin-delivery"); thread.setDaemon(true); thread.start(); return thread;
        }
        private void stopChild() {
            if (!stoppingChild.compareAndSet(false, true)) return;
            Thread escalation = new Thread(() -> {
                try { if (!child.waitFor(2, TimeUnit.SECONDS)) forceKill(); }
                catch (InterruptedException interrupted) { forceKill(); Thread.currentThread().interrupt(); }
            }, "termux-admin-stop"); escalation.setDaemon(true); escalation.start();
            // Process.destroy can block closing a stream held by a reader. Arm force-kill first.
            Thread graceful = new Thread(() -> child.destroy(), "termux-admin-terminate");
            graceful.setDaemon(true); graceful.start();
        }
        private void forceKill() {
            synchronized (child) {
                try { child.exitValue(); return; }
                catch (IllegalThreadStateException running) { /* The process reaper has not observed exit. */ }
                try { android.system.Os.kill(childPid, android.system.OsConstants.SIGKILL); }
                catch (android.system.ErrnoException failure) {
                    if (failure.errno != android.system.OsConstants.ESRCH) {
                        streamError.compareAndSet(null, failure);
                        System.err.println("termux-admin child termination failed: " + failure.errno);
                    }
                }
            }
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(Wire.RUN); return true; }
            admitExecution(Binder.getCallingUid());
            data.enforceInterface(Wire.RUN);
            if (code == Wire.CANCEL) { stopChild(); reply.writeNoException(); return true; }
            if (code == Wire.WAIT) {
                try { finished.await(); } catch (InterruptedException interrupted) { throw new IllegalStateException("Wait interrupted"); }
                reply.writeNoException(); reply.writeInt(exitCode); reply.writeInt(captureComplete ? 1 : 0); return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
