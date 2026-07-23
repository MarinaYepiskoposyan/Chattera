CREATE TABLE profiles (
    user_id       VARCHAR(255) PRIMARY KEY,
    display_name  VARCHAR(255),
    avatar_url    VARCHAR(1024),
    timezone      VARCHAR(64),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
