package org.chromium.chrome.browser.app;

import android.util.Log;

import org.chromium.build.BuildConfig; // Chromium's generated BuildConfig

import java.io.DataOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files; // Requires API 26+ or desugaring

public class BraveSentryUploader {
    private static final String SENTRY_MINIDUMP_URL =
        "https://o4509807793733632.ingest.de.sentry.io/api/4509807795372112/minidump/?sentry_key=591d5ee7a98b1fdeca311066ff238573";

    public static void uploadMinidumpToSentry(File minidumpFile) {
        new Thread(() -> {
            try {
                String boundary = "----SentryBoundary" + System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(SENTRY_MINIDUMP_URL).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    // Minidump file
                    out.writeBytes("--" + boundary + "\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"upload_file_minidump\"; filename=\"" + minidumpFile.getName() + "\"\r\n");
                    out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                    Files.copy(minidumpFile.toPath(), out);
                    out.writeBytes("\r\n");

                    // Optional: release info
                    out.writeBytes("--" + boundary + "\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"sentry[release]\"\r\n\r\n");
                    out.writeBytes("chromium@" + "0.0.217" + "\r\n");

                    out.writeBytes("--" + boundary + "--\r\n");
                }

                int responseCode = conn.getResponseCode();
                Log.i("BraveSentry", "Sentry minidump upload response: " + responseCode);
            } catch (Exception e) {
                Log.e("BraveSentry", "Failed to upload minidump to Sentry", e);
            }
        }).start();
    }
}