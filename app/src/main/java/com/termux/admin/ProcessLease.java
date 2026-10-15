// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import java.io.*;
import java.nio.channels.*;

/** A process-held lock. Keep its path stable and never delete the lock file. */
final class ProcessLease implements AutoCloseable {
    private final RandomAccessFile file;
    private final FileLock lock;
    private boolean closed;
    private ProcessLease(RandomAccessFile file, FileLock lock) { this.file = file; this.lock = lock; }
    static ProcessLease acquire(File path) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path, "rw");
        try {
            FileLock lock;
            try { lock = file.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { lock = null; }
            if (lock == null) { file.close(); return null; }
            return new ProcessLease(file, lock);
        } catch (Throwable failure) { file.close(); throw failure; }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { lock.release(); } finally { file.close(); }
    }
}
