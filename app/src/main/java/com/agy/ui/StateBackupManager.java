package com.agy.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.agy.util.PathSafety;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable backup & restore manager for Antigravity chats, brain artifacts,
 * rules (`AGENTS.md`), and local state (`$HOME/.gemini`).
 *
 * <p>Archives are written to {@code Download/Antigravity-Backups/} so they
 * survive app uninstalls, Clear Data, and custom ROM flashes.
 */
public final class StateBackupManager {

    private static final String TAG = "StateBackupManager";
    private static final String BACKUP_SUBDIR = "Antigravity-Backups";
    private static final String BACKUP_PREFIX = "agy-backup-";
    private static final String BACKUP_SUFFIX = ".zip";

    private StateBackupManager() {
    }

    public static File getBackupDir(Context context) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null && (downloads.exists() || downloads.mkdirs())) {
            File dir = new File(downloads, BACKUP_SUBDIR);
            if (dir.exists() || dir.mkdirs()) {
                return dir;
            }
        }
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            File dir = new File(ext, BACKUP_SUBDIR);
            if (dir.exists() || dir.mkdirs()) {
                return dir;
            }
        }
        return null;
    }

    /** Archives $HOME/.gemini (excluding builtin/crashes/scratch) on a background thread. */
    public static void backupChatsAsync(final Context context) {
        if (context == null) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(appCtx, "Backing up chats & settings...", Toast.LENGTH_SHORT).show();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File geminiDir = new File(appCtx.getFilesDir(), ".gemini");
                    if (!geminiDir.isDirectory()) {
                        throw new IOException("No .gemini data directory found to backup");
                    }
                    File backupDir = getBackupDir(appCtx);
                    if (backupDir == null) {
                        throw new IOException("Cannot access Download/Antigravity-Backups (check All Files permission)");
                    }
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                    final File outZip = new File(backupDir, BACKUP_PREFIX + stamp + BACKUP_SUFFIX);

                    final int[] fileCount = new int[]{0};
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
                        zipGeminiTree(geminiDir, ".gemini", zos, fileCount);
                    }
                    MediaScannerConnection.scanFile(appCtx, new String[]{outZip.getAbsolutePath()}, null, null);
                    final long sizeKb = Math.max(1L, outZip.length() / 1024L);
                    Log.i(TAG, "Backup written: " + outZip.getAbsolutePath() + " (" + fileCount[0] + " files)");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appCtx,
                                    "Backed up " + fileCount[0] + " files (" + sizeKb + " KB) to Download/"
                                            + BACKUP_SUBDIR + "/" + outZip.getName(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Backup failed", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appCtx, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "AgyStateBackup");
        t.setDaemon(true);
        t.start();
    }

    /** Finds the newest backup zip in Download/Antigravity-Backups and prompts to restore it. */
    public static void promptRestoreLatest(final MainActivity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        File backupDir = getBackupDir(activity);
        final File latest = findLatestBackup(backupDir);
        if (latest == null) {
            Toast.makeText(activity,
                    "No backups found in Download/" + BACKUP_SUBDIR,
                    Toast.LENGTH_LONG).show();
            return;
        }
        long sizeKb = Math.max(1L, latest.length() / 1024L);
        new AlertDialog.Builder(activity)
                .setTitle("Restore Chats & Settings?")
                .setMessage("Restore from:\n" + latest.getName() + " (" + sizeKb + " KB)\n\n"
                        + "This overwrites local chats/config in .gemini and restarts the core server.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        restoreZipAsync(activity, latest);
                    }
                })
                .show();
    }

    private static File findLatestBackup(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(BACKUP_SUFFIX) && f.length() > 0) {
                return f;
            }
        }
        return null;
    }

    private static void restoreZipAsync(final MainActivity activity, final File zipFile) {
        final Context appCtx = activity.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(appCtx, "Restoring backup...", Toast.LENGTH_SHORT).show();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File filesDir = appCtx.getFilesDir().getCanonicalFile();
                    File geminiRoot = new File(filesDir, ".gemini").getCanonicalFile();
                    if (!geminiRoot.exists() && !geminiRoot.mkdirs()) {
                        throw new IOException("Cannot create .gemini directory");
                    }
                    int restored = 0;
                    byte[] buf = new byte[16384];
                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            String name = entry.getName();
                            if (name == null || name.isEmpty() || !name.startsWith(".gemini/")) {
                                continue;
                            }
                            File out = new File(filesDir, name).getCanonicalFile();
                            if (!PathSafety.isInside(geminiRoot, out)) {
                                throw new IOException("Refusing unsafe backup path: " + name);
                            }
                            if (entry.isDirectory()) {
                                out.mkdirs();
                            } else {
                                File parent = out.getParentFile();
                                if (parent != null && !parent.exists()) {
                                    parent.mkdirs();
                                }
                                try (FileOutputStream fos = new FileOutputStream(out)) {
                                    int n;
                                    while ((n = zis.read(buf)) != -1) {
                                        fos.write(buf, 0, n);
                                    }
                                    fos.flush();
                                }
                                restored++;
                            }
                            zis.closeEntry();
                        }
                    }
                    final int count = restored;
                    Log.i(TAG, "Restored " + count + " files from " + zipFile.getAbsolutePath());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appCtx,
                                    "Restored " + count + " files! Restarting core...",
                                    Toast.LENGTH_LONG).show();
                            if (!activity.isFinishing()) {
                                activity.restartCoreFromUser();
                            }
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "Restore failed", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appCtx, "Restore failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "AgyStateRestore");
        t.setDaemon(true);
        t.start();
    }

    private static void zipGeminiTree(File node, String relPath, ZipOutputStream zos, int[] counter) throws IOException {
        if (shouldSkip(relPath)) {
            return;
        }
        if (node.isDirectory()) {
            File[] children = node.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                zipGeminiTree(child, relPath + "/" + child.getName(), zos, counter);
            }
        } else if (node.isFile()) {
            zos.putNextEntry(new ZipEntry(relPath));
            try (FileInputStream in = new FileInputStream(node)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    zos.write(buf, 0, n);
                }
            }
            zos.closeEntry();
            counter[0]++;
        }
    }

    private static boolean shouldSkip(String relPath) {
        return relPath.equals(".gemini/antigravity-app/scratch")
                || relPath.startsWith(".gemini/antigravity-app/scratch/")
                || relPath.equals(".gemini/antigravity-app/builtin")
                || relPath.startsWith(".gemini/antigravity-app/builtin/")
                || relPath.equals(".gemini/antigravity-app/crashes")
                || relPath.startsWith(".gemini/antigravity-app/crashes/");
    }
}
