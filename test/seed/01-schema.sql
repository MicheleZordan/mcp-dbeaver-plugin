CREATE TABLE customers (
  id INT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(200) NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_customers_email (email)
);
CREATE TABLE orders (
  id INT AUTO_INCREMENT PRIMARY KEY,
  customer_id INT NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  status ENUM('new','paid','shipped') NOT NULL DEFAULT 'new',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY ix_orders_customer (customer_id),
  KEY ix_orders_status_created (status, created_at),
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);
INSERT INTO customers (name, email) VALUES ('Ada', 'ada@example.com'), ('Grace', 'grace@example.com'), ('Linus', 'linus@example.com');
INSERT INTO orders (customer_id, amount, status) VALUES (1, 10.50, 'paid'), (1, 99.00, 'new'), (2, 5.25, 'shipped');
CREATE VIEW v_customer_totals AS SELECT c.id, c.name, SUM(o.amount) AS total FROM customers c LEFT JOIN orders o ON o.customer_id = c.id GROUP BY c.id, c.name;
