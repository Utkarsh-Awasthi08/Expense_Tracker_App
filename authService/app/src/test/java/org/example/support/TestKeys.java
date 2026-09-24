package org.example.support;

import org.example.Auth.JwtKeyProvider;
import org.example.Auth.JwtProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Base64;

/** Test helpers: keys are always generated in memory or in a temp directory, never read from the repository. */
public final class TestKeys {

    public static final String ISSUER = "expense-tracker-auth";

    private TestKeys() {
    }

    public static KeyPair generate(String algorithm, int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair generateRsa() {
        return generate("RSA", 2048);
    }

    public static String toPkcs8Pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    public static Path writePem(Path dir, String fileName, KeyPair pair) {
        try {
            return Files.writeString(dir.resolve(fileName), toPkcs8Pem(pair), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JwtKeyProvider provider(KeyPair pair) {
        return new JwtKeyProvider((RSAPrivateCrtKey) pair.getPrivate());
    }

    public static JwtProperties properties(Duration accessTtl) {
        return new JwtProperties(ISSUER, "", accessTtl, Duration.ofDays(30));
    }
}
