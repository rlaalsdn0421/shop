CREATE TABLE products (
    id          VARCHAR(36)   NOT NULL PRIMARY KEY,
    name        VARCHAR(200)  NOT NULL,
    description VARCHAR(2000) NOT NULL,
    price       INTEGER       NOT NULL CHECK (price >= 0),
    image_url   VARCHAR(2000) NOT NULL,
    stock       INTEGER       NOT NULL CHECK (stock >= 0),
    created_at  TIMESTAMP     NOT NULL
);

CREATE TABLE orders (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    customer_name    VARCHAR(200) NOT NULL,
    customer_phone   VARCHAR(50)  NOT NULL,
    customer_address VARCHAR(500) NOT NULL,
    total_amount     INTEGER      NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL
);

CREATE TABLE order_items (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id   VARCHAR(36) NOT NULL REFERENCES orders(id),
    product_id VARCHAR(36) NOT NULL REFERENCES products(id),
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    price      INTEGER     NOT NULL
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

CREATE TABLE reviews (
    id             VARCHAR(36)   NOT NULL PRIMARY KEY,
    product_id     VARCHAR(36)   NOT NULL REFERENCES products(id),
    reviewer_name  VARCHAR(100)  NOT NULL,
    rating         INTEGER       NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment        VARCHAR(1000) NOT NULL,
    created_at     TIMESTAMP     NOT NULL
);

CREATE INDEX idx_reviews_product_id ON reviews(product_id);
