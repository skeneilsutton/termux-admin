// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** The terminal sees policies, command arguments and completion, never IPC setup. */
public final class Admin {
    private final Context context;
    private final IBinder host;
    private Admin(Context context, IBinder host) {
        this.context = context;
        this.host = host;
    }
    public static Admin attach(Context context) throws Exception {
        if (!Config.HOST_CERT.equals(Compat.certificate(context, Config.HOST_PACKAGE)))
            throw new SecurityException("Admin host signer mismatch");
        return new Admin(context, Compat.broker(context));
    }
    Bundle call(String method, Bundle data) {
        return Compat.call(host, method, data);
    }
    public Availability status() {
        Bundle state = call("status", Bundle.EMPTY);
        return new Availability(state.getBoolean("owner"), state.getBoolean("active"),
            state.getInt("host_uid"), state.getInt("shell_uid", -1));
    }
    public PolicyResult apply(PolicyChange change) {
        try {
            Bundle result = call("policy", change.wire());
            return new PolicyResult(result.getString("disposition"), result.getString("detail"),
                result.getStringArrayList("packages"), result.getBoolean("enabled"),
                result.getStringArrayList("refused"));
        } catch (Throwable uncertain) {
            return new PolicyResult("unknown", uncertain.getClass().getSimpleName(), null, false, null);
        }
    }
    /** Submission happens once. Failure after submission is uncertainty, never a replay. */
    public Execution execute(ShellCommand command, Streams streams) {
        boolean submitted = false;
        boolean handedOff = false;
        ParcelFileDescriptor[] input = null, output = null, errors = null;
        try {
            IBinder shell = call("shell", Bundle.EMPTY).getBinder("service");
            if (shell == null) return Execution.finished(Completion.rejected("shell_unavailable"));
            input = ParcelFileDescriptor.createPipe();
            output = ParcelFileDescriptor.createPipe();
            errors = ParcelFileDescriptor.createPipe();
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            IBinder operation;
            IBinder lifetime = new android.os.Binder();
            try {
                request.writeInterfaceToken(Wire.SHELL);
                request.writeStringArray(command.argv);
                request.writeStrongBinder(lifetime);
                input[0].writeToParcel(request, 0);
                output[1].writeToParcel(request, 0);
                errors[1].writeToParcel(request, 0);
                submitted = true;
                if (!shell.transact(Wire.SUBMIT, request, reply, 0))
                    throw new IOException("Unsupported shell protocol");
                reply.readException();
                if (reply.readInt() != 0) {
                    close(input); close(output); close(errors);
                    return Execution.finished(Completion.rejected(reply.readString()));
                }
                operation = reply.readStrongBinder();
            } catch (Throwable failure) {
                close(input); close(output); close(errors);
                return Execution.finished(Completion.unknown(failure.getClass().getSimpleName()));
            } finally {
                request.recycle(); reply.recycle();
                close(input[0]); close(output[1]); close(errors[1]);
            }
            AtomicReference<Throwable> pumpError = new AtomicReference<>();
            Thread stdout = pump(new ParcelFileDescriptor.AutoCloseInputStream(output[0]),
                streams.stdout, false, pumpError);
            Thread stderr = pump(new ParcelFileDescriptor.AutoCloseInputStream(errors[0]),
                streams.stderr, false, pumpError);
            OutputStream inputWriter = new ParcelFileDescriptor.AutoCloseOutputStream(input[1]);
            if (streams.stdin == null) close(inputWriter);
            else pump(streams.stdin, inputWriter, true, pumpError);
            handedOff = true;
            return new Execution(operation, stdout, stderr, pumpError, inputWriter, lifetime);
        } catch (Throwable failure) {
            return Execution.finished(submitted ? Completion.unknown(failure.getClass().getSimpleName())
                : Completion.rejected(failure.getClass().getSimpleName()));
        } finally { if (!handedOff) { close(input); close(output); close(errors); } }
    }
    static Thread pump(InputStream input, OutputStream output, boolean closeOutput,
            AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[16384]; int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                output.flush();
            } catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { if (closeOutput) close(output); else close(input); }
        }, "termux-admin-stream");
        thread.setDaemon(true); thread.start(); return thread;
    }
    static void close(Closeable value) { try { if (value != null) value.close(); } catch (IOException ignored) {} }
    static void close(ParcelFileDescriptor[] values) { if (values != null) for (ParcelFileDescriptor value : values) close(value); }

    public static final class Availability {
        public final boolean deviceOwner, activeAdmin, shellReady;
        public final int hostUid, shellUid;
        Availability(boolean owner, boolean active, int hostUid, int shellUid) {
            deviceOwner = owner; activeAdmin = active; this.hostUid = hostUid;
            this.shellUid = shellUid; shellReady = shellUid == 2000;
        }
    }
    public static final class PackageName {
        public final String value;
        private PackageName(String value) { this.value = value; }
        public static PackageName parse(String value) {
            if (value == null || value.length() > 255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("Invalid package name");
            return new PackageName(value);
        }
    }
    public enum Restriction {
        DISALLOW_SAFE_BOOT("no_safe_boot"), DISALLOW_FACTORY_RESET("no_factory_reset"),
        DISALLOW_ADD_USER("no_add_user");
        final String androidName;
        Restriction(String name) { androidName = name; }
    }
    public static final class PolicyChange {
        enum Kind { USER_CONTROL_ADD, USER_CONTROL_REMOVE, SUSPEND, HIDE, BLOCK_UNINSTALL,
            RESTRICTION, CAMERA_DISABLED, SCREEN_CAPTURE_DISABLED, BACKUP_ENABLED }
        final Kind kind; final List<String> packages; final boolean enabled; final Restriction restriction;
        private PolicyChange(Kind kind, boolean enabled, Restriction restriction, PackageName... names) {
            this.kind = kind; this.enabled = enabled; this.restriction = restriction;
            List<String> copy = new ArrayList<>();
            for (PackageName name : names) if (!copy.contains(Objects.requireNonNull(name).value)) copy.add(name.value);
            if (copy.size() > 128) throw new IllegalArgumentException("Too many packages");
            packages = Collections.unmodifiableList(copy);
        }
        public static PolicyChange disableUserControl(PackageName... names) {
            return packageChange(Kind.USER_CONTROL_ADD, true, names);
        }
        public static PolicyChange enableUserControl(PackageName... names) {
            return packageChange(Kind.USER_CONTROL_REMOVE, false, names);
        }
        public static PolicyChange suspend(boolean enabled, PackageName... names) { return packageChange(Kind.SUSPEND, enabled, names); }
        public static PolicyChange hide(boolean enabled, PackageName... names) { return packageChange(Kind.HIDE, enabled, names); }
        public static PolicyChange blockUninstall(boolean enabled, PackageName... names) { return packageChange(Kind.BLOCK_UNINSTALL, enabled, names); }
        public static PolicyChange restrict(Restriction restriction, boolean enabled) {
            return new PolicyChange(Kind.RESTRICTION, enabled, Objects.requireNonNull(restriction));
        }
        public static PolicyChange cameraDisabled(boolean enabled) { return new PolicyChange(Kind.CAMERA_DISABLED, enabled, null); }
        public static PolicyChange screenCaptureDisabled(boolean enabled) { return new PolicyChange(Kind.SCREEN_CAPTURE_DISABLED, enabled, null); }
        public static PolicyChange backupEnabled(boolean enabled) { return new PolicyChange(Kind.BACKUP_ENABLED, enabled, null); }
        private static PolicyChange packageChange(Kind kind, boolean enabled, PackageName... names) {
            if (names.length == 0) throw new IllegalArgumentException("Packages required");
            return new PolicyChange(kind, enabled, null, names);
        }
        Bundle wire() {
            Bundle out = new Bundle(); out.putString("kind", kind.name());
            out.putStringArrayList("packages", new ArrayList<>(packages)); out.putBoolean("enabled", enabled);
            if (restriction != null) out.putString("restriction", restriction.name());
            return out;
        }
        static PolicyChange parse(Bundle wire) {
            Kind kind = Kind.valueOf(wire.getString("kind", ""));
            ArrayList<String> names = wire.getStringArrayList("packages");
            if (names == null || names.size() > 128) throw new IllegalArgumentException("Invalid packages");
            PackageName[] parsed = new PackageName[names.size()];
            for (int i = 0; i < parsed.length; i++) parsed[i] = PackageName.parse(names.get(i));
            if (kind.ordinal() <= Kind.BLOCK_UNINSTALL.ordinal() && parsed.length == 0)
                throw new IllegalArgumentException("Packages required");
            Restriction restriction = kind == Kind.RESTRICTION ? Restriction.valueOf(wire.getString("restriction", "")) : null;
            return new PolicyChange(kind, wire.getBoolean("enabled"), restriction, parsed);
        }
    }
    public static final class PolicyResult {
        public final String disposition, detail;
        public final List<String> packages, refused;
        public final boolean enabled;
        PolicyResult(String disposition, String detail, List<String> packages, boolean enabled, List<String> refused) {
            this.disposition = disposition; this.detail = detail;
            this.packages = Collections.unmodifiableList(packages == null ? Collections.emptyList() : packages);
            this.refused = Collections.unmodifiableList(refused == null ? Collections.emptyList() : refused);
            this.enabled = enabled;
        }
    }
    public static final class ShellCommand {
        final String[] argv;
        private ShellCommand(String[] argv) { this.argv = argv; }
        public static ShellCommand argv(String... argv) {
            if (argv == null || argv.length == 0 || argv.length > 256) throw new IllegalArgumentException("Invalid argv");
            int total = 0;
            for (String arg : argv) {
                if (arg == null || arg.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid argument");
                total += arg.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if (argv[0].isEmpty() || total > 65536) throw new IllegalArgumentException("Invalid command size");
            return new ShellCommand(argv.clone());
        }
    }
    public static final class Streams {
        final InputStream stdin; final OutputStream stdout, stderr;
        public Streams(InputStream stdin, OutputStream stdout, OutputStream stderr) {
            this.stdin = stdin; this.stdout = Objects.requireNonNull(stdout); this.stderr = Objects.requireNonNull(stderr);
        }
    }
    public static final class Completion {
        public enum Kind { EXITED, REJECTED, UNKNOWN, STREAM_FAILED }
        public final Kind kind; public final int exitCode; public final String detail;
        Completion(Kind kind, int code, String detail) { this.kind = kind; exitCode = code; this.detail = detail; }
        static Completion rejected(String detail) { return new Completion(Kind.REJECTED, -1, detail); }
        static Completion unknown(String detail) { return new Completion(Kind.UNKNOWN, -1, detail); }
    }
    public static final class Execution {
        final IBinder operation; final Thread stdout, stderr; final AtomicReference<Throwable> streamError;
        final Closeable inputWriter; private Completion completion;
        final IBinder lifetime;
        Execution(IBinder operation, Thread stdout, Thread stderr, AtomicReference<Throwable> error, Closeable inputWriter, IBinder lifetime) {
            this.operation = operation; this.stdout = stdout; this.stderr = stderr; streamError = error; this.inputWriter = inputWriter;
            this.lifetime = lifetime;
        }
        static Execution finished(Completion completion) {
            Execution out = new Execution(null, null, null, null, null, null); out.completion = completion; return out;
        }
        public synchronized Completion await() throws InterruptedException {
            if (completion != null) return completion;
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(Wire.RUN);
                if (!operation.transact(Wire.WAIT, request, reply, 0)) throw new IOException("Missing completion protocol");
                reply.readException(); int code = reply.readInt(); boolean complete = reply.readInt() == 1;
                stdout.join(10000); stderr.join(10000);
                completion = !complete || stdout.isAlive() || stderr.isAlive() || streamError.get() != null
                    ? new Completion(Completion.Kind.STREAM_FAILED, code, "Output capture incomplete")
                    : new Completion(Completion.Kind.EXITED, code, "");
            } catch (InterruptedException interrupted) { cancel(); throw interrupted; }
            catch (Throwable failure) { completion = Completion.unknown(failure.getClass().getSimpleName()); }
            finally { request.recycle(); reply.recycle(); close(inputWriter); }
            return completion;
        }
        public void cancel() {
            if (operation == null) return;
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try { request.writeInterfaceToken(Wire.RUN); operation.transact(Wire.CANCEL, request, reply, 0); reply.readException(); }
            catch (Throwable ignored) {} finally { request.recycle(); reply.recycle(); close(inputWriter); }
        }
    }
}

final class Wire {
    static final String HOST = "com.termux.admin.host.v1", CALLBACK = "com.termux.admin.callback.v1";
    static final int CALL = 1;
    static final String SHELL = "com.termux.admin.shell.v1", RUN = "com.termux.admin.run.v1";
    static final int PROBE = 1, SUBMIT = 2, REVOKE = 3, WAIT = 1, CANCEL = 2;
}
