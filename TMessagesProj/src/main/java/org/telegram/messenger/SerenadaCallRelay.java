package org.telegram.messenger;

import android.net.Uri;
import android.os.SystemClock;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class SerenadaCallRelay {

    private static final String KEY_ENABLED = "serenada_calls_enabled";
    private static final String KEY_BASE_URL = "serenada_base_url";
    private static final String DEFAULT_BASE_URL = "https://serenada-app.ru";
    private static final long CACHE_SAFETY_MARGIN_MS = 1000L;
    private static final int HTTP_TIMEOUT_MS = 1400;

    private static final Object LOCK = new Object();
    private static volatile Credentials cachedCredentials;
    private static volatile long cachedUntilRealtime;
    private static volatile boolean fetchInProgress;

    private SerenadaCallRelay() {
    }

    public static final class Endpoint {
        public final String host;
        public final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String asKey() {
            return host + ":" + port;
        }
    }

    public static final class Credentials {
        public final String username;
        public final String password;
        public final ArrayList<Endpoint> endpoints;
        public final int ttlSeconds;

        Credentials(String username, String password, ArrayList<Endpoint> endpoints, int ttlSeconds) {
            this.username = username;
            this.password = password;
            this.endpoints = endpoints;
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(KEY_ENABLED, true);
    }

    public static void prepareForCallRouting() {
        if (!isEnabled() || hasFreshCredentials()) {
            return;
        }
        synchronized (LOCK) {
            if (fetchInProgress) {
                return;
            }
            fetchInProgress = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                fetchAndCacheCredentials();
            } finally {
                synchronized (LOCK) {
                    fetchInProgress = false;
                }
            }
        });
    }

    public static Credentials getCredentialsForCall() {
        if (!isEnabled()) {
            return null;
        }
        if (hasFreshCredentials()) {
            return cachedCredentials;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prepareForCallRouting();
            return null;
        }
        fetchAndCacheCredentials();
        return hasFreshCredentials() ? cachedCredentials : null;
    }

    private static boolean hasFreshCredentials() {
        return cachedCredentials != null && SystemClock.elapsedRealtime() < cachedUntilRealtime;
    }

    private static void fetchAndCacheCredentials() {
        try {
            String baseUrl = normalizeBaseUrl(MessagesController.getGlobalMainSettings().getString(KEY_BASE_URL, DEFAULT_BASE_URL));
            if (TextUtils.isEmpty(baseUrl)) {
                return;
            }
            String token = requestTurnToken(baseUrl);
            if (TextUtils.isEmpty(token)) {
                return;
            }
            JSONObject turnConfig = requestTurnCredentials(baseUrl, token);
            if (turnConfig == null) {
                return;
            }
            Credentials parsed = parseCredentials(turnConfig);
            if (parsed == null || parsed.endpoints.isEmpty()) {
                return;
            }
            long ttlMs = Math.max(1000L, parsed.ttlSeconds * 1000L - CACHE_SAFETY_MARGIN_MS);
            cachedCredentials = parsed;
            cachedUntilRealtime = SystemClock.elapsedRealtime() + ttlMs;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("serenada relay credentials ready, ttl=" + parsed.ttlSeconds + "s, endpoints=" + parsed.endpoints.size());
            }
        } catch (Throwable t) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("serenada relay fetch failed", t);
            }
        }
    }

    private static String normalizeBaseUrl(String value) {
        String baseUrl = value == null ? "" : value.trim();
        if (TextUtils.isEmpty(baseUrl)) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static String requestTurnToken(String baseUrl) throws Exception {
        JSONObject json = requestJson(baseUrl + "/api/diagnostic-token", "POST", null);
        if (json == null) {
            return null;
        }
        String token = json.optString("token", null);
        return TextUtils.isEmpty(token) ? null : token.trim();
    }

    private static JSONObject requestTurnCredentials(String baseUrl, String token) throws Exception {
        String requestUrl = baseUrl + "/api/turn-credentials?token=" + Uri.encode(token);
        return requestJson(requestUrl, "GET", null);
    }

    private static Credentials parseCredentials(JSONObject json) {
        String username = json.optString("username", "").trim();
        String password = json.optString("password", "").trim();
        int ttl = json.optInt("ttl", 5);
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            return null;
        }

        JSONArray uris = json.optJSONArray("uris");
        ArrayList<Endpoint> turnEndpoints = new ArrayList<>();
        ArrayList<Endpoint> turnsEndpoints = new ArrayList<>();
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();

        if (uris != null) {
            for (int i = 0; i < uris.length(); i++) {
                String rawUri = uris.optString(i, "");
                if (TextUtils.isEmpty(rawUri)) {
                    continue;
                }
                Endpoint endpoint = parseUri(rawUri);
                if (endpoint == null) {
                    continue;
                }
                if (!dedupe.add(endpoint.asKey())) {
                    continue;
                }
                String scheme = Uri.parse(rawUri).getScheme();
                if ("turn".equalsIgnoreCase(scheme)) {
                    turnEndpoints.add(endpoint);
                } else if ("turns".equalsIgnoreCase(scheme)) {
                    turnsEndpoints.add(endpoint);
                }
            }
        }

        ArrayList<Endpoint> endpoints = !turnEndpoints.isEmpty() ? turnEndpoints : turnsEndpoints;
        if (endpoints.isEmpty()) {
            return null;
        }
        return new Credentials(username, password, endpoints, Math.max(1, ttl));
    }

    private static Endpoint parseUri(String rawUri) {
        try {
            Uri uri = Uri.parse(rawUri);
            String scheme = uri.getScheme();
            if (!"turn".equalsIgnoreCase(scheme) && !"turns".equalsIgnoreCase(scheme)) {
                return null;
            }
            String host = uri.getHost();
            if (TextUtils.isEmpty(host)) {
                String cleaned = rawUri.trim();
                int schemeSep = cleaned.indexOf(':');
                String rest = schemeSep >= 0 ? cleaned.substring(schemeSep + 1) : cleaned;
                if (rest.startsWith("//")) {
                    rest = rest.substring(2);
                }
                int query = rest.indexOf('?');
                if (query >= 0) {
                    rest = rest.substring(0, query);
                }
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    rest = rest.substring(0, slash);
                }
                int colon = rest.lastIndexOf(':');
                host = colon >= 0 ? rest.substring(0, colon) : rest;
            }
            if (TextUtils.isEmpty(host)) {
                return null;
            }
            int port = uri.getPort();
            if (port <= 0 || port > 65535) {
                port = "turns".equalsIgnoreCase(scheme) ? 5349 : 3478;
            }
            return new Endpoint(host.toLowerCase(Locale.US), port);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static JSONObject requestJson(String url, String method, String body) throws Exception {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(bytes);
                }
            } else if ("POST".equals(method)) {
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(new byte[0]);
                }
            }

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("serenada relay http " + code + " for " + url);
                }
                return null;
            }
            stream = connection.getInputStream();
            String response = readAll(stream);
            if (TextUtils.isEmpty(response)) {
                return null;
            }
            return new JSONObject(response);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignore) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, count);
            }
        }
        return builder.toString();
    }
}
