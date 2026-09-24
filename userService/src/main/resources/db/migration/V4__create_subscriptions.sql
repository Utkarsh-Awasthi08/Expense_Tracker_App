CREATE TABLE subscriptions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(36)   NOT NULL,
    platform        VARCHAR(255)  NOT NULL,
    amount          DECIMAL(19,2) NOT NULL,
    currency        VARCHAR(3)    NOT NULL DEFAULT 'INR',
    billing_day     INT           NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
