package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/** Appends core stdout to files/logs/core.log. */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            File logFile = new File(dir, "core.log");
            // If log file exceeds 1.5MB, rotate it so it does not grow indefinitely
            if (logFile.exists() && logFile.length() > 1_500_000) {
                File oldLog = new File(dir, "core.log.old");
                if (oldLog.exists()) {
                    oldLog.delete();
                }
                logFile.renameTo(oldLog);
            }
            return new OutputStreamWriter(new FileOutputStream(logFile, true),
                    Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "cannot open core log", e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        try {
            log.write(SensitiveDataRedactor.redactLine(line));
            log.write('\n');
            log.flush();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
