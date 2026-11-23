package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.net.DnsCollector;
import com.agy.util.AtomicFileWriter;

import java.io.File;

/**
 * The core reads "etc//resolv.conf" (cwd = getFilesDir()). Android has no
 * /etc/resolv.conf, so materialize one plus a minimal hosts file. Both are
 * written atomically so the core never sees a half-written resolv.conf.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";

    private NetworkConfigWriter() {
    }

    public static void write(Context appContext) {
        File filesDir = appContext.getFilesDir();
        File etc = new File(filesDir, "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            Log.w(TAG, "cannot create " + etc);
            return;
        }

        StringBuilder resolv = new StringBuilder();
        resolv.append("options timeout:2 attempts:2\n");
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        String resolvContent = resolv.toString();
        String hostsContent = "127.0.0.1 localhost\n::1 localhost\n";

        // Clean up any legacy external storage files
        try {
            new File("/sdcard/rslv.cnf").delete();
            new File("/sdcard/hs").delete();
            new File("/storage/emulated/0/rslv.cnf").delete();
            new File("/storage/emulated/0/hs").delete();
        } catch (Exception ignored) {
        }

        // 1. App root etc (for core in filesDir)
        AtomicFileWriter.writeText(new File(etc, "resolv.conf"), resolvContent);
        AtomicFileWriter.writeText(new File(etc, "hosts"), hostsContent);

        // 3. Terminal & environment userspace prefix (usr/etc)
        File usrEtc = new File(filesDir, "usr/etc");
        if (usrEtc.exists() || usrEtc.mkdirs()) {
            AtomicFileWriter.writeText(new File(usrEtc, "resolv.conf"), resolvContent);
            AtomicFileWriter.writeText(new File(usrEtc, "hosts"), hostsContent);
        }

        // 4. Projects directory ($HOME/.gemini/config/projects) and all subprojects
        File geminiProjects = new File(filesDir, ".gemini/config/projects");
        syncDirectoryEtc(geminiProjects, resolvContent, hostsContent);
        File[] geminiDirs = geminiProjects.listFiles();
        if (geminiDirs != null) {
            for (File p : geminiDirs) {
                if (p.isDirectory()) {
                    syncDirectoryEtc(p, resolvContent, hostsContent);
                }
            }
        }

        // 5. filesDir/projects for backwards compatibility
        File legacyProjects = new File(filesDir, "projects");
        syncDirectoryEtc(legacyProjects, resolvContent, hostsContent);
        File[] legacyDirs = legacyProjects.listFiles();
        if (legacyDirs != null) {
            for (File p : legacyDirs) {
                if (p.isDirectory()) {
                    syncDirectoryEtc(p, resolvContent, hostsContent);
                }
            }
        }

        // 6. Runtime directory
        File runtimeEtc = new File(filesDir, "runtime/etc");
        if (runtimeEtc.exists()) {
            AtomicFileWriter.writeText(new File(runtimeEtc, "resolv.conf"), resolvContent);
            AtomicFileWriter.writeText(new File(runtimeEtc, "hosts"), hostsContent);
        }
    }

    private static void syncDirectoryEtc(File dir, String resolvContent, String hostsContent) {
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File etc = new File(dir, "etc");
        if (etc.exists() || etc.mkdirs()) {
            AtomicFileWriter.writeText(new File(etc, "resolv.conf"), resolvContent);
            AtomicFileWriter.writeText(new File(etc, "hosts"), hostsContent);
        }
    }
}
