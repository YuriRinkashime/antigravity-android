package com.agy.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.agy.core.CoreRuntimeState;
import com.agy.runtime.RuntimeConfig;
import com.agy.shell.RootBridge;
import com.agy.shell.RootManagerInfo;
import com.agy.shell.RootResilience;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.splash.LogExporter;

/**
 * Compact draggable edge pill + native Quick-Control & Diagnostics sheet.
 * Gives one-tap access to Root/Sui status & re-check, Anti-Kill protection,
 * Desktop Mode, Core Restart, and Log Export without leaving the workspace.
 */
public final class QuickControlSheet {

    private static final String PREFS_NAME = "agy_quick_control";
    private static final String KEY_PILL_Y = "pill_top_margin_px";

    private final MainActivity activity;
    private final FrameLayout rootLayout;
    private final ThemeManager themeManager;
    private final RootBridge rootBridge;
    private final ShizukuBridge shizukuBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView pillView;
    private View sheetOverlay;
    private boolean pillAttached = false;

    public QuickControlSheet(MainActivity activity, FrameLayout rootLayout,
                             ThemeManager themeManager, RootBridge rootBridge,
                             ShizukuBridge shizukuBridge) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.themeManager = themeManager;
        this.rootBridge = rootBridge;
        this.shizukuBridge = shizukuBridge;
    }

    /** Attaches the compact edge pill once the main UI is visible. */
    public void attachPill() {
        if (pillAttached || activity.isFinishing()) {
            return;
        }
        pillAttached = true;

        final TextView pill = new TextView(activity);
        pill.setText("AGY");
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setAlpha(0.72f);
        pill.setPadding(dp(9), dp(6), dp(9), dp(6));
        stylePill(pill);

        int savedTop = loadPillTopMargin();
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = savedTop;
        lp.rightMargin = dp(6);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int startTop;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        startTop = lp.topMargin;
                        dragged = false;
                        v.setAlpha(0.95f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dy) > dp(6)) {
                            dragged = true;
                        }
                        if (dragged) {
                            int maxTop = Math.max(dp(48), rootLayout.getHeight() - dp(96));
                            int nextTop = Math.max(dp(36), Math.min(maxTop, startTop + Math.round(dy)));
                            lp.topMargin = nextTop;
                            v.setLayoutParams(lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(0.72f);
                        if (dragged) {
                            savePillTopMargin(lp.topMargin);
                        } else {
                            openSheet();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(0.72f);
                        return true;
                    default:
                        return false;
                }
            }
        });

        this.pillView = pill;
        rootLayout.addView(pill, lp);
    }

    public void refreshTheme() {
        if (pillView != null) {
            stylePill(pillView);
        }
    }

    public boolean isSheetOpen() {
        return sheetOverlay != null;
    }

    public void closeSheet() {
        if (sheetOverlay != null) {
            try {
                rootLayout.removeView(sheetOverlay);
            } catch (Throwable ignored) {
            }
            sheetOverlay = null;
        }
    }

    public void destroy() {
        closeSheet();
        if (pillView != null) {
            try {
                rootLayout.removeView(pillView);
            } catch (Throwable ignored) {
            }
            pillView = null;
        }
        pillAttached = false;
    }

    public void openSheet() {
        if (activity.isFinishing()) {
            return;
        }
        closeSheet();

        boolean dark = themeManager != null && "dark".equals(themeManager.getTheme());
        int scrimCol = Color.parseColor("#88000000");
        int cardBg = dark ? Color.parseColor("#FA121722") : Color.parseColor("#FAFFFFFF");
        int borderCol = dark ? Color.parseColor("#334155") : Color.parseColor("#CBD5E1");
        int titleCol = dark ? Color.parseColor("#F8FAFC") : Color.parseColor("#0F172A");
        int bodyCol = dark ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155");
        int mutedCol = dark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");
        int btnBg = dark ? Color.parseColor("#1E293B") : Color.parseColor("#F1F5F9");
        int accentCol = dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7");

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(scrimCol);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
            }
        });

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable cardDrawable = new GradientDrawable();
        cardDrawable.setColor(cardBg);
        cardDrawable.setCornerRadius(dp(18));
        cardDrawable.setStroke(dp(1), borderCol);
        card.setBackground(cardDrawable);

        // Header
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("Antigravity Control  v" + RuntimeConfig.APP_VERSION_NAME);
        title.setTextColor(titleCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        TextView closeBtn = new TextView(activity);
        closeBtn.setText("Close");
        closeBtn.setTextColor(accentCol);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        closeBtn.setTypeface(Typeface.DEFAULT_BOLD);
        closeBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
            }
        });
        header.addView(closeBtn);
        card.addView(header);

        // Status info block
        final TextView statusView = new TextView(activity);
        statusView.setTextColor(bodyCol);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusView.setText(formatStatusText(rootBridge != null ? rootBridge.getCachedOrFastInfo() : null));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(10);
        stLp.bottomMargin = dp(12);
        card.addView(statusView, stLp);

        // Kick off live refresh so sheet displays up-to-date root info
        if (rootBridge != null) {
            rootBridge.probeAsync(true, new RootBridge.ProbeCallback() {
                @Override
                public void onProbeComplete(final RootManagerInfo info) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (sheetOverlay != null) {
                                statusView.setText(formatStatusText(info));
                            }
                        }
                    });
                }
            });
        }

        // Action buttons
        card.addView(makeSheetButton("Re-check Root / Sui Access", btnBg, accentCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rootBridge == null) return;
                statusView.setText("Probing root access...");
                rootBridge.probeAsync(true, new RootBridge.ProbeCallback() {
                    @Override
                    public void onProbeComplete(final RootManagerInfo info) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (sheetOverlay != null) {
                                    statusView.setText(formatStatusText(info));
                                }
                                String msg = info.granted
                                        ? "Root active (" + info.managerType + ", uid=0)"
                                        : "Not granted — grant in KernelSU/bakaSU or Sui & restart";
                                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        }));

        card.addView(makeSheetButton("Apply Anti-Kill Protection (Doze + OOM)", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(activity, "Applying Doze whitelist & OOM protection...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final RootResilience.ProtectionResult res = RootResilience.applyProtection(activity, rootBridge);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(activity,
                                        res.rootGranted ? ("Protected: " + res.summary) : "Root required for Anti-Kill protection",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }, "QuickProtect").start();
            }
        }));

        final boolean desktopOn = activity.isDesktopMode();
        card.addView(makeSheetButton("Desktop Mode: " + (desktopOn ? "ON (tap to disable)" : "OFF (tap to enable)"),
                btnBg, titleCol, borderCol, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean next = !activity.isDesktopMode();
                        activity.setDesktopMode(next);
                        closeSheet();
                        Toast.makeText(activity, "Desktop Mode " + (next ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
                    }
                }));

        card.addView(makeSheetButton("Restart Core Server", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                activity.restartCoreFromUser();
            }
        }));

        card.addView(makeSheetButton("Backup Chats & Settings (to Downloads)", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                StateBackupManager.backupChatsAsync(activity);
            }
        }));

        card.addView(makeSheetButton("Restore Latest Chats Backup", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                StateBackupManager.promptRestoreLatest(activity);
            }
        }));

        card.addView(makeSheetButton("Copy Diagnostics Logs", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogExporter.copyToClipboard(activity);
            }
        }));

        card.addView(makeSheetButton("Save & Share Logs", btnBg, titleCol, borderCol, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                LogExporter.saveAndShare(activity);
            }
        }));

        TextView hideBtn = new TextView(activity);
        hideBtn.setText("Hide AGY pill until next launch");
        hideBtn.setTextColor(mutedCol);
        hideBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setPadding(dp(8), dp(10), dp(8), dp(2));
        hideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSheet();
                if (pillView != null) {
                    pillView.setVisibility(View.GONE);
                }
            }
        });
        card.addView(hideBtn);

        TextView creditView = new TextView(activity);
        creditView.setText("Root Edition by YuriRinkashime  •  GitHub");
        creditView.setTextColor(accentCol);
        creditView.setAlpha(0.85f);
        creditView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        creditView.setGravity(Gravity.CENTER);
        creditView.setPadding(dp(8), dp(6), dp(8), dp(2));
        creditView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/YuriRinkashime/antigravity-android"));
                    activity.startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
        });
        card.addView(creditView);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(card);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        int m = dp(12);
        cardLp.setMargins(m, dp(48), m, dp(16));
        scrim.addView(scroll, cardLp);

        this.sheetOverlay = scrim;
        rootLayout.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private String formatStatusText(RootManagerInfo info) {
        StringBuilder sb = new StringBuilder();
        boolean coreUp = CoreRuntimeState.serverUrl != null;
        sb.append("Core: ").append(coreUp ? ("Running (:" + CoreRuntimeState.httpsPort + ")") : "Starting / Stopped");
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        sb.append("  •  JVM Heap: ").append(usedMb).append(" MB\n");

        if (info != null && info.granted) {
            sb.append("Root: GRANTED (").append(info.managerType);
            if (info.mountMasterSupported) {
                sb.append(", -mm");
            }
            sb.append(")");
            if (!info.suVersion.isEmpty()) {
                sb.append("\nsu: ").append(info.suVersion);
            }
        } else {
            sb.append("Root: Not granted\n");
            sb.append("• KernelSU / bakaSU: grant Superuser & restart app\n");
            sb.append("• Sui module: allow in Sui / Shizuku & restart app");
        }
        if (shizukuBridge != null) {
            sb.append("\nShizuku (rish): ").append(shizukuBridge.isAvailable() ? "Bundled" : "Unavailable");
        }
        return sb.toString();
    }

    private View makeSheetButton(String label, int bgColor, int textColor, int borderColor, View.OnClickListener onClick) {
        TextView btn = new TextView(activity);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setPadding(dp(14), dp(11), dp(14), dp(11));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), borderColor);
        btn.setBackground(d);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void stylePill(TextView pill) {
        boolean dark = themeManager != null && "dark".equals(themeManager.getTheme());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? Color.parseColor("#CC1E293B") : Color.parseColor("#CCF1F5F9"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), dark ? Color.parseColor("#475569") : Color.parseColor("#94A3B8"));
        pill.setBackground(bg);
        pill.setTextColor(dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7"));
    }

    private int loadPillTopMargin() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_PILL_Y, dp(96));
        } catch (Throwable ignored) {
            return dp(96);
        }
    }

    private void savePillTopMargin(int px) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(KEY_PILL_Y, px).apply();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }
}
