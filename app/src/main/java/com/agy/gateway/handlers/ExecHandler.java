package com.agy.gateway.handlers;

import android.content.Context;
import android.util.Log;

import com.agy.BuildConfig;
import com.agy.log.CommandHistory;
import com.agy.log.CommandRecord;
import com.agy.log.LogRingBuffer;
import com.agy.shell.RootBridge;
import com.agy.shell.RootManagerInfo;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles command execution requests with detailed telemetry, outputs, and exit codes.
 * Supports unprivileged Bionic shell, Shizuku (`rish`), and native root (`su`).
 */
public final class ExecHandler {

    private static final String TAG = "ExecHandler";
    private final Context context;
    private final CommandHistory history;
    private final LogRingBuffer logBuffer;
    private final RootBridge rootBridge;

    public ExecHandler(Context context, CommandHistory history, LogRingBuffer logBuffer) {
        this(context, history, logBuffer, new RootBridge(context));
    }

    public ExecHandler(Context context, CommandHistory history, LogRingBuffer logBuffer, RootBridge rootBridge) {
        this.context = context.getApplicationContext();
        this.history = history;
        this.logBuffer = logBuffer;
        this.rootBridge = rootBridge != null ? rootBridge : new RootBridge(context);
    }

    public JSONObject handle(JSONObject payload) {
        String cmd = payload.optString("cmd", "").trim();
        String source = payload.optString("source", "agent");
        long timeoutMs = payload.optLong("timeout_ms", 30000);
        boolean useRish = payload.optBoolean("rish", false);
        boolean useRoot = payload.optBoolean("root", false);
        boolean mountMaster = payload.optBoolean("mount_master", false);
        boolean fallbackRish = payload.optBoolean("fallback_rish", false);
        String cwdPath = payload.optString("cwd", context.getFilesDir().getAbsolutePath());

        String cmdId = "cmd_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        long startTime = System.currentTimeMillis();

        if (cmd.isEmpty()) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "empty command");
            } catch (Exception ignored) {}
            return err;
        }

        if (BuildConfig.DEBUG) {
            String startMsg = String.format("[EXEC] START [%s] (source=%s, root=%s, rish=%s): %s",
                    cmdId, source, useRoot, useRish, cmd);
            Log.d(TAG, startMsg);
            if (logBuffer != null) {
                logBuffer.append("exec", "DEBUG", startMsg);
            }
        }

        int exitCode = -1;
        StringBuilder stdoutBuf = new StringBuilder();
        StringBuilder stderrBuf = new StringBuilder();
        String status = "ERROR";
        RootManagerInfo rootInfo = null;
        boolean fellBackToRish = false;

        if (useRoot) {
            File cwd = new File(cwdPath);
            RootBridge.ExecResult r = rootBridge.exec(cmd, mountMaster, timeoutMs, cwd.isDirectory() ? cwd : null);
            rootInfo = r.rootInfo;
            if (rootInfo != null && !rootInfo.granted && fallbackRish) {
                fellBackToRish = true;
                useRoot = false;
                useRish = true;
            } else {
                exitCode = r.exitCode;
                stdoutBuf.append(r.stdout);
                stderrBuf.append(r.stderr);
                if (r.timedOut) {
                    status = "TIMEOUT";
                } else {
                    status = (exitCode == 0) ? "SUCCESS" : "FAILED";
                }
            }
        }

        if (!useRoot) {
            try {
                ProcessBuilder pb;
                if (useRish) {
                    pb = new ProcessBuilder(resolveRishPath(), "-c", cmd);
                } else {
                    pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                }

                File cwd = new File(cwdPath);
                if (cwd.isDirectory()) {
                    pb.directory(cwd);
                }

                String home = context.getFilesDir().getAbsolutePath();
                String prefix = home + "/usr";
                pb.environment().put("HOME", home);
                pb.environment().put("PREFIX", prefix);
                pb.environment().put("PATH", home + "/runtime/bin:" + prefix + "/bin:/system/bin:" + System.getenv("PATH"));
                pb.environment().put("RISH_APPLICATION_ID", context.getPackageName());
                if (useRish) {
                    pb.environment().remove("LD_LIBRARY_PATH");
                } else {
                    pb.environment().put("LD_LIBRARY_PATH", prefix + "/lib");
                    pb.environment().put("TERMINFO", prefix + "/share/terminfo");
                    pb.environment().put("OPENSSL_CONF", prefix + "/etc/tls/openssl.cnf");
                    pb.environment().put("SSL_CERT_FILE", prefix + "/etc/tls/cert.pem");
                    pb.environment().put("CURL_CA_BUNDLE", prefix + "/etc/tls/cert.pem");
                    pb.environment().put("GIT_SSL_CAINFO", prefix + "/etc/tls/cert.pem");
                    pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
                    pb.environment().put("GIT_ATTR_NOSYSTEM", "1");
                }

                Process proc = pb.start();

                // Read stdout and stderr in background threads to avoid buffer deadlocks
                Thread outThread = new Thread(() -> drainStream(proc.getInputStream(), stdoutBuf), "StdoutDrain");
                Thread errThread = new Thread(() -> drainStream(proc.getErrorStream(), stderrBuf), "StderrDrain");
                outThread.start();
                errThread.start();

                boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (finished) {
                    exitCode = proc.exitValue();
                    outThread.join(500);
                    errThread.join(500);
                    status = (exitCode == 0) ? "SUCCESS" : "FAILED";
                } else {
                    proc.destroy();
                    status = "TIMEOUT";
                    stderrBuf.append("\n[Process timed out after ").append(timeoutMs).append("ms]");
                }
            } catch (Exception e) {
                status = "ERROR";
                stderrBuf.append("\nExecution exception: ").append(e.getMessage());
                Log.w(TAG, "exec error: " + e.getMessage());
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String stdoutStr = stdoutBuf.toString().trim();
        String stderrStr = stderrBuf.toString().trim();

        CommandRecord record = new CommandRecord(cmdId, startTime, cmd, exitCode, durationMs, stdoutStr, stderrStr, status, source);
        if (history != null) {
            history.record(record);
        }

        if (BuildConfig.DEBUG) {
            String endMsg = String.format("[EXEC] DONE [%s] (exit=%d, %dms, status=%s)", cmdId, exitCode, durationMs, status);
            Log.d(TAG, endMsg);
            if (logBuffer != null) {
                logBuffer.append("exec", (exitCode == 0 ? "INFO" : "WARN"), endMsg);
                if (!stderrStr.isEmpty()) {
                    logBuffer.append("exec", "WARN", "[stderr] " + stderrStr);
                }
            }
        }

        JSONObject resp = record.toJson();
        try {
            resp.put("ok", "SUCCESS".equals(status));
            if (rootInfo != null) {
                resp.put("root_granted", rootInfo.granted);
                resp.put("manager_hint", rootInfo.managerType);
                if (!rootInfo.granted && !rootInfo.guidance.isEmpty()) {
                    resp.put("guidance", rootInfo.guidance);
                }
            }
            if (fellBackToRish) {
                resp.put("fallback_used", "rish");
            }
        } catch (Exception ignored) {}
        return resp;
    }

    private static void drainStream(InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append(line);
            }
        } catch (Exception ignored) {}
    }

    private String resolveRishPath() {
        File files = context.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "usr/bin/rish"),
                new File(files, "runtime/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return new File(files, "usr/bin/rish").getAbsolutePath();
    }
}
