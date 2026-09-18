CREATE TABLE users (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    email         VARCHAR(200) NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);
