// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import android.content.Context;
import android.os.Bundle;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

public final class Cli {
    public static void main(String[] args) {
        int exit = 0;
        try {
            if (args.length == 0) throw new IllegalArgumentException("Usage: termux-admin status|owner|shell");
            Context context = Compat.context(Config.CLI_PACKAGE);
            Admin admin = Admin.attach(context);
            if ("status".equals(args[0]) && args.length == 1) {
                Admin.Availability state = admin.status(); JSONObject json = new JSONObject();
                json.put("device_owner", state.deviceOwner); json.put("active_admin", state.activeAdmin);
                json.put("host_uid", state.hostUid); json.put("shell_uid", state.shellUid);
                json.put("shell_ready", state.shellReady); System.out.println(json);
            } else if ("_challenge".equals(args[0]) && args.length == 1) {
                System.out.println(admin.call("challenge", Bundle.EMPTY).getString("challenge"));
            } else if ("shell".equals(args[0]) && args.length > 2 && "--".equals(args[1])) {
                Admin.Completion result = admin.execute(Admin.ShellCommand.argv(Arrays.copyOfRange(args, 2, args.length)),
                    new Admin.Streams(new FileInputStream(FileDescriptor.in),
                        new FileOutputStream(FileDescriptor.out), new FileOutputStream(FileDescriptor.err))).await();
                if (result.kind == Admin.Completion.Kind.EXITED) exit = result.exitCode;
                else { System.err.println("termux-admin " + result.kind + ": " + result.detail + "; command will not be retried"); exit = 70; }
            } else if ("owner".equals(args[0])) {
                if (args.length == 3 && "transfer-to".equals(args[1])) {
                    Bundle target = new Bundle(); target.putString("target", args[2]);
                    Bundle receipt = admin.call("transfer", target);
                    if (!receipt.getBoolean("transferred")) throw new IllegalStateException("Ownership transfer outcome unknown; not retried");
                    System.out.println("Ownership transfer observed"); System.exit(0); return;
                }
                Admin.PolicyChange change;
                if (args.length >= 4 && "user-control-disabled".equals(args[1])) {
                    Admin.PackageName[] names = new Admin.PackageName[args.length - 3];
                    for (int i = 0; i < names.length; i++) names[i] = Admin.PackageName.parse(args[i + 3]);
                    if ("add".equals(args[2])) change = Admin.PolicyChange.disableUserControl(names);
                    else if ("remove".equals(args[2])) change = Admin.PolicyChange.enableUserControl(names);
                    else throw new IllegalArgumentException("Expected add or remove");
                } else if (args.length == 4 && ("suspend".equals(args[1]) || "hide".equals(args[1]) || "block-uninstall".equals(args[1]))) {
                    boolean value = bool(args[2]); Admin.PackageName name = Admin.PackageName.parse(args[3]);
                    change = "suspend".equals(args[1]) ? Admin.PolicyChange.suspend(value, name)
                        : "hide".equals(args[1]) ? Admin.PolicyChange.hide(value, name) : Admin.PolicyChange.blockUninstall(value, name);
                } else if (args.length == 4 && "restriction".equals(args[1])) {
                    change = Admin.PolicyChange.restrict(Admin.Restriction.valueOf(args[2]), bool(args[3]));
                } else if (args.length == 3) {
                    boolean value = bool(args[2]);
                    if ("camera-disabled".equals(args[1])) change = Admin.PolicyChange.cameraDisabled(value);
                    else if ("screen-capture-disabled".equals(args[1])) change = Admin.PolicyChange.screenCaptureDisabled(value);
                    else if ("backup-enabled".equals(args[1])) change = Admin.PolicyChange.backupEnabled(value);
                    else throw new IllegalArgumentException("Unknown owner operation");
                } else throw new IllegalArgumentException("Invalid owner arguments");
                Admin.PolicyResult result = admin.apply(change); JSONObject json = new JSONObject();
                json.put("disposition", result.disposition); json.put("detail", result.detail);
                json.put("packages", new org.json.JSONArray(result.packages)); json.put("refused", new org.json.JSONArray(result.refused));
                json.put("enabled", result.enabled); System.out.println(json);
                exit = "observed".equals(result.disposition) ? 0 : 77;
            } else throw new IllegalArgumentException("Invalid command");
        } catch (IllegalArgumentException syntax) { System.err.println(syntax.getMessage()); exit = 64; }
        catch (SecurityException refused) { System.err.println(refused.getMessage()); exit = 77; }
        catch (Throwable failure) { System.err.println(failure.getClass().getSimpleName() + ": " + failure.getMessage()); exit = 70; }
        System.exit(exit);
    }
    static boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("Expected true or false");
    }
}
