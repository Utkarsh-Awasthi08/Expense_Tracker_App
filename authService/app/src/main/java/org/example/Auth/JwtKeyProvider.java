package org.example.Auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * Holds the RSA signing key. The key is loaded from a PKCS#8 PEM file; the public key is derived from the
 * private CRT key (no second key file) and the key id is the RFC 7638 JWK thumbprint (base64url SHA-256).
 */
public final class JwtKeyProvider {

    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    private static final int MIN_MODULUS_BITS = 2048;

    private final RSAPublicKey publicKey;
    private final String kid;
    private final RSAKey signingJwk;

    public JwtKeyProvider(RSAPrivateCrtKey privateKey) {
        if (privateKey.getModulus().bitLength() < MIN_MODULUS_BITS) {
            throw new IllegalStateException("JWT signing key is too small: RSA keys must be at least "
                    + MIN_MODULUS_BITS + " bits");
        }
        try {
            this.publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            this.kid = new RSAKey.Builder(publicKey).build().computeThumbprint().toString();
            this.signingJwk = new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(kid)
                    .build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Could not derive the JWT public key from the private key", e);
        }
    }

    /** Loads the key from {@code path}; fails fast with a clear message if it is missing, unreadable or not RSA. */
    public static JwtKeyProvider load(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("jwt.private-key-path is not set. Set JWT_PRIVATE_KEY_PATH to a "
                    + "PKCS#8 PEM RSA private key file (see scripts/gen-secrets.sh).");
        }
        Path file = Path.of(path.trim());
        String pem;
        try {
            pem = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the JWT private key file '" + file
                    + "' (JWT_PRIVATE_KEY_PATH): " + e.getClass().getSimpleName(), e);
        }
        return fromPem(pem, file.toString());
    }

    static JwtKeyProvider fromPem(String pem, String source) {
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalStateException("The JWT private key '" + source + "' is PKCS#1; convert it to PKCS#8 "
                    + "(openssl pkcs8 -topk8 -nocrypt -in old.pem -out key.pem)");
        }
        int begin = pem.indexOf(PEM_BEGIN);
        int end = pem.indexOf(PEM_END);
        if (begin < 0 || end < begin) {
            throw new IllegalStateException("The JWT private key '" + source
                    + "' is not an unencrypted PKCS#8 PEM (expected a '" + PEM_BEGIN + "' block)");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(pem.substring(begin + PEM_BEGIN.length(), end));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The JWT private key '" + source + "' has invalid base64 content", e);
        }
        PrivateKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JWT private key '" + source + "' is not a valid RSA key "
                    + "(EC and other key types are not supported)", e);
        }
        if (!(key instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException("The JWT private key '" + source
                    + "' is not an RSA CRT key, so the public key cannot be derived from it");
        }
        return new JwtKeyProvider(crtKey);
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String kid() {
        return kid;
    }

    /** An encoder that signs RS256 with this key; the private key never leaves this class. */
    public JwtEncoder newEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingJwk)));
    }

    /** The public JWK (kty, n, e, kid, alg, use); it never carries private members. */
    public RSAKey publicJwk() {
        return signingJwk.toPublicJWK();
    }
}
