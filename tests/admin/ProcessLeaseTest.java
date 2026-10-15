package com.termux.admin;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real competing processes, abrupt owner death, and a surviving child. */
public final class ProcessLeaseTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if ("sleeper".equals(args[0])) { Thread.sleep(120000); return; }
            try (ProcessLease lease = ProcessLease.acquire(new File(args[1]))) {
                if (lease == null) { System.exit(3); return; }
                if ("try".equals(args[0])) return;
                java.lang.Process child = launch("sleeper");
                Files.write(Paths.get(args[2]), Long.toString(child.pid()).getBytes("UTF-8"));
                System.out.println("LOCKED"); System.out.flush();
                Thread.sleep(120000);
            }
            return;
        }
        Path base = Files.createTempDirectory("termux-worker-lease-test-");
        Path lock = base.resolve("worker.lock"), child = base.resolve("child.pid");
        java.lang.Process owner = null;
        List<java.lang.Process> contenders = new ArrayList<>();
        try {
            owner = launch("hold", lock.toString(), child.toString());
            check("LOCKED".equals(new BufferedReader(new InputStreamReader(owner.getInputStream())).readLine()), "Owner did not acquire lock");
            for (int i = 0; i < 12; i++) contenders.add(launch("try", lock.toString()));
            for (java.lang.Process contender : contenders)
                check(contender.waitFor(15, TimeUnit.SECONDS) && contender.exitValue() == 3, "A second process acquired the held lock");
            long childPid = Long.parseLong(new String(Files.readAllBytes(child), "UTF-8"));
            owner.destroyForcibly(); check(owner.waitFor(10, TimeUnit.SECONDS), "Owner did not die");
            check(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false), "Child exited before inheritance check");
            java.lang.Process replacement = launch("try", lock.toString());
            contenders.add(replacement);
            check(replacement.waitFor(15, TimeUnit.SECONDS) && replacement.exitValue() == 0, "Abrupt death or inherited child retained the lock");
            System.out.println("Worker lock contention, SIGKILL release and child inheritance checks passed");
        } finally {
            if (owner != null) owner.destroyForcibly();
            for (java.lang.Process contender : contenders) contender.destroyForcibly();
            if (Files.exists(child)) {
                long pid = Long.parseLong(new String(Files.readAllBytes(child), "UTF-8"));
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
            Files.deleteIfExists(child); Files.deleteIfExists(lock); Files.deleteIfExists(base);
        }
    }
    private static java.lang.Process launch(String... args) throws IOException {
        List<String> command = new ArrayList<>(Arrays.asList(System.getProperty("java.home") + "/bin/java",
            "-cp", System.getProperty("java.class.path"), ProcessLeaseTest.class.getName()));
        Collections.addAll(command, args);
        return new ProcessBuilder(command).start();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
