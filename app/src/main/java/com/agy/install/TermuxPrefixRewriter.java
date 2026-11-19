package com.agy.install;

import android.util.Log;

import com.agy.util.AtomicFileWriter;
import com.agy.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

/**
 * Full rewrite pass: replaces every Android app-private prefix
 * ({@code /data/data/com.<anyid>/files/usr}) with our prefix in text files.
 */
public final class TermuxPrefixRewriter {

    private static final String TAG = "TermuxPrefixRewriter";

    private TermuxPrefixRewriter() {
    }

    public static void rewrite(File prefix) {
        rewrite(prefix, prefix.getAbsolutePath());
    }

    /** Rewrites a staging tree while emitting paths for its eventual install location. */
    public static void rewrite(File root, String targetPrefix) {
        patchBashBinary(new File(root, EnvPaths.BASH_REL));
        int rewritten = 0;
        for (String dir : EnvPaths.SCRIPT_DIRS) {
            rewritten += rewriteIn(new File(root, dir), targetPrefix);
        }
        if (rewritten > 0) {
            Log.i(TAG, "rewrote termux paths in " + rewritten + " file(s) -> " + targetPrefix);
        }
    }

    public static void patchBashBinary(File bashFile) {
        if (bashFile == null || !bashFile.isFile()) {
            return;
        }
        try {
            byte[] full;
            try (InputStream in = new FileInputStream(bashFile)) {
                full = IoUtil.readAllBytes(in);
            }
            byte[] t1 = "/data/data/com.termux/files/usr/etc/profile\0".getBytes(StandardCharsets.ISO_8859_1);
            byte[] r1 = new byte[t1.length];
            byte[] p1 = "/etc/profile\0".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(p1, 0, r1, 0, p1.length);

            byte[] t2 = "/data/data/com.termux/files/usr/etc/bash.bashrc\0".getBytes(StandardCharsets.ISO_8859_1);
            byte[] r2 = new byte[t2.length];
            byte[] p2 = "/etc/bash.bashrc\0".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(p2, 0, r2, 0, p2.length);

            boolean modified = false;
            int idx1 = indexOf(full, t1);
            if (idx1 != -1) {
                System.arraycopy(r1, 0, full, idx1, r1.length);
                modified = true;
            }
            int idx2 = indexOf(full, t2);
            if (idx2 != -1) {
                System.arraycopy(r2, 0, full, idx2, r2.length);
                modified = true;
            }
            if (modified) {
                AtomicFileWriter.writeBytes(bashFile, full);
                bashFile.setExecutable(true, false);
                Log.i(TAG, "successfully patched hardcoded profile paths in bash binary: " + bashFile);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot patch bash binary " + bashFile + ": " + t.getMessage());
        }
    }

    private static int indexOf(byte[] source, byte[] target) {
        if (source == null || target == null || target.length == 0 || source.length < target.length) {
            return -1;
        }
        for (int i = 0; i <= source.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static int rewriteIn(File dir, String newPrefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int rewritten = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                continue;
            }
            if (child.isDirectory()) {
                rewritten += rewriteIn(child, newPrefix);
            } else if (child.isFile() && rewriteFile(child, newPrefix)) {
                rewritten++;
            }
        }
        return rewritten;
    }

    private static boolean rewriteFile(File file, String newPrefix) {
        byte[] full;
        try (InputStream in = new FileInputStream(file)) {
            full = IoUtil.readAllBytes(in);
        } catch (IOException e) {
            return false;
        }
        if (!TextFileDetector.isTextFile(full)) {
            return false;
        }
        // Cheap marker test before the regex over the whole file.
        String content = new String(full, StandardCharsets.ISO_8859_1);
        if (!content.contains(EnvPaths.PREFIX_MARKER)) {
            return false;
        }
        String replaced = EnvPaths.TERMUX_ANY_PREFIX.matcher(content)
                .replaceAll(Matcher.quoteReplacement(newPrefix));
        if (replaced.equals(content)) {
            return false;
        }
        return AtomicFileWriter.writeBytes(file, replaced.getBytes(StandardCharsets.ISO_8859_1));
    }
}
