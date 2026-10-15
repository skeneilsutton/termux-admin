// SPDX-License-Identifier: GPL-3.0-only
package com.termux.admin;

import java.io.*;

/** Replace the terminal client without opening a name supplied by an archive. */
final class ClientScriptWriter {
    private ClientScriptWriter() {}
    static void write(File bin, byte[] script) throws IOException {
        File temporary = File.createTempFile("termux-admin-", ".new", bin);
        try {
            try (OutputStream out = new FileOutputStream(temporary)) { out.write(script); }
            if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false)
                    || !temporary.setExecutable(false, false) || !temporary.setReadable(true, true)
                    || !temporary.setWritable(true, true) || !temporary.setExecutable(true, true))
                throw new IOException("Terminal command permissions unavailable");
            if (!temporary.renameTo(new File(bin, "termux-admin")))
                throw new IOException("Terminal command installation failed");
        } finally { temporary.delete(); }
    }
}
