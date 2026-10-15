// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.content.Context;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Client installation and SSH startup. No archive import is exposed. */
public final class Maintenance {
    static Bundle startSsh(Context context) throws Exception {
        requireNative(context);
        // A daemon alone remains in a cached app's freezer group on Android 16.
        // Keep the existing Termux foreground service running before launching it.
        context.startForegroundService(new android.content.Intent()
            .setClassName("com.termux", "com.termux.app.TermuxService"));
        File usr = new File(context.getFilesDir(), "usr");
        File home = new File(context.getFilesDir(), "home");
        File config = new File(usr, "etc/ssh/sshd_config");
        new File(usr, "tmp").mkdirs(); new File(usr, "var/run").mkdirs();
        ProcessBuilder builder = new ProcessBuilder(new File(usr, "bin/sshd").getPath(), "-f", config.getPath(),
            "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no", "-o", "PubkeyAuthentication=yes",
            "-o", "AuthenticationMethods=publickey");
        builder.environment().put("HOME", home.getPath());
        builder.environment().put("PREFIX", usr.getPath());
        builder.environment().put("PATH", new File(usr, "bin").getPath() + ":/system/bin");
        builder.environment().put("TMPDIR", new File(usr, "tmp").getPath());
        builder.environment().put("LD_LIBRARY_PATH", new File(usr, "lib").getPath());
        File preload = new File(usr, "lib/libtermux-exec.so");
        if (preload.isFile()) builder.environment().put("LD_PRELOAD", preload.getPath());
        Process process = builder.start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { terminate(process); throw new IOException("SSH startup timed out"); }
        Bundle result = new Bundle(); result.putInt("exit", process.exitValue()); return result;
    }
    public static void installClient(Context context) throws IOException {
        requireNative(context);
        File bin = new File(context.getFilesDir(), "home/bin");
        if (!bin.isDirectory() && !bin.mkdirs()) throw new IOException("Terminal command directory unavailable");
        String script = "#!/system/bin/sh\nset -eu\nunset LD_PRELOAD LD_LIBRARY_PATH\n"
            + "apk=$(/system/bin/pm path --user 0 com.termux </dev/null)\n"
            + "case \"$apk\" in package:/data/app/*/base.apk) export CLASSPATH=\"${apk#package:}\";; *) exit 70;; esac\n"
            + "exec /system/bin/app_process / com.termux.admin.Cli \"$@\"\n";
        ClientScriptWriter.write(bin, script.getBytes(StandardCharsets.UTF_8));
    }
    private static void requireNative(Context context) {
        if (!"com.termux".equals(context.getPackageName()) || !"com.termux".equals(Config.HOST_PACKAGE))
            throw new SecurityException("Maintenance requires the native Termux package");
    }
    private static void terminate(Process process) throws Exception {
        if (!process.isAlive()) return;
        java.lang.reflect.Field field = process.getClass().getDeclaredField("pid"); field.setAccessible(true);
        synchronized (process) {
            if (process.isAlive()) android.system.Os.kill(field.getInt(process), android.system.OsConstants.SIGKILL);
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("SSH child termination uncertain");
    }
}
