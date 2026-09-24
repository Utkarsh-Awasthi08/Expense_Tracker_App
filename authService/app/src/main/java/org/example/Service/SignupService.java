package org.example.Service;

/**
 * Previously handled username+password signup; replaced by OTP-based auto-provisioning in
 * {@link org.example.Otp.OtpService#verifyOtp}. A user account is automatically created on the
 * first successful OTP verification for a phone number. This class is retained as a placeholder
 * and is not wired anywhere.
 *
 * @deprecated Replaced by OTP auto-provisioning.
 */
@Deprecated(since = "OTP migration", forRemoval = true)
public class SignupService {
    // No longer used: OTP verify auto-provisions the user account.
}
