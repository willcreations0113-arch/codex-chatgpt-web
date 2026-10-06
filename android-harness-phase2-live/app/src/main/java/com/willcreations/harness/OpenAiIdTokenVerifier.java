package com.willcreations.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

public final class OpenAiIdTokenVerifier {
    public static final String ISSUER = "https://auth.openai.com";

    public Claims verify(String jwt, String jwksJson, String expectedClientId, String expectedNonce) throws Exception {
        return verify(jwt, jwksJson, expectedClientId, expectedNonce, System.currentTimeMillis() / 1000L);
    }

    Claims verify(String jwt, String jwksJson, String expectedClientId, String expectedNonce, long nowSeconds) throws Exception {
        if (jwt == null || jwt.trim().isEmpty()) throw new SecurityException("ID token is empty");
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) throw new SecurityException("Malformed ID token");

        JSONObject header = jsonPart(parts[0]);
        if (!"RS256".equals(header.optString("alg"))) throw new SecurityException("Unsupported ID token alg");
        String kid = header.optString("kid", "");
        if (kid.isEmpty()) throw new SecurityException("ID token kid is missing");

        JSONObject jwk = findKey(new JSONObject(jwksJson), kid);
        PublicKey key = rsaKey(jwk);

        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(decodeUrl(parts[2]))) throw new SecurityException("Invalid ID token signature");

        JSONObject payload = jsonPart(parts[1]);
        if (!ISSUER.equals(payload.optString("iss"))) throw new SecurityException("Unexpected ID token issuer");
        if (!audienceContains(payload.opt("aud"), expectedClientId)) throw new SecurityException("Unexpected ID token audience");

        long exp = payload.optLong("exp", 0);
        if (exp <= nowSeconds - 60) throw new SecurityException("ID token expired");
        long nbf = payload.optLong("nbf", 0);
        if (nbf > 0 && nbf > nowSeconds + 60) throw new SecurityException("ID token not active yet");
        long iat = payload.optLong("iat", 0);
        if (iat > 0 && iat > nowSeconds + 300) throw new SecurityException("ID token issued in the future");

        if (expectedNonce == null || expectedNonce.isEmpty()) throw new SecurityException("Expected nonce missing");
        if (!expectedNonce.equals(payload.optString("nonce", ""))) throw new SecurityException("ID token nonce mismatch");

        String subject = payload.optString("sub", "");
        if (subject.isEmpty()) throw new SecurityException("ID token subject missing");

        return new Claims(
                subject,
                payload.optString("email", ""),
                payload.optString("name", ""),
                exp
        );
    }

    private JSONObject jsonPart(String part) throws Exception {
        return new JSONObject(new String(decodeUrl(part), StandardCharsets.UTF_8));
    }

    private byte[] decodeUrl(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private JSONObject findKey(JSONObject jwks, String kid) throws Exception {
        JSONArray keys = jwks.optJSONArray("keys");
        if (keys == null) throw new SecurityException("JWKS has no keys");
        for (int i = 0; i < keys.length(); i++) {
            JSONObject key = keys.optJSONObject(i);
            if (key != null && kid.equals(key.optString("kid")) &&
                    "RSA".equals(key.optString("kty")) &&
                    "RS256".equals(key.optString("alg"))) {
                return key;
            }
        }
        throw new SecurityException("No matching OpenAI signing key");
    }

    private PublicKey rsaKey(JSONObject jwk) throws Exception {
        BigInteger modulus = new BigInteger(1, decodeUrl(jwk.getString("n")));
        BigInteger exponent = new BigInteger(1, decodeUrl(jwk.getString("e")));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private boolean audienceContains(Object aud, String expected) {
        if (expected == null || expected.isEmpty() || aud == null) return false;
        if (aud instanceof String) return expected.equals(aud);
        if (aud instanceof JSONArray) {
            JSONArray array = (JSONArray) aud;
            for (int i = 0; i < array.length(); i++) {
                if (expected.equals(array.optString(i))) return true;
            }
        }
        return false;
    }

    public static final class Claims {
        public final String subject;
        public final String email;
        public final String name;
        public final long expiresAtSeconds;

        Claims(String subject, String email, String name, long expiresAtSeconds) {
            this.subject = subject;
            this.email = email;
            this.name = name;
            this.expiresAtSeconds = expiresAtSeconds;
        }
    }
}
