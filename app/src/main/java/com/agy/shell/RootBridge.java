package com.agy.shell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.agy.util.ShellQuote;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dynamic native root (`su`) bridge supporting KernelSU / bakaSU / KernelSU-Next /
 * SukiSU, Magisk, APatch, and generic `su` implementations.
 *
 * <p>Important KernelSU / bakaSU behavior: KernelSU-family root managers completely
 * hide `/system/bin/su` (returning ENOENT/EACCES) until the app UID is explicitly
 * granted Superuser inside the root manager app and the app process is restarted.
 * Because of this, probing never relies solely on {@link File#exists()} and always
 * expires negative results quickly so newly granted access is picked up cleanly.
 */
public final class RootBridge {

    private static final String TAG = "RootBridge";
    private static final long PROBE_TIMEOUT_MS = 3000L;
    private static final long PROBE_CACHE_TTL_MS = 5000L;
    private static final String CLEAN_SYSTEM_PATH =
            "/system/bin:/sbin:/vendor/bin:/system/xbin:/debug_ramdisk:/su/bin";

    private static final String[] SU_CANDIDATES = new String[]{
            "/system/bin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/system/xbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "su"
    };

    private static final String[] KERNELSU_PACKAGES = new String[]{
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext",
            "com.shirkneko.sukisu",
            "com.sukisu.ultra",
            "com.bakasu.manager",
            "me.bakasu.manager",
            "org.bakasu.manager"
    };

    private static final String[] APATCH_PACKAGES = new String[]{
            "me.bmax.apatch"
    };

    private static final String[] MAGISK_PACKAGES = new String[]{
            "com.topjohnwu.magisk"
    };

    private static final String[] SHIZUKU_PACKAGES = new String[]{
            "moe.shizuku.privileged.api"
    };

    public static final class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final RootManagerInfo rootInfo;

        public ExecResult(int exitCode, String stdout, String stderr,
                          boolean timedOut, RootManagerInfo rootInfo) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.timedOut = timedOut;
            this.rootInfo = rootInfo;
        }

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private final Context appContext;
    private volatile RootManagerInfo cachedInfo;
    private volatile long cachedAtMs;

    public RootBridge(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isAvailable() {
        return probe(false).granted;
    }

    public RootManagerInfo probe() {
        return probe(false);
    }

    public synchronized RootManagerInfo probe(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedInfo != null && (now - cachedAtMs) < PROBE_CACHE_TTL_MS) {
            return cachedInfo;
        }
        RootManagerInfo info = runLiveProbe();
        cachedInfo = info;
        cachedAtMs = System.currentTimeMillis();
        return info;
    }

    public interface ProbeCallback {
        void onProbeComplete(RootManagerInfo info);
    }

    /** Triggers a background refresh so UI threads never block on `su` probing. */
    public void probeAsync(final boolean forceRefresh) {
        probeAsync(forceRefresh, null);
    }

    /** Triggers a background refresh and invokes {@code callback} on the worker thread when done. */
    public void probeAsync(final boolean forceRefresh, final ProbeCallback callback) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                RootManagerInfo info = null;
                try {
                    info = probe(forceRefresh);
                } catch (Throwable ignored) {
                }
                if (callback != null && info != null) {
                    try {
                        callback.onProbeComplete(info);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "RootBridgeProbe");
        t.setDaemon(true);
        t.start();
    }

    /** Returns the latest cached probe snapshot without spawning a process on the caller thread. */
    public RootManagerInfo getCachedOrFastInfo() {
        RootManagerInfo info = cachedInfo;
        if (info != null) {
            return info;
        }
        probeAsync(false);
        return new RootManagerInfo(false, "", "", "none", "", "", false, buildGuidance("none", ""));
    }

    /** Convenience helper returning combined stdout/stderr text. */
    public String execText(String cmd) {
        ExecResult res = exec(cmd, false, 30000L, appContext.getFilesDir());
        if (res.isSuccess()) {
            return res.stdout;
        }
        if (!res.stderr.isEmpty()) {
            return res.stderr;
        }
        if (!res.stdout.isEmpty()) {
            return res.stdout;
        }
        if (!res.rootInfo.granted && !res.rootInfo.guidance.isEmpty()) {
            return "error: " + res.rootInfo.guidance;
        }
        return "error: root command exited " + res.exitCode;
    }

    /**
     * Executes {@code cmd} as uid=0 via the active `su` binary while keeping
     * Android system binaries free of Bionic LD_LIBRARY_PATH conflicts and
     * exposing AGY runtime/prefix tools on PATH.
     */
    public ExecResult exec(String cmd, boolean mountMaster, long timeoutMs, File cwd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return new ExecResult(1, "", "empty command", false, probe(false));
        }

        RootManagerInfo info = probe(false);
        if (!info.granted) {
            // Re-probe once in case root was just granted in the manager.
            info = probe(true);
        }
        if (!info.granted) {
            return new ExecResult(126, "", info.guidance, false, info);
        }

        String suBin = info.suPath.isEmpty() ? "/system/bin/su" : info.suPath;
        String wrappedCmd = buildRootShellPayload(cmd, cwd);

        List<String> argv = new ArrayList<String>();
        argv.add(suBin);
        if (mountMaster && info.mountMasterSupported) {
            argv.add("-mm");
        }
        argv.add("-c");
        argv.add(wrappedCmd);

        ProcessBuilder pb = new ProcessBuilder(argv);
        prepareCleanSuEnvironment(pb);
        if (cwd != null && cwd.isDirectory()) {
            pb.directory(cwd);
        } else {
            pb.directory(appContext.getFilesDir());
        }

        return runProcess(pb, timeoutMs > 0 ? timeoutMs : 30000L, info);
    }

    private RootManagerInfo runLiveProbe() {
        String detectedPkg = "";
        String hintedManager = "none";

        String ksuPkg = findInstalledPackage(KERNELSU_PACKAGES);
        String apatchPkg = findInstalledPackage(APATCH_PACKAGES);
        String magiskPkg = findInstalledPackage(MAGISK_PACKAGES);
        String shizukuPkg = findInstalledPackage(SHIZUKU_PACKAGES);

        if (ksuPkg != null) {
            detectedPkg = ksuPkg;
            hintedManager = ksuPkg.toLowerCase(Locale.US).contains("baka") ? "bakasu" : "kernelsu";
        } else if (apatchPkg != null) {
            detectedPkg = apatchPkg;
            hintedManager = "apatch";
        } else if (magiskPkg != null) {
            detectedPkg = magiskPkg;
            hintedManager = "magisk";
        } else if (shizukuPkg != null) {
            detectedPkg = shizukuPkg;
            hintedManager = "sui";
        }

        List<String> orderedCandidates = buildOrderedCandidates();
        for (String candidate : orderedCandidates) {
            ProcessBuilder idPb = new ProcessBuilder(candidate, "-c", "id");
            prepareCleanSuEnvironment(idPb);
            ExecResult idRes = runProcess(idPb, PROBE_TIMEOUT_MS, null);
            String idOut = (idRes.stdout + " " + idRes.stderr).trim();
            if (idRes.isSuccess() && idOut.contains("uid=0")) {
                String version = probeSuVersion(candidate);
                boolean mmSupported = probeMountMaster(candidate);
                String selinux = extractSelinuxContext(idOut);
                String resolvedManager = classifyManager(version, selinux, hintedManager);
                return new RootManagerInfo(
                        true,
                        candidate,
                        version,
                        resolvedManager,
                        detectedPkg,
                        selinux,
                        mmSupported,
                        "");
            }
        }

        String guidance = buildGuidance(hintedManager, detectedPkg);
        return new RootManagerInfo(false, "", "", hintedManager, detectedPkg, "", false, guidance);
    }

    private List<String> buildOrderedCandidates() {
        List<String> ordered = new ArrayList<String>();
        for (String c : SU_CANDIDATES) {
            if (!"su".equals(c)) {
                File f = new File(c);
                if (f.exists() && f.canExecute()) {
                    ordered.add(c);
                }
            }
        }
        // Always include /system/bin/su and PATH-based `su` even when stat() hides them
        // (KernelSU / bakaSU can intercept execve directly).
        if (!ordered.contains("/system/bin/su")) {
            ordered.add("/system/bin/su");
        }
        if (!ordered.contains("su")) {
            ordered.add("su");
        }
        return ordered;
    }

    private String probeSuVersion(String suBin) {
        ProcessBuilder pb = new ProcessBuilder(suBin, "-v");
        prepareCleanSuEnvironment(pb);
        ExecResult res = runProcess(pb, PROBE_TIMEOUT_MS, null);
        String out = res.stdout.trim();
        if (out.isEmpty()) {
            out = res.stderr.trim();
        }
        int nl = out.indexOf('\n');
        return nl >= 0 ? out.substring(0, nl).trim() : out;
    }

    private boolean probeMountMaster(String suBin) {
        ProcessBuilder pb = new ProcessBuilder(suBin, "-mm", "-c", "id");
        prepareCleanSuEnvironment(pb);
        ExecResult res = runProcess(pb, PROBE_TIMEOUT_MS, null);
        return res.isSuccess() && res.stdout.contains("uid=0");
    }

    private String buildRootShellPayload(String cmd, File cwd) {
        String home = appContext.getFilesDir().getAbsolutePath();
        String prefix = home + "/usr";
        String runtimeBin = home + "/runtime/bin";
        String tmpDir = appContext.getCacheDir().getAbsolutePath();
        String certPem = prefix + "/etc/tls/cert.pem";
        String opensslConf = prefix + "/etc/tls/openssl.cnf";
        String terminfo = prefix + "/share/terminfo";
        String path = runtimeBin + ":" + prefix + "/bin:" + CLEAN_SYSTEM_PATH;

        StringBuilder sb = new StringBuilder();
        sb.append("unset LD_PRELOAD LD_LIBRARY_PATH; ");
        sb.append("export HOME=").append(ShellQuote.quote(home)).append("; ");
        sb.append("export PREFIX=").append(ShellQuote.quote(prefix)).append("; ");
        sb.append("export TMPDIR=").append(ShellQuote.quote(tmpDir)).append("; ");
        sb.append("export PATH=").append(ShellQuote.quote(path)).append("; ");
        sb.append("export SSL_CERT_FILE=").append(ShellQuote.quote(certPem)).append("; ");
        sb.append("export CURL_CA_BUNDLE=").append(ShellQuote.quote(certPem)).append("; ");
        sb.append("export GIT_SSL_CAINFO=").append(ShellQuote.quote(certPem)).append("; ");
        sb.append("export OPENSSL_CONF=").append(ShellQuote.quote(opensslConf)).append("; ");
        sb.append("export GIT_CONFIG_NOSYSTEM=1; ");
        sb.append("export GIT_ATTR_NOSYSTEM=1; ");
        sb.append("export TERM=xterm-256color; ");
        sb.append("export TERMINFO=").append(ShellQuote.quote(terminfo)).append("; ");
        if (cwd != null && cwd.isDirectory()) {
            sb.append("cd ").append(ShellQuote.quote(cwd.getAbsolutePath())).append(" 2>/dev/null || true; ");
        }
        sb.append(cmd);
        return sb.toString();
    }

    private void prepareCleanSuEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.remove("LD_LIBRARY_PATH");
        env.remove("LD_PRELOAD");
        env.put("PATH", CLEAN_SYSTEM_PATH);
    }

    private String classifyManager(String version, String selinux, String hintedManager) {
        String combined = (version + " " + selinux).toLowerCase(Locale.US);
        if (combined.contains("bakasu")) {
            return "bakasu";
        }
        if (combined.contains("kernelsu") || combined.contains("u:r:ksu")) {
            return "bakasu".equals(hintedManager) ? "bakasu" : "kernelsu";
        }
        if (combined.contains("apatch")) {
            return "apatch";
        }
        if (combined.contains("magisk")) {
            return "magisk";
        }
        if (!"none".equals(hintedManager) && !"sui".equals(hintedManager)) {
            return hintedManager;
        }
        return "su";
    }

    private String buildGuidance(String hintedManager, String detectedPkg) {
        String pkg = appContext.getPackageName();
        if ("bakasu".equals(hintedManager) || "kernelsu".equals(hintedManager)) {
            return "Root access is not granted yet. Because you are using a KernelSU-based root manager "
                    + "(KernelSU / bakaSU / KernelSU-Next / SukiSU), open your root manager app, grant "
                    + "Superuser access to Antigravity (" + pkg + "), then fully restart Antigravity. "
                    + "If you use the Sui module instead, allow Antigravity in Sui / Shizuku and restart the app.";
        }
        if ("apatch".equals(hintedManager)) {
            return "Root access is not granted yet. Open APatch Manager, grant Superuser access to "
                    + "Antigravity (" + pkg + "), then restart Antigravity. If you use the Sui module, "
                    + "allow Antigravity in Sui / Shizuku and restart the app.";
        }
        if ("magisk".equals(hintedManager)) {
            return "Root access is not granted yet. Allow the Superuser prompt in Magisk Manager for "
                    + "Antigravity (" + pkg + ") and retry. If you use the Sui module, allow Antigravity "
                    + "in Sui / Shizuku and restart the app.";
        }
        return "Root access (su) is not granted to Antigravity (" + pkg + "). "
                + "If you use KernelSU / bakaSU (or another KernelSU fork), grant Superuser access to "
                + "Antigravity in your root manager first, then restart the app. "
                + "If you use the Sui module, allow Antigravity in Sui / Shizuku, then restart the app. "
                + "If you use Magisk or APatch, grant Superuser in the manager and retry.";
    }

    private String findInstalledPackage(String[] candidates) {
        PackageManager pm = appContext.getPackageManager();
        if (pm == null) {
            return null;
        }
        for (String pkg : candidates) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String extractSelinuxContext(String idOutput) {
        int idx = idOutput.indexOf("context=");
        if (idx < 0) {
            return "";
        }
        String tail = idOutput.substring(idx + "context=".length()).trim();
        int space = tail.indexOf(' ');
        if (space >= 0) {
            tail = tail.substring(0, space);
        }
        int nl = tail.indexOf('\n');
        return nl >= 0 ? tail.substring(0, nl).trim() : tail.trim();
    }

    private static ExecResult runProcess(ProcessBuilder pb, long timeoutMs, RootManagerInfo info) {
        Process proc = null;
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();
        try {
            proc = pb.start();
            final InputStream outStream = proc.getInputStream();
            final InputStream errStream = proc.getErrorStream();
            Thread outThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainStream(outStream, outBuf);
                }
            }, "RootStdout");
            Thread errThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainStream(errStream, errBuf);
                }
            }, "RootStderr");
            outThread.start();
            errThread.start();

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (finished) {
                int code = proc.exitValue();
                outThread.join(500);
                errThread.join(500);
                return new ExecResult(code, outBuf.toString().trim(), errBuf.toString().trim(), false, info);
            } else {
                proc.destroy();
                errBuf.append("\n[Root process timed out after ").append(timeoutMs).append("ms]");
                return new ExecResult(-1, outBuf.toString().trim(), errBuf.toString().trim(), true, info);
            }
        } catch (Exception e) {
            Log.d(TAG, "su exec failed: " + e.getMessage());
            return new ExecResult(-1, outBuf.toString().trim(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    false, info);
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void drainStream(InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        } catch (Exception ignored) {
        }
    }
}
