package org.example.Model;

/**
 * Legacy placeholder. It no longer extends the {@code UserInfo} entity (that was a mass-assignment shape) and is not
 * used by the signup flow, which takes {@code org.example.Request.SignupRequest}.
 *
 * <p>It only exists because the legacy Kafka classes ({@code UserInfoProducer}, {@code UserInfoSerializer}) still
 * import it. Delete it together with those classes when the outbox replaces them.
 */
public final class UserInfoDto {
    private UserInfoDto() {
    }
}
