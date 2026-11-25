package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.util.AtomicFileWriter;

import java.io.File;
import java.io.InputStream;

/**
 * Materializes a fully-onboarded state before the core starts, so the Welcome
 * wizard is skipped. Idempotent and non-destructive: only writes when the
 * target is absent (or a zero-byte stub).
 */
public final class OnboardingSeeder {

    private static final String TAG = "OnboardingSeeder";

    private OnboardingSeeder() {
    }

    public static void seed(Context appContext, File appData, File projects) {
        try {
            if (!appData.exists() && !appData.mkdirs()) {
                Log.w(TAG, "cannot create " + appData);
                return;
            }
            seedStateFile(appContext, "jetski_state.pbtxt", new File(appData, "jetski_state.pbtxt"));
            seedStateFile(appContext, "antigravity_state.pbtxt",
                    new File(appData, "antigravity_state.pbtxt"));

            // Seed default agent rules (AGENTS.md) into ~/.gemini/config/AGENTS.md
            // and safely upgrade bloated stock v2.x AGENTS.md to the lean token-saving version.
            File configDir = projects.getParentFile();
            if (configDir != null && (configDir.isDirectory() || configDir.mkdirs())) {
                seedOrCompactAgentsFile(appContext, new File(configDir, "AGENTS.md"));
            }

            // The project store resolves its projects here; without the
            // pseudo-project file it logs project_store_get_file_missing.
            if (!projects.exists() && !projects.mkdirs()) {
                Log.w(TAG, "cannot create " + projects);
                return;
            }
            File outside = new File(projects, "outside-of-project.json");
            if (!outside.isFile()) {
                AtomicFileWriter.writeText(outside, "{}\n");
            }

            // Seed ~/.profile and ~/.bashrc into $HOME (getFilesDir()) if missing or empty
            File homeDir = appContext.getFilesDir();
            File profile = new File(homeDir, ".profile");
            if (!profile.isFile() || profile.length() == 0) {
                String profileContent = "# Antigravity Shell Profile\n"
                        + "if [ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/profile\" ]; then\n"
                        + "    . \"$PREFIX/etc/profile\"\n"
                        + "elif [ -f \"$HOME/usr/etc/profile\" ]; then\n"
                        + "    . \"$HOME/usr/etc/profile\"\n"
                        + "fi\n"
                        + "if [ -f \"$HOME/.bashrc\" ]; then\n"
                        + "    . \"$HOME/.bashrc\"\n"
                        + "fi\n";
                AtomicFileWriter.writeText(profile, profileContent);
            }

            File bashrc = new File(homeDir, ".bashrc");
            if (!bashrc.isFile() || bashrc.length() == 0) {
                String bashrcContent = "# Antigravity Interactive Bash Config\n"
                        + "if [ -n \"$PREFIX\" ] && [ -f \"$PREFIX/etc/bash.bashrc\" ]; then\n"
                        + "    . \"$PREFIX/etc/bash.bashrc\"\n"
                        + "elif [ -f \"$HOME/usr/etc/bash.bashrc\" ]; then\n"
                        + "    . \"$HOME/usr/etc/bash.bashrc\"\n"
                        + "fi\n";
                AtomicFileWriter.writeText(bashrc, bashrcContent);
            }
        } catch (Exception e) {
            Log.w(TAG, "onboarding seed failed (continuing): " + e.getMessage());
        }
    }

    private static void seedStateFile(Context appContext, String assetName, File target)
            throws Exception {
        if (target.isFile() && target.length() > 0) {
            Log.i(TAG, "state present; left untouched: " + target);
            return;
        }
        try (InputStream in = appContext.getAssets().open(
                RuntimePaths.SEED_ASSET + "/" + assetName)) {
            String content = com.agy.util.IoUtil.readAll(in, "UTF-8");
            AtomicFileWriter.writeText(target, content);
        }
        Log.i(TAG, "seeded onboarding state (was missing): " + target);
    }

    /**
     * Seeds AGENTS.md if absent, and auto-upgrades unmodified bloated stock AGENTS.md
     * (>4 KB legacy template) to the lean token-optimized ruleset (backing up old copy).
     */
    private static void seedOrCompactAgentsFile(Context appContext, File target) throws Exception {
        if (!target.isFile() || target.length() == 0) {
            seedStateFile(appContext, "AGENTS.md", target);
            return;
        }
        if (target.length() > 4000) {
            try (InputStream existingIn = new java.io.FileInputStream(target)) {
                String existing = com.agy.util.IoUtil.readAll(existingIn, "UTF-8");
                if (existing.startsWith("# Antigravity Android Agent Rules")
                        && existing.contains("## 7. Project Architecture & Sync Rules")) {
                    File backup = new File(target.getParentFile(), "AGENTS.md.legacy.bak");
                    if (!backup.exists()) {
                        AtomicFileWriter.writeText(backup, existing);
                    }
                    try (InputStream in = appContext.getAssets().open(
                            RuntimePaths.SEED_ASSET + "/AGENTS.md")) {
                        String lean = com.agy.util.IoUtil.readAll(in, "UTF-8");
                        AtomicFileWriter.writeText(target, lean);
                        Log.i(TAG, "migrated stock AGENTS.md (" + existing.length()
                                + "B -> " + lean.length() + "B) for token optimization");
                    }
                    return;
                }
            }
        }
        Log.i(TAG, "AGENTS.md present; left untouched: " + target);
    }
}
