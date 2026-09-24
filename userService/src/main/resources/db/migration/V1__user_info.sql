-- Final shape of the user profile table (Stage 2). user_id is the auth service's user UUID as text.
CREATE TABLE user_info (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          VARCHAR(36)  NOT NULL,
    first_name       VARCHAR(100) NULL,
    last_name        VARCHAR(100) NULL,
    phone_number     VARCHAR(20)  NULL,
    email            VARCHAR(254) NULL,
    profile_picture  VARCHAR(512) NULL,
    default_currency VARCHAR(3)   NOT NULL DEFAULT 'INR',
    timezone         VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_info_user_id UNIQUE (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
