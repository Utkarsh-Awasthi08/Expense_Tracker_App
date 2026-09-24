CREATE TABLE merchant_alias (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(36)   NOT NULL,
    original_name   VARCHAR(255)  NOT NULL,
    alias_name      VARCHAR(255)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_merchant_alias_user_original (user_id, original_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
