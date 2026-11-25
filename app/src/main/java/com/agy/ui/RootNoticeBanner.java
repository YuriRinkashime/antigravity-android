package com.agy.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.agy.shell.RootBridge;
import com.agy.shell.RootManagerInfo;
import com.agy.theme.ThemeManager;

/**
 * Lightweight, dismissible in-app notice shown when root (`su`) is not yet granted.
 * Guides KernelSU / bakaSU users to grant Superuser in their manager and restart,
 * and Sui module users to allow Antigravity in Sui / Shizuku and restart.
 *
 * <p>Never shown when root is already granted or once the user taps "Don't ask again".
 */
public final class RootNoticeBanner {

    private static final String PREFS_NAME = "agy_root_notice";
    private static final String KEY_SUPPRESSED = "suppressed_v1";

    private final Activity activity;
    private final FrameLayout rootLayout;
    private final ThemeManager themeManager;
    private final RootBridge rootBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View bannerView;
    private boolean dismissedForSession = false;

    public RootNoticeBanner(Activity activity, FrameLayout rootLayout,
                            ThemeManager themeManager, RootBridge rootBridge) {
        this.activity = activity;
        this.rootLayout = rootLayout;
        this.themeManager = themeManager;
        this.rootBridge = rootBridge;
    }

    /** Probes root in the background and shows the notice only if not granted. */
    public void evaluateAndShow() {
        if (dismissedForSession || isSuppressed() || rootBridge == null) {
            return;
        }
        rootBridge.probeAsync(true, new RootBridge.ProbeCallback() {
            @Override
            public void onProbeComplete(final RootManagerInfo info) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing()) {
                            return;
                        }
                        if (info.granted) {
                            hide();
                        } else if (!dismissedForSession && !isSuppressed()) {
                            show(info);
                        }
                    }
                });
            }
        });
    }

    public void hide() {
        if (bannerView != null) {
            try {
                rootLayout.removeView(bannerView);
            } catch (Throwable ignored) {
            }
            bannerView = null;
        }
    }

    private boolean isSuppressed() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_SUPPRESSED, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setSuppressed(boolean suppressed) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_SUPPRESSED, suppressed).apply();
        } catch (Throwable ignored) {
        }
    }

    private void show(RootManagerInfo info) {
        hide();
        boolean dark = themeManager != null && "dark".equals(themeManager.getTheme());

        int cardBg = dark ? Color.parseColor("#EE161B26") : Color.parseColor("#F5FFFFFF");
        int borderCol = dark ? Color.parseColor("#334155") : Color.parseColor("#CBD5E1");
        int titleCol = dark ? Color.parseColor("#F8FAFC") : Color.parseColor("#0F172A");
        int bodyCol = dark ? Color.parseColor("#CBD5E1") : Color.parseColor("#334155");
        int accentCol = dark ? Color.parseColor("#38BDF8") : Color.parseColor("#0284C7");
        int mutedCol = dark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B");

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(16);
        int padV = dp(14);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), borderCol);
        card.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText(buildTitle(info));
        title.setTextColor(titleCol);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView body = new TextView(activity);
        body.setText(buildBody(info));
        body.setTextColor(bodyCol);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(6);
        card.addView(body, bodyLp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(10);

        TextView neverBtn = makeActionText("Don't show again", mutedCol);
        neverBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSuppressed(true);
                dismissedForSession = true;
                hide();
            }
        });
        actions.addView(neverBtn);

        TextView dismissBtn = makeActionText("Dismiss", mutedCol);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissedForSession = true;
                hide();
            }
        });
        actions.addView(dismissBtn);

        TextView recheckBtn = makeActionText("Re-check", accentCol);
        recheckBtn.setTypeface(Typeface.DEFAULT_BOLD);
        recheckBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recheckBtn.setText("Checking...");
                recheckBtn.setEnabled(false);
                rootBridge.probeAsync(true, new RootBridge.ProbeCallback() {
                    @Override
                    public void onProbeComplete(final RootManagerInfo refreshed) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (activity.isFinishing()) {
                                    return;
                                }
                                if (refreshed.granted) {
                                    hide();
                                    Toast.makeText(activity,
                                            "Root granted (" + refreshed.managerType + ", uid=0)",
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    recheckBtn.setText("Re-check");
                                    recheckBtn.setEnabled(true);
                                    Toast.makeText(activity,
                                            "Still not granted. Grant in manager & restart Antigravity.",
                                            Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                });
            }
        });
        actions.addView(recheckBtn);

        card.addView(actions, actionsLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        int margin = dp(14);
        lp.setMargins(margin, margin, margin, dp(22));

        bannerView = card;
        rootLayout.addView(card, lp);
    }

    private String buildTitle(RootManagerInfo info) {
        String mgr = info.managerType;
        if ("bakasu".equals(mgr) || "kernelsu".equals(mgr)) {
            return "KernelSU / bakaSU Root Setup";
        }
        if ("sui".equals(mgr)) {
            return "Sui / Root Permission Required";
        }
        if ("magisk".equals(mgr) || "apatch".equals(mgr)) {
            return "Superuser Access Not Granted";
        }
        return "Optional Root / Sui Access";
    }

    private String buildBody(RootManagerInfo info) {
        String pkg = activity.getPackageName();
        String mgr = info.managerType;
        if ("bakasu".equals(mgr) || "kernelsu".equals(mgr)) {
            return "Using KernelSU / bakaSU? Open your root manager, grant Superuser access to Antigravity ("
                    + pkg + "), then fully restart the app.\n"
                    + "If you use the Sui module instead, allow Antigravity in Sui / Shizuku and restart.";
        }
        if ("sui".equals(mgr)) {
            return "If you're using the Sui module, allow Antigravity (" + pkg
                    + ") in Sui / Shizuku Manager, then restart the app.\n"
                    + "If you're using KernelSU / bakaSU, grant Superuser in your root manager first and restart.";
        }
        return "Root (su) is not granted to Antigravity (" + pkg + ").\n"
                + "• KernelSU / bakaSU: Grant Superuser in your root manager first, then restart the app.\n"
                + "• Sui module: Allow Antigravity in Sui / Shizuku, then restart the app.";
    }

    private TextView makeActionText(String label, int color) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int pH = dp(10);
        int pV = dp(6);
        tv.setPadding(pH, pV, pH, pV);
        return tv;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }
}
