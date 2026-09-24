-- Identity schema for authService.

CREATE TABLE users (
    user_id    VARCHAR(36)  NOT NULL,
    username   VARCHAR(100) NOT NULL,
    password   VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT uk_users_username UNIQUE (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE roles (
    role_id   BIGINT      NOT NULL AUTO_INCREMENT,
    role_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id),
    CONSTRAINT uk_roles_role_name UNIQUE (role_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO roles (role_name) VALUES ('ROLE_USER'), ('ROLE_ADMIN');

CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL,
    role_id BIGINT      NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Refresh tokens are opaque; only the SHA-256 hex digest of the token is stored.
-- A rotated token keeps its row with revoked_at set and replaced_by pointing at its successor.
CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    token_hash VARCHAR(64) NOT NULL,
    user_id    VARCHAR(36) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    replaced_by BIGINT     NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
