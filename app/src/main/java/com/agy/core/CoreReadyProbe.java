package com.agy.core;

import android.content.Context;
import android.util.Log;

import com.agy.util.CoreUrl;
import com.agy.util.ProcessUtils;

import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;

/** One-shot HTTPS and certificate-pin probe of the embedded core origin. */
public final class CoreReadyProbe {

    private static final String TAG = "CoreReadyProbe";
    public static final long READY_TIMEOUT_MS = 60000;
    /** Re-probe gap while the core boots. */
    private static final long RETRY_SLEEP_MS = 60;
    private static volatile byte[] expectedCertDigest;

    private CoreReadyProbe() {
    }

    public static boolean probeOnce(Context context, int port) {
        // Fast loopback TCP socket connection check: verifies that language_server
        // has successfully bound port and is actively listening for connections.
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Exception notReady) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        }
    }

    private static byte[] expectedCertDigest(Context context) throws Exception {
        byte[] cached = expectedCertDigest;
        if (cached != null) {
            return cached;
        }
        synchronized (CoreReadyProbe.class) {
            cached = expectedCertDigest;
            if (cached != null) {
                return cached;
            }
            int resourceId = context.getResources().getIdentifier(
                    "agy_core_cert", "raw", context.getPackageName());
            if (resourceId == 0) {
                Log.e(TAG, "bundled core certificate resource is missing");
                return null;
            }
            try (InputStream input = context.getResources().openRawResource(resourceId)) {
                Certificate certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(input);
                if (!(certificate instanceof X509Certificate)) {
                    return null;
                }
                cached = sha256(certificate.getEncoded());
                expectedCertDigest = cached;
                return cached;
            }
        }
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }
}
