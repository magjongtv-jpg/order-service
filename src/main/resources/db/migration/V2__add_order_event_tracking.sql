ALTER TABLE orders ADD COLUMN created_event_id UUID;
UPDATE orders SET created_event_id = gen_random_uuid() WHERE created_event_id IS NULL;
ALTER TABLE orders ALTER COLUMN created_event_id SET NOT NULL;

ALTER TABLE orders ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
-- Orders that existed before event tracking are not republished by the retry job.
UPDATE orders SET published_at = created_at;
ALTER TABLE orders ADD COLUMN publish_failure_reason VARCHAR(100);

CREATE INDEX idx_orders_unpublished ON orders (created_at) WHERE published_at IS NULL;

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
