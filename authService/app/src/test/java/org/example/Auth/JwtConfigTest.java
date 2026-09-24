package org.example.Auth;

import org.example.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Startup behaviour of the JWT wiring: it must refuse to start without a usable RSA key. */
class JwtConfigTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(JwtConfig.class);
    }

    @Test
    void startsWithAValidKeyAndBindsTheTtlsAsIsoDurations() {
        Path pem = TestKeys.writePem(dir, "key.pem", TestKeys.generateRsa());

        runner().withPropertyValues("jwt.issuer=expense-tracker-auth", "jwt.private-key-path=" + pem,
                        "jwt.access-ttl=PT15M", "jwt.refresh-ttl=P30D")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtKeyProvider.class).hasSingleBean(JwtDecoder.class);
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(properties.refreshTtl()).isEqualTo(Duration.ofDays(30));
                    assertThat(properties.issuer()).isEqualTo("expense-tracker-auth");
                });
    }

    @Test
    void refusesToStartWhenThePrivateKeyPathIsNotSet() {
        runner().withPropertyValues("jwt.issuer=x", "jwt.private-key-path=", "jwt.access-ttl=PT15M", "jwt.refresh-ttl=P30D")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("JWT_PRIVATE_KEY_PATH"));
    }

    @Test
    void refusesToStartWhenTheKeyFileIsMissing() {
        runner().withPropertyValues("jwt.issuer=x", "jwt.private-key-path=" + dir.resolve("nope.pem"),
                        "jwt.access-ttl=PT15M", "jwt.refresh-ttl=P30D")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("Cannot read the JWT private key file"));
    }

    @Test
    void refusesToStartWhenTheKeyIsNotRsa() {
        Path pem = TestKeys.writePem(dir, "ec.pem", TestKeys.generate("EC", 256));

        runner().withPropertyValues("jwt.issuer=x", "jwt.private-key-path=" + pem, "jwt.access-ttl=PT15M",
                        "jwt.refresh-ttl=P30D")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("not a valid RSA key"));
    }
}
