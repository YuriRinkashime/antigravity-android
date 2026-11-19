package com.agy.ui.webview;

import android.util.Log;
import android.webkit.WebView;

/** WebView resume/timer/memory lifecycle, kept out of the activity. */
public final class WebViewLifecycle {

    private static final String TAG = "WebViewHost";

    private WebViewLifecycle() {
    }

    /** Resume the page and tell it the host is back in front. */
    public static void resume(WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.onResume();
            webView.resumeTimers();
            webView.evaluateJavascript(
                    "(function(){ try { "
                            + "window.dispatchEvent(new CustomEvent('agy:host-resume')); "
                            + "window.dispatchEvent(new Event('focus')); "
                            + "window.dispatchEvent(new Event('online')); "
                            + "if (typeof document !== 'undefined') { document.dispatchEvent(new Event('visibilitychange')); } "
                            + "} catch(e){} })();",
                    null);
        } catch (Exception e) {
            Log.w(TAG, "failed to resume webView", e);
        }
    }

    /**
     * Pauses page callbacks while the host activity is not visible.
     * Note: pauseTimers() is intentionally omitted so active agent event streams
     * and background UI sync continue processing without net::ERR_FAILED disconnects.
     */
    public static void pause(WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.onPause();
        } catch (Exception e) {
            Log.w(TAG, "failed to pause webView", e);
        }
    }

    public static void freeMemory(WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.freeMemory();
        } catch (Exception e) {
            Log.w(TAG, "freeMemory failed", e);
        }
    }
}
