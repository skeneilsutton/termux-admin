// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import java.util.Map;

/** Keep Android's boot runtime usable without inheriting loader or client flags. */
final class RuntimeEnvironment {
    static void populate(Map<String, String> target, Map<String, String> bootstrap) {
        target.clear();
        target.put("PATH", "/system/bin:/system/xbin");
        target.put("HOME", "/data/local/tmp");
        target.put("TMPDIR", "/data/local/tmp");
        target.put("LANG", "C.UTF-8");
        for (String key : new String[] {
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT",
            "ANDROID_TZDATA_ROOT", "ANDROID_ASSETS", "ANDROID_STORAGE",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH"
        }) {
            String value = bootstrap.get(key);
            if (value != null && !value.isEmpty()) target.put(key, value);
        }
    }
}
