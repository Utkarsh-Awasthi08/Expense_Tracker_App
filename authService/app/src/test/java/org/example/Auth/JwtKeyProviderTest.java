package org.example.Auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.example.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyProviderTest {

    @TempDir
    Path dir;

    @Test
    void loadsPkcs8PemAndDerivesPublicKeyFromThePrivateKey() {
        KeyPair pair = TestKeys.generateRsa();
        Path pem = TestKeys.writePem(dir, "key.pem", pair);

        JwtKeyProvider provider = JwtKeyProvider.load(pem.toString());

        RSAPublicKey expected = (RSAPublicKey) pair.getPublic();
        assertThat(provider.publicKey().getModulus()).isEqualTo(expected.getModulus());
        assertThat(provider.publicKey().getPublicExponent()).isEqualTo(expected.getPublicExponent());
    }

    @Test
    void kidIsTheRfc7638ThumbprintInBase64Url() throws Exception {
        KeyPair pair = TestKeys.generateRsa();
        JwtKeyProvider provider = TestKeys.provider(pair);

        String expected = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).build().computeThumbprint().toString();

        assertThat(provider.kid()).isEqualTo(expected);
        // 32 bytes of SHA-256 in unpadded base64url
        assertThat(provider.kid()).matches(Pattern.compile("^[A-Za-z0-9_-]{43}$"));
        assertThat(Base64.getUrlDecoder().decode(provider.kid())).hasSize(32);
    }

    @Test
    void publicJwkCarriesKidAlgAndUseButNoPrivateMembers() {
        JwtKeyProvider provider = TestKeys.provider(TestKeys.generateRsa());

        RSAKey jwk = provider.publicJwk();

        assertThat(jwk.isPrivate()).isFalse();
        assertThat(jwk.getKeyID()).isEqualTo(provider.kid());
        assertThat(jwk.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwk.getKeyUse().identifier()).isEqualTo("sig");
    }

    @Test
    void missingFileFailsFastWithAClearMessage() {
        String missing = dir.resolve("does-not-exist.pem").toString();

        assertThatThrownBy(() -> JwtKeyProvider.load(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read the JWT private key file")
                .hasMessageContaining("does-not-exist.pem");
    }

    @Test
    void blankPathFailsFastNamingTheEnvironmentVariable() {
        assertThatThrownBy(() -> JwtKeyProvider.load(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");
        assertThatThrownBy(() -> JwtKeyProvider.load(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");
    }

    @Test
    void nonRsaKeyIsRejected() throws Exception {
        KeyPair ec = TestKeys.generate("EC", 256);
        Path pem = TestKeys.writePem(dir, "ec.pem", ec);

        assertThatThrownBy(() -> JwtKeyProvider.load(pem.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid RSA key");
    }

    @Test
    void garbageThatIsNotPemIsRejected() throws Exception {
        Path pem = Files.writeString(dir.resolve("junk.pem"), "this is not a key", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> JwtKeyProvider.load(pem.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void pkcs1PemGetsAConversionHint() throws Exception {
        Path pem = Files.writeString(dir.resolve("pkcs1.pem"),
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> JwtKeyProvider.load(pem.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#1")
                .hasMessageContaining("pkcs8");
    }

    @Test
    void corruptDerInsidePemIsRejected() throws Exception {
        Path pem = Files.writeString(dir.resolve("corrupt.pem"),
                "-----BEGIN PRIVATE KEY-----\nAAAAAAAAAAAAAAAA\n-----END PRIVATE KEY-----\n", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> JwtKeyProvider.load(pem.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid RSA key");
    }

    @Test
    void tooSmallRsaKeyIsRejected() {
        KeyPair small = TestKeys.generate("RSA", 1024);

        assertThatThrownBy(() -> TestKeys.provider(small))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 2048");
    }
}
