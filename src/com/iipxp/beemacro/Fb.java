package com.iipxp.beemacro;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Firebase over plain HTTPS (no SDK, so no Gradle): Auth (email + password) and Firestore documents.
 * Shared by Silly Macro and the Silly Admin app. Who may read or change what is enforced by firestore.rules
 * on Google's side, not by this code.
 */
public final class Fb {
    public static final class Error extends Exception {
        public final String code;   // Firebase's error code, e.g. USER_DISABLED, INVALID_LOGIN_CREDENTIALS, NOT_FOUND, PERMISSION_DENIED, OFFLINE
        Error(String code) { super(code); this.code = code; }
    }

    public final String apiKey, project;
    public Fb(String apiKey, String project) { this.apiKey = apiKey; this.project = project; }

    /** A signed-in user: uid, email, a 1-hour ID token, and the long-lived refresh token. */
    public static final class User { public String uid, email, idToken, refresh; }

    public User signIn(String email, String pw) throws Error { return user(post(auth("signInWithPassword"), creds(email, pw), false)); }
    public User signUp(String email, String pw) throws Error { return user(post(auth("signUp"), creds(email, pw), false)); }
    public void sendReset(String email) throws Error {
        try { post(auth("sendOobCode"), new JSONObject().put("requestType", "PASSWORD_RESET").put("email", email).toString(), false); }
        catch (org.json.JSONException e) { throw new Error("BAD_JSON"); }
    }
    /** New ID token. Fails with USER_DISABLED once the account is disabled in the Firebase console. */
    public User refresh(String refreshToken) throws Error {
        JSONObject j = post("https://securetoken.googleapis.com/v1/token?key=" + apiKey, "grant_type=refresh_token&refresh_token=" + enc(refreshToken), true);
        User u = new User(); u.uid = j.optString("user_id"); u.idToken = j.optString("id_token"); u.refresh = j.optString("refresh_token");
        return u;
    }

    // ---- Firestore (documents are {"fields": {name: {"stringValue": ...}, ...}})
    String doc(String path) { return "https://firestore.googleapis.com/v1/projects/" + project + "/databases/(default)/documents/" + path; }
    /** The document, or null if it doesn't exist. */
    public JSONObject get(String idToken, String path) throws Error {
        try { return call("GET", doc(path), idToken, null); } catch (Error e) { if (e.code.equals("NOT_FOUND")) return null; throw e; }
    }
    /** Writes only the given fields (the rest of the document is kept). */
    public void patch(String idToken, String path, JSONObject fields) throws Error {
        StringBuilder mask = new StringBuilder();
        for (java.util.Iterator<String> it = fields.keys(); it.hasNext(); ) mask.append(mask.length() == 0 ? "?" : "&").append("updateMask.fieldPaths=").append(enc(it.next()));
        try { call("PATCH", doc(path) + mask, idToken, new JSONObject().put("fields", fields).toString()); }
        catch (org.json.JSONException e) { throw new Error("BAD_JSON"); }
    }
    /** All documents of a collection (follows pages). */
    public java.util.List<JSONObject> list(String idToken, String collection) throws Error {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        String next = "";
        do {
            JSONObject j = call("GET", doc(collection) + "?pageSize=300" + (next.isEmpty() ? "" : "&pageToken=" + enc(next)), idToken, null);
            org.json.JSONArray a = j.optJSONArray("documents");
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i));
            next = j.optString("nextPageToken", "");
        } while (!next.isEmpty());
        return out;
    }
    public static String str(JSONObject doc, String f) { JSONObject v = field(doc, f); return v == null ? "" : v.optString("stringValue", v.optString("timestampValue", "")); }
    public static boolean bool(JSONObject doc, String f) { JSONObject v = field(doc, f); return v != null && v.optBoolean("booleanValue", false); }
    static JSONObject field(JSONObject doc, String f) { JSONObject fs = doc == null ? null : doc.optJSONObject("fields"); return fs == null ? null : fs.optJSONObject(f); }
    public static JSONObject s(String v) { try { return new JSONObject().put("stringValue", v); } catch (org.json.JSONException e) { throw new RuntimeException(e); } }
    public static JSONObject b(boolean v) { try { return new JSONObject().put("booleanValue", v); } catch (org.json.JSONException e) { throw new RuntimeException(e); } }
    public static JSONObject now() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try { return new JSONObject().put("timestampValue", f.format(new java.util.Date())); } catch (org.json.JSONException e) { throw new RuntimeException(e); }
    }

    // ---- HTTP
    String auth(String op) { return "https://identitytoolkit.googleapis.com/v1/accounts:" + op + "?key=" + apiKey; }
    static String creds(String email, String pw) {
        try { return new JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true).toString(); }
        catch (org.json.JSONException e) { throw new RuntimeException(e); }
    }
    static User user(JSONObject j) { User u = new User(); u.uid = j.optString("localId"); u.email = j.optString("email"); u.idToken = j.optString("idToken"); u.refresh = j.optString("refreshToken"); return u; }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    JSONObject post(String url, String body, boolean form) throws Error { return send("POST", url, null, body, form); }
    JSONObject call(String method, String url, String idToken, String body) throws Error { return send(method, url, idToken, body, false); }

    static JSONObject send(String method, String url, String idToken, String body, boolean form) throws Error {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(15000);
            if (method.equals("PATCH")) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
            else c.setRequestMethod(method);
            if (idToken != null) c.setRequestProperty("Authorization", "Bearer " + idToken);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            String text = read(code / 100 == 2 ? c.getInputStream() : c.getErrorStream());
            JSONObject j = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (code / 100 == 2) return j;
            JSONObject err = j.optJSONObject("error");
            String msg = err == null ? "" : err.optString("message", "");
            String status = err == null ? "" : err.optString("status", "");
            // Auth gives e.g. "USER_DISABLED" or "TOO_MANY_ATTEMPTS_TRY_LATER : ..."; Firestore gives status NOT_FOUND / PERMISSION_DENIED
            String k = msg.matches("[A-Z_]+( :.*)?") ? msg.split(" ")[0] : !status.isEmpty() ? status : "HTTP_" + code;
            throw new Error(k);
        } catch (Error e) { throw e; }
        catch (java.io.IOException e) { throw new Error("OFFLINE"); }
        catch (org.json.JSONException e) { throw new Error("BAD_JSON"); }
        finally { if (c != null) c.disconnect(); }
    }
    static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        try (java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) { return s.hasNext() ? s.next() : ""; }   // readAllBytes is Android 13+
    }

    /** Readable text for an error code. */
    public static String explain(String code) {
        switch (code) {
            case "INVALID_LOGIN_CREDENTIALS": case "INVALID_PASSWORD": case "EMAIL_NOT_FOUND": return "Wrong email or password.";
            case "EMAIL_EXISTS": return "That email already has an account. Sign in instead.";
            case "WEAK_PASSWORD": return "Use a password of at least 6 characters.";
            case "INVALID_EMAIL": return "That isn't a valid email address.";
            case "USER_DISABLED": return "This account has been disabled.";
            case "TOO_MANY_ATTEMPTS_TRY_LATER": return "Too many attempts. Try again in a few minutes.";
            case "OFFLINE": return "No connection to the account server.";
            case "PERMISSION_DENIED": return "Not allowed (check the Firestore rules / admin UID).";
            default: return "Account server error: " + code;
        }
    }
}
