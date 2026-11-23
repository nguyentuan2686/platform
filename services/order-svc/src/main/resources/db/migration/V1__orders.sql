create table orders (
  id          uuid primary key,
  customer_id text           not null,
  status      text           not null,
  total       numeric(19, 2) not null,
  created_at  timestamptz    not null
);

create table order_lines (
  order_id   uuid           not null references orders (id),
  line_no    int            not null,
  sku        text           not null,
  quantity   int            not null check (quantity > 0),
  unit_price numeric(19, 2) not null check (unit_price >= 0),
  primary key (order_id, line_no)
);
