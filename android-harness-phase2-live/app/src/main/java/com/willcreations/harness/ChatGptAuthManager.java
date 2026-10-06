package com.willcreations.harness;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChatGptAuthManager {
    public interface Listener {
        void onChatGptAuthState(String state);
        void onChatGptModels(String firstSlug, String displayText);
    }

    private static final String AUTHORIZE = "https://auth.openai.com/api/accounts/authorize";
    private static final String TOKEN = "https://auth.openai.com/api/accounts/oauth/token";
    private static final String REVOKE = "https://auth.openai.com/api/accounts/oauth/revoke";
    private static final String JWKS = "https://auth.openai.com/.well-known/jwks.json";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String DYNAMIC_CLIENT = "dynamic_agent_client";
    private static final String AGENT_NAME = "Will Harness";

    private static final String ACCESS_SECRET = "chatgpt_oauth_access_token";
    private static final String REFRESH_SECRET = "chatgpt_oauth_refresh_token";
    private static final String ID_SECRET = "chatgpt_oauth_id_token";

    private static final String PREFS = "chatgpt_auth";
    private static final String HOST_ID = "host_id";
    private static final String CLIENT_ID = "client_id";
    private static final String SUBJECT = "subject";
    private static final String EMAIL = "email";
    private static final String SCOPES = "scopes";
    private static final String EXPIRES_AT = "expires_at_ms";

    private final Context context;
    private final SecretStore secrets;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean signingIn = new AtomicBoolean(false);
    private final SecureRandom random = new SecureRandom();
    private final OpenAiIdTokenVerifier verifier = new OpenAiIdTokenVerifier();

    public ChatGptAuthManager(Context context, SecretStore secrets, Listener listener) {
        this.context = context.getApplicationContext();
        this.secrets = secrets;
        this.listener = listener;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureHostId();
    }

    public boolean isSignedIn() {
        return !prefs.getString(CLIENT_ID, "").isEmpty()
                && hasRequiredScope(prefs.getString(SCOPES, ""))
                && secrets.hasSecret(ACCESS_SECRET)
                && secrets.hasSecret(REFRESH_SECRET);
    }

    public String accountLabel() {
        String email = prefs.getString(EMAIL, "");
        if (!email.isEmpty()) return email;
        String subject = prefs.getString(SUBJECT, "");
        return subject.isEmpty() ? "未ログイン" : subject;
    }

    public void startSignIn() {
        if (!signingIn.compareAndSet(false, true)) {
            emitState("ChatGPTログイン処理はすでに実行中です");
            return;
        }
        worker.execute(() -> {
            try {
                runSignIn();
            } catch (Throwable t) {
                emitState("ChatGPTログイン失敗: " + safeMessage(t));
            } finally {
                signingIn.set(false);
            }
        });
    }

    public synchronized String getValidAccessToken() throws Exception {
        if (!isSignedIn()) throw new IllegalStateException("ChatGPTにログインしていません");
        long expiresAt = prefs.getLong(EXPIRES_AT, 0L);
        String access = secrets.loadSecret(ACCESS_SECRET);
        if (!access.isEmpty() && expiresAt > System.currentTimeMillis() + 120_000L) return access;
        return refreshAccessToken();
    }

    public void listModels() {
        worker.execute(() -> {
            try {
                String token = getValidAccessToken();
                HttpResult result = request("GET", RESOURCE + "/models", token, null, null, null);
                if (result.code < 200 || result.code >= 300) throw httpError("Models", result);

                JSONObject root = new JSONObject(result.body);
                JSONArray models = root.optJSONArray("models");
                if (models == null) models = root.optJSONArray("data");
                if (models == null) throw new IllegalStateException("Model catalog missing");

                String first = "";
                StringBuilder display = new StringBuilder();
                int visibleCount = 0;
                for (int i = 0; i < models.length() && visibleCount < 30; i++) {
                    JSONObject m = models.optJSONObject(i);
                    if (m == null) continue;
                    String visibility = m.optString("visibility", "");
                    if (!visibility.isEmpty() && !"list".equals(visibility)) continue;
                    String slug = m.optString("slug", m.optString("id", ""));
                    if (slug.isEmpty()) continue;
                    if (first.isEmpty()) first = slug;
                    String label = m.optString("display_name", slug);
                    display.append(label).append("  [").append(slug).append("]\n");
                    visibleCount++;
                }
                if (first.isEmpty()) throw new IllegalStateException("利用可能なモデルが見つかりません");
                emitModels(first, display.toString());
            } catch (Throwable t) {
                emitState("モデル取得失敗: " + safeMessage(t));
            }
        });
    }

    public void signOut() {
        worker.execute(() -> {
            boolean revoked = false;
            try {
                String refresh = secrets.loadSecret(REFRESH_SECRET);
                String clientId = prefs.getString(CLIENT_ID, "");
                if (!refresh.isEmpty() && !clientId.isEmpty()) {
                    String form = form(
                            "token", refresh,
                            "token_type_hint", "refresh_token",
                            "client_id", clientId
                    );
                    HttpResult result = request("POST_FORM", REVOKE, null, null, form, null);
                    revoked = result.code == 200;
                }
            } catch (Throwable ignored) {
            } finally {
                clearCredentials();
                emitState(revoked
                        ? "ChatGPTからログアウトしました"
                        : "ローカルのChatGPTログイン情報を削除しました（リモート失効は未確認）");
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private void runSignIn() throws Exception {
        emitState("ChatGPTログイン準備中…");
        String hostId = ensureHostId();
        String savedClient = prefs.getString(CLIENT_ID, "");
        boolean firstRegistration = savedClient.isEmpty();
        String authClient = firstRegistration ? DYNAMIC_CLIENT : savedClient;

        String state = randomUrl(32);
        String nonce = randomUrl(32);
        String verifierValue = randomUrl(64);
        String challenge = base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifierValue.getBytes(StandardCharsets.US_ASCII)));

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(180_000);
            int port = server.getLocalPort();
            String redirect = "http://127.0.0.1:" + port + "/auth/callback";

            Uri.Builder builder = Uri.parse(AUTHORIZE).buildUpon()
                    .appendQueryParameter("client_id", authClient)
                    .appendQueryParameter("ext_agent_host_id", hostId)
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("scope", SCOPE)
                    .appendQueryParameter("resource", RESOURCE)
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", challenge);

            if (firstRegistration) {
                builder.appendQueryParameter("agent_name_hint", AGENT_NAME);
            } else {
                String idTokenHint = secrets.loadSecret(ID_SECRET);
                if (!idTokenHint.isEmpty()) builder.appendQueryParameter("id_token_hint", idTokenHint);
                String email = prefs.getString(EMAIL, "");
                if (!email.isEmpty()) builder.appendQueryParameter("login_hint", email);
            }

            String authorizationUrl = builder.build().toString();
            main.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    listener.onChatGptAuthState("ブラウザでChatGPTにログインしてください");
                } catch (Throwable t) {
                    listener.onChatGptAuthState("ブラウザを開けません: " + safeMessage(t));
                }
            });

            try (Socket socket = server.accept()) {
                socket.setSoTimeout(15_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                Map<String, String> query = parseCallbackRequest(requestLine);

                if (!state.equals(query.get("state"))) {
                    sendBrowserResponse(socket, false, "stateが一致しません。アプリに戻ってやり直してください。");
                    throw new SecurityException("OAuth state mismatch");
                }
                String oauthError = query.get("error");
                if (oauthError != null && !oauthError.isEmpty()) {
                    sendBrowserResponse(socket, false, "ChatGPTログインが許可されませんでした。");
                    throw new IllegalStateException("OAuth error: " + oauthError);
                }

                String code = query.get("code");
                if (code == null || code.isEmpty()) {
                    sendBrowserResponse(socket, false, "認証コードがありません。");
                    throw new IllegalStateException("Authorization code missing");
                }

                String callbackClient = query.get("client_id");
                String issuedClient;
                if (firstRegistration) {
                    if (callbackClient == null || callbackClient.isEmpty() || DYNAMIC_CLIENT.equals(callbackClient)) {
                        sendBrowserResponse(socket, false, "OpenAIクライアント登録が完了しませんでした。");
                        throw new SecurityException("Issued client_id missing");
                    }
                    issuedClient = callbackClient;
                } else {
                    issuedClient = savedClient;
                    if (callbackClient != null && !callbackClient.isEmpty() && !savedClient.equals(callbackClient)) {
                        sendBrowserResponse(socket, false, "登録済みクライアントと一致しません。");
                        throw new SecurityException("OAuth client_id changed unexpectedly");
                    }
                }

                JSONObject tokens = exchangeCode(code, issuedClient, verifierValue, redirect);
                String accessToken = tokens.optString("access_token", "");
                String refreshToken = tokens.optString("refresh_token", "");
                String idToken = tokens.optString("id_token", "");
                String scopes = tokens.optString("scope", "");
                long expiresIn = tokens.optLong("expires_in", 3600);

                if (accessToken.isEmpty() || refreshToken.isEmpty() || idToken.isEmpty()) {
                    sendBrowserResponse(socket, false, "必要な認証情報を受け取れませんでした。");
                    throw new SecurityException("OAuth token response incomplete");
                }
                if (!hasRequiredScope(scopes)) {
                    sendBrowserResponse(socket, false, "ChatGPTプラン利用権限が許可されませんでした。");
                    throw new SecurityException("chatgpt.tokens.use.direct scope not granted");
                }

                String jwks = getText(JWKS);
                OpenAiIdTokenVerifier.Claims claims = verifier.verify(idToken, jwks, issuedClient, nonce);

                secrets.saveSecret(ACCESS_SECRET, accessToken);
                secrets.saveSecret(REFRESH_SECRET, refreshToken);
                secrets.saveSecret(ID_SECRET, idToken);
                prefs.edit()
                        .putString(CLIENT_ID, issuedClient)
                        .putString(SUBJECT, claims.subject)
                        .putString(EMAIL, claims.email)
                        .putString(SCOPES, scopes)
                        .putLong(EXPIRES_AT, System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L)
                        .apply();

                sendBrowserResponse(socket, true, "ChatGPTログイン完了。Will Harnessに戻ってください。");
                emitState("ChatGPTログイン済み: " + accountLabel());
            }
        }
    }

    private synchronized String refreshAccessToken() throws Exception {
        String clientId = prefs.getString(CLIENT_ID, "");
        String refreshToken = secrets.loadSecret(REFRESH_SECRET);
        if (clientId.isEmpty() || refreshToken.isEmpty()) throw new IllegalStateException("ChatGPT再ログインが必要です");

        String form = form(
                "grant_type", "refresh_token",
                "client_id", clientId,
                "refresh_token", refreshToken,
                "resource", RESOURCE
        );
        HttpResult result = request("POST_FORM", TOKEN, null, null, form, null);
        if (result.code < 200 || result.code >= 300) {
            if (result.body.contains("invalid_grant") || result.body.contains("refresh_token_expired")
                    || result.body.contains("refresh_token_invalidated") || result.body.contains("refresh_token_reused")) {
                clearCredentials();
            }
            throw httpError("ChatGPT token refresh", result);
        }

        JSONObject tokens = new JSONObject(result.body);
        String access = tokens.optString("access_token", "");
        String replacementRefresh = tokens.optString("refresh_token", "");
        String scope = tokens.optString("scope", prefs.getString(SCOPES, ""));
        long expiresIn = tokens.optLong("expires_in", 3600);

        if (access.isEmpty()) throw new SecurityException("Refreshed access token missing");
        if (!hasRequiredScope(scope)) throw new SecurityException("ChatGPT plan usage scope missing after refresh");

        secrets.saveSecret(ACCESS_SECRET, access);
        if (!replacementRefresh.isEmpty()) secrets.saveSecret(REFRESH_SECRET, replacementRefresh);
        String replacementId = tokens.optString("id_token", "");
        if (!replacementId.isEmpty()) secrets.saveSecret(ID_SECRET, replacementId);
        prefs.edit()
                .putString(SCOPES, scope)
                .putLong(EXPIRES_AT, System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L)
                .apply();
        return access;
    }

    private JSONObject exchangeCode(String code, String issuedClient, String verifierValue, String redirect) throws Exception {
        String form = form(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", redirect,
                "client_id", issuedClient,
                "code_verifier", verifierValue,
                "resource", RESOURCE
        );
        HttpResult result = request("POST_FORM", TOKEN, null, null, form, null);
        if (result.code < 200 || result.code >= 300) throw httpError("ChatGPT code exchange", result);
        return new JSONObject(result.body);
    }

    private String ensureHostId() {
        String host = prefs.getString(HOST_ID, "");
        if (!host.isEmpty()) return host;
        host = "urn:uuid:" + UUID.randomUUID();
        prefs.edit().putString(HOST_ID, host).apply();
        return host;
    }

    private void clearCredentials() {
        secrets.clearSecret(ACCESS_SECRET);
        secrets.clearSecret(REFRESH_SECRET);
        secrets.clearSecret(ID_SECRET);
        prefs.edit()
                .remove(SUBJECT)
                .remove(EMAIL)
                .remove(SCOPES)
                .remove(EXPIRES_AT)
                .apply();
        // Keep CLIENT_ID and HOST_ID so a later reauthorization can reuse the registered client.
    }

    private boolean hasRequiredScope(String scopes) {
        if (scopes == null) return false;
        for (String scope : scopes.trim().split("\\s+")) {
            if ("chatgpt.tokens.use.direct".equals(scope)) return true;
        }
        return false;
    }

    private String randomUrl(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return base64Url(value);
    }

    private String base64Url(byte[] value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private Map<String, String> parseCallbackRequest(String requestLine) throws Exception {
        if (requestLine == null || !requestLine.startsWith("GET ")) throw new SecurityException("Invalid callback request");
        String target = requestLine.split(" ")[1];
        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        if (!"/auth/callback".equals(path)) throw new SecurityException("Unexpected callback path");

        Map<String, String> values = new HashMap<>();
        if (q < 0 || q == target.length() - 1) return values;
        String query = target.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            values.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
        }
        return values;
    }

    private void sendBrowserResponse(Socket socket, boolean success, String message) {
        try {
            String title = success ? "Will Harness — Success" : "Will Harness — Error";
            String html = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
                    "<title>" + title + "</title></head><body style='font-family:sans-serif;padding:32px'>" +
                    "<h2>" + (success ? "ログイン完了" : "ログインエラー") + "</h2><p>" + escapeHtml(message) + "</p></body></html>";
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            PrintWriter headers = new PrintWriter(out, false, StandardCharsets.US_ASCII);
            headers.print("HTTP/1.1 " + (success ? "200 OK" : "400 Bad Request") + "\r\n");
            headers.print("Content-Type: text/html; charset=utf-8\r\n");
            headers.print("Cache-Control: no-store\r\n");
            headers.print("Connection: close\r\n");
            headers.print("Content-Length: " + body.length + "\r\n\r\n");
            headers.flush();
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {}
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String getText(String endpoint) throws Exception {
        HttpResult result = request("GET", endpoint, null, null, null, null);
        if (result.code < 200 || result.code >= 300) throw httpError("GET", result);
        return result.body;
    }

    private HttpResult request(String method, String endpoint, String bearer, JSONObject extraHeaders, String formBody, JSONObject jsonBody) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST_FORM".equals(method) ? "POST" : method);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (extraHeaders != null) {
            java.util.Iterator<String> it = extraHeaders.keys();
            while (it.hasNext()) {
                String k = it.next();
                connection.setRequestProperty(k, extraHeaders.optString(k, ""));
            }
        }

        byte[] body = null;
        if ("POST_FORM".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            body = formBody == null ? new byte[0] : formBody.getBytes(StandardCharsets.UTF_8);
        } else if (jsonBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            body = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (body != null) {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        return new HttpResult(code, readAll(stream));
    }

    private String form(String... pairs) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(pairs[i], "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(pairs[i + 1] == null ? "" : pairs[i + 1], "UTF-8"));
        }
        return b.toString();
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) b.append(line).append('\n');
        }
        return b.toString();
    }

    private IllegalStateException httpError(String where, HttpResult result) {
        String body = result.body == null ? "" : result.body;
        if (body.length() > 700) body = body.substring(0, 700) + "…";
        return new IllegalStateException(where + " HTTP " + result.code + ": " + body);
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private void emitState(String state) {
        main.post(() -> listener.onChatGptAuthState(state));
    }

    private void emitModels(String firstSlug, String display) {
        main.post(() -> listener.onChatGptModels(firstSlug, display));
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
