// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

/** Fixed shell-only startup operation used by the independent watcher. */
public final class MaintenanceCli {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 2000) throw new SecurityException("Shell UID required");
            if (args.length != 1 || !"start-ssh".equals(args[0]))
                throw new IllegalArgumentException("Usage: start-ssh");
            android.content.Context context = Compat.context("com.android.shell");
            android.os.Bundle result = Compat.call(Compat.broker(context), "start-ssh", android.os.Bundle.EMPTY);
            System.exit(result.getInt("exit", 70));
        } catch (Throwable failure) {
            System.err.println("Maintenance failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.exit(70);
        }
    }
}
