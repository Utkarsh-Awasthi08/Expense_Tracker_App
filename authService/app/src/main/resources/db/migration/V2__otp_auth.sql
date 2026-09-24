-- Replaces password auth with phone-number + OTP. Local dev data is disposable: a pre-existing local DB with old
-- rows in `users` will make the ADD COLUMN ... NOT NULL below fail (strict SQL mode rejects backfilling a NOT NULL
-- column with no default on a non-empty table); wipe the local `authservice` database before re-running Flyway.

ALTER TABLE users
    DROP COLUMN username,
    DROP COLUMN password,
    ADD COLUMN phone_number VARCHAR(20) NOT NULL,
    ADD UNIQUE KEY uq_users_phone (phone_number);

-- OTP challenges: one row per code sent. `attempts_remaining` is decremented with an atomic conditional UPDATE
-- (never read-then-write) so that two concurrent wrong guesses cannot both consume the last attempt. A challenge is
-- "active" while consumed_at IS NULL and expires_at is in the future; creating a new challenge for a phone marks any
-- prior active challenge for that phone consumed, so only the latest code is ever valid.
CREATE TABLE otp_challenges (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    phone_number       VARCHAR(20) NOT NULL,
    code_hash          VARCHAR(64) NOT NULL,
    expires_at         DATETIME(6) NOT NULL,
    attempts_remaining INT         NOT NULL DEFAULT 5,
    consumed_at        DATETIME(6) NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_otp_phone_created (phone_number, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
