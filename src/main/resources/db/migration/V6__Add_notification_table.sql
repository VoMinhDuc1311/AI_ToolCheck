-- V6: Add notification table for persistent user notifications
-- Follows existing project conventions:
--   - UUID stored as BINARY(16) consistent with JPA GenerationType.UUID
--   - created_at / updated_at managed by entity lifecycle hooks
--   - Nullable FK to source_project (global user notifications have no project)

CREATE TABLE notification (
    id                  BINARY(16)   NOT NULL,
    recipient_user_id   BINARY(16)   NOT NULL,
    project_id          BINARY(16)   NULL,
    type                VARCHAR(80)  NOT NULL,
    severity            VARCHAR(20)  NOT NULL,
    title               VARCHAR(255) NOT NULL,
    message             VARCHAR(1000) NOT NULL,
    action_url          VARCHAR(500) NULL,
    metadata_json       JSON         NULL,
    read_flag           BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at             DATETIME     NULL,
    created_at          DATETIME     NOT NULL,
    updated_at          DATETIME     NOT NULL,
    CONSTRAINT pk_notification PRIMARY KEY (id),
    CONSTRAINT fk_notification_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES app_user(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_notification_project
        FOREIGN KEY (project_id) REFERENCES source_project(id)
        ON DELETE SET NULL
);

-- Index for fetching a user's notifications ordered by recency (primary list query)
CREATE INDEX idx_notification_recipient_created_at
    ON notification (recipient_user_id, created_at DESC);

-- Index for fast unread-count query
CREATE INDEX idx_notification_recipient_read
    ON notification (recipient_user_id, read_flag);

-- Index for project-scoped queries (e.g., cleanup on project delete)
CREATE INDEX idx_notification_project
    ON notification (project_id);
