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
        patchElfBinaries(root, targetPrefix);
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

    /**
     * Length-preserving rewrite for ELF binaries (DT_RUNPATH / DT_RPATH / embedded paths).
     * Replaces occurrences of "/data/data/com.termux" (21 bytes) with path padded with slashes
     * (e.g. "/data/data/com.agy///" which is exactly 21 bytes). Restricting the match to
     * path prefixes prevents accidental modification of non-path strings (e.g. broadcast intents
     * or namespaces like com.termux.api).
     */
    public static int patchElfBinaries(File root, String targetPrefix) {
        String pkg = extractPackageName(targetPrefix);
        if (pkg == null || pkg.isEmpty() || pkg.length() > 10) {
            return 0;
        }
        StringBuilder paddedPkg = new StringBuilder(pkg);
        while (paddedPkg.length() < 10) {
            paddedPkg.append('/');
        }

        // Fast-path guard for warm launches: skip file iteration if already patched
        File marker = new File(root, ".agy-elf-patched");
        String expectedMarker = paddedPkg + ":" + com.agy.BuildConfig.APP_VERSION_CODE;
        if (marker.isFile()) {
            try {
                String existing = IoUtil.readAll(new FileInputStream(marker), "UTF-8").trim();
                if (expectedMarker.equals(existing)) {
                    return 0;
                }
            } catch (Exception ignored) {
            }
        }

        byte[] targetData = "/data/data/com.termux".getBytes(StandardCharsets.ISO_8859_1);
        byte[] replaceData = ("/data/data/" + paddedPkg).getBytes(StandardCharsets.ISO_8859_1);

        byte[] targetUser = "/data/user/0/com.termux".getBytes(StandardCharsets.ISO_8859_1);
        byte[] replaceUser = ("/data/user/0/" + paddedPkg).getBytes(StandardCharsets.ISO_8859_1);

        int patched = 0;
        String[] elfDirs = {"bin", "lib", "libexec"};
        for (String dirName : elfDirs) {
            File dir = new File(root, dirName);
            if (dir.isDirectory()) {
                patched += patchElfInDir(dir, targetData, replaceData);
                patched += patchElfInDir(dir, targetUser, replaceUser);
            }
        }
        if (patched > 0) {
            Log.i(TAG, "rewrote DT_RUNPATH/termux path prefixes in " + patched + " ELF file(s) -> " + paddedPkg);
        }
        try {
            AtomicFileWriter.writeText(marker, expectedMarker + "\n");
        } catch (Exception ignored) {
        }
        return patched;
    }

    private static String extractPackageName(String path) {
        if (path == null) return "com.agy";
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("data".equals(parts[i]) || parts[i].matches("user|0|[0-9]+")) {
                if (i + 1 < parts.length && parts[i + 1].contains(".")) {
                    return parts[i + 1];
                }
            }
        }
        return "com.agy";
    }

    private static int patchElfInDir(File dir, byte[] target, byte[] replacement) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (TextFileDetector.isSymlink(f)) continue;
            if (f.isDirectory()) {
                count += patchElfInDir(f, target, replacement);
            } else if (f.isFile()) {
                if (patchElfFile(f, target, replacement)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static boolean patchElfFile(File file, byte[] target, byte[] replacement) {
        if (file.length() < 16) return false;
        try {
            byte[] full;
            try (InputStream in = new FileInputStream(file)) {
                full = IoUtil.readAllBytes(in);
            }
            if (full.length < 18 || full[0] != 0x7f || full[1] != 'E' || full[2] != 'L' || full[3] != 'F') {
                return false;
            }
            boolean modified = false;
            int idx = 0;
            while ((idx = indexOf(full, target, idx)) != -1) {
                System.arraycopy(replacement, 0, full, idx, replacement.length);
                idx += replacement.length;
                modified = true;
            }
            if (modified) {
                AtomicFileWriter.writeBytes(file, full);
                file.setExecutable(true, false);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed patching ELF file " + file + ": " + t.getMessage());
        }
        return false;
    }

    private static int indexOf(byte[] source, byte[] target, int start) {
        if (source == null || target == null || target.length == 0 || source.length < target.length || start < 0) {
            return -1;
        }
        for (int i = start; i <= source.length - target.length; i++) {
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
}
