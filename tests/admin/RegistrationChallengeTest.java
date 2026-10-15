package com.termux.admin;

import java.util.*;
import java.util.concurrent.*;

public final class RegistrationChallengeTest {
    public static void main(String[] args) throws Exception {
        RegistrationChallenge value = new RegistrationChallenge();
        ExecutorService workers = Executors.newFixedThreadPool(16);
        Set<String> tokens = new HashSet<>();
        try {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 128; i++) calls.add(workers.submit(() -> value.issue(1000)));
            for (Future<String> call : calls) tokens.add(call.get());
        } finally { workers.shutdownNow(); }
        check(tokens.size() == 1, "Concurrent requests invalidated an outstanding challenge");
        String first = tokens.iterator().next();
        check(first.matches("[0-9a-f]{64}"), "Malformed random token");
        check(value.matches(first, 60999), "Token expired early");
        check(!value.matches(null, 1000) && !value.matches("wrong", 1000), "Invalid token admitted");
        check(value.issue(60999).equals(first), "Repeat request rotated token");
        check(!value.matches(first, 61000), "Repeat request extended expiry");
        String second = value.issue(61000);
        check(!first.equals(second) && !value.matches(first, 61000), "Expired token remained valid");
        check(value.matches(second, 61000), "Replacement token invalid");
        value.clear();
        check(!value.matches(second, 61000), "Consumed token replay admitted");
        check(!value.issue(61000).equals(second), "Consumed token reused");
        System.out.println("Registration challenge concurrency, expiry and replay checks passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
