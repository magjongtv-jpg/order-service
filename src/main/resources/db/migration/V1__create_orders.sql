CREATE TABLE orders (
                        id          UUID PRIMARY KEY,
                        status      VARCHAR(20) NOT NULL,
                        created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE order_items (
                             id        BIGSERIAL PRIMARY KEY,
                             order_id  UUID NOT NULL REFERENCES orders (id),
                             sku       VARCHAR(64) NOT NULL,
                             quantity  INTEGER NOT NULL CHECK (quantity >= 1)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);