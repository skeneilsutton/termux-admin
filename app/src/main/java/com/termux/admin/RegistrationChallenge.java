// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Concurrent launchers share a pending token without extending its lifetime. */
final class RegistrationChallenge {
    private final SecureRandom random = new SecureRandom();
    private String pending;
    private long deadline;
    synchronized String issue(long now) {
        if (pending == null || now >= deadline) {
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            pending = value.toString(); deadline = now + 60000;
        }
        return pending;
    }
    synchronized boolean matches(String supplied, long now) {
        return supplied != null && pending != null && now < deadline &&
            MessageDigest.isEqual(pending.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    synchronized void clear() { pending = null; }
}
