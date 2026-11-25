package com.agy.ui.webview;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Reads pasted image URIs from Android's clipboard or soft-keyboard InputContentInfo
 * and encodes them as Base64 payloads for the chat WebView.
 */
public final class ClipboardImageReader {

    private static final String TAG = "ClipboardImageReader";
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

    public static final class ImageData {
        public final String base64;
        public final String mimeType;
        public final String filename;

        public ImageData(String base64, String mimeType, String filename) {
            this.base64 = base64 != null ? base64 : "";
            this.mimeType = mimeType != null ? mimeType : "image/png";
            this.filename = filename != null ? filename : "pasted_image.png";
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("base64", base64);
                o.put("mimeType", mimeType);
                o.put("filename", filename);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private ClipboardImageReader() {
    }

    public static ImageData readFromClipboard(Context context) {
        if (context == null) {
            return null;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            ClipData.Item item = clip.getItemAt(0);
            Uri uri = item != null ? item.getUri() : null;
            if (uri == null) {
                return null;
            }
            String preferredMime = null;
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                for (int i = 0; i < desc.getMimeTypeCount(); i++) {
                    String m = desc.getMimeType(i);
                    if (m != null && m.toLowerCase(Locale.US).startsWith("image/")
                            && !"image/*".equalsIgnoreCase(m)) {
                        preferredMime = m;
                        break;
                    }
                }
            }
            String resolved = resolveMimeType(context, uri, preferredMime);
            if (!resolved.toLowerCase(Locale.US).startsWith("image/")) {
                return null;
            }
            return readFromUri(context, uri, resolved);
        } catch (Throwable t) {
            Log.w(TAG, "readFromClipboard failed: " + t.getMessage());
            return null;
        }
    }

    public static ImageData readFromUri(Context context, Uri uri, String preferredMime) {
        if (context == null || uri == null) {
            return null;
        }
        ContentResolver cr = context.getContentResolver();
        if (cr == null) {
            return null;
        }
        String mime = resolveMimeType(context, uri, preferredMime);
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "pasted image exceeds 16 MiB cap; ignoring");
                    return null;
                }
                out.write(buf, 0, n);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                return null;
            }
            String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
            String filename = "pasted_image_" + System.currentTimeMillis() + extensionForMime(mime);
            return new ImageData(base64, mime, filename);
        } catch (Throwable t) {
            Log.w(TAG, "readFromUri failed: " + t.getMessage());
            return null;
        }
    }

    private static String resolveMimeType(Context context, Uri uri, String preferredMime) {
        if (preferredMime != null) {
            String low = preferredMime.trim().toLowerCase(Locale.US);
            if (low.startsWith("image/") && !"image/*".equals(low)) {
                return low;
            }
        }
        try {
            ContentResolver cr = context.getContentResolver();
            if (cr != null) {
                String type = cr.getType(uri);
                if (type != null) {
                    String low = type.trim().toLowerCase(Locale.US);
                    if (low.startsWith("image/") && !"image/*".equals(low)) {
                        return low;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "image/png";
    }

    private static String extensionForMime(String mime) {
        if (mime == null) {
            return ".png";
        }
        String low = mime.toLowerCase(Locale.US);
        if (low.contains("jpeg") || low.contains("jpg")) {
            return ".jpg";
        }
        if (low.contains("webp")) {
            return ".webp";
        }
        if (low.contains("gif")) {
            return ".gif";
        }
        return ".png";
    }
}
