package com.termux.admin;

import java.util.HashMap;
import java.util.Map;

public final class RuntimeEnvironmentTest {
    public static void main(String[] args) {
        Map<String, String> bootstrap = new HashMap<>();
        bootstrap.put("ANDROID_ROOT", "/system");
        bootstrap.put("ANDROID_ART_ROOT", "/apex/com.android.art");
        bootstrap.put("BOOTCLASSPATH", "/apex/com.android.art/javalib/core-oj.jar:/system/framework/framework.jar");
        bootstrap.put("DEX2OATBOOTCLASSPATH", "/apex/com.android.art/javalib/core-oj.jar");
        bootstrap.put("LD_PRELOAD", "/untrusted/loader.so");
        bootstrap.put("LD_LIBRARY_PATH", "/untrusted");
        bootstrap.put("CLASSPATH", "/untrusted/client.dex");
        bootstrap.put("JAVA_TOOL_OPTIONS", "-agentlib:untrusted");
        bootstrap.put("ANDROID_SOCKET_adbd", "27");
        bootstrap.put("PATH", "/untrusted");
        bootstrap.put("ANDROID_DATA", "");
        Map<String, String> child = new HashMap<>(bootstrap);
        child.put("LEFTOVER_SECRET", "must disappear");
        RuntimeEnvironment.populate(child, bootstrap);
        for (String key : new String[] {"ANDROID_ROOT", "ANDROID_ART_ROOT", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"})
            if (!bootstrap.get(key).equals(child.get(key))) throw new AssertionError("Missing runtime variable " + key);
        for (String key : new String[] {"LD_PRELOAD", "LD_LIBRARY_PATH", "CLASSPATH", "JAVA_TOOL_OPTIONS", "ANDROID_SOCKET_adbd", "LEFTOVER_SECRET", "ANDROID_DATA"})
            if (child.containsKey(key)) throw new AssertionError("Inherited forbidden variable " + key);
        if (!"/system/bin:/system/xbin".equals(child.get("PATH"))) throw new AssertionError("Untrusted PATH");
        if (!"/data/local/tmp".equals(child.get("HOME"))) throw new AssertionError("Untrusted HOME");
        if (child.size() != 8) throw new AssertionError("Unexpected inherited environment");
        System.out.println("Android runtime paths retained; loader flags, sockets and unrelated variables excluded");
    }
}
