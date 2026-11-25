create table price (
  product_code      text          not null,
  currency          text          not null check (currency in ('VND', 'USD')),
  amount            numeric(12, 2) not null check (amount > 0),
  refundable_credits int          null,
  active            boolean       not null default true,
  primary key (product_code, currency)
);

-- Spec 14.1. Codes are the PaymentSucceeded event contract (identity-svc grants by product_code).
-- refundable_credits: credits a refund must be able to take back (null for passes).
insert into price (product_code, currency, amount, refundable_credits) values
  ('CREDIT_1',   'VND',   29000.00, 1),
  ('CREDIT_5',   'VND',  119000.00, 5),
  ('PASS_MONTH', 'VND',   49000.00, null),
  ('PASS_YEAR',  'VND',  399000.00, null),
  ('CREDIT_1',   'USD',       1.19, 1),
  ('CREDIT_5',   'USD',       4.99, 5),
  ('PASS_MONTH', 'USD',       1.99, null),
  ('PASS_YEAR',  'USD',      15.99, null);

create table orders (
  id               uuid           primary key,
  code             text           not null unique,
  account_id       uuid           not null,
  site             text           not null check (site in ('tuvi', 'astro')),
  product_code     text           not null,
  chart_profile_id uuid           null,
  amount           numeric(12, 2) not null,
  currency         text           not null check (currency in ('VND', 'USD')),
  provider         text           not null check (provider in ('SEPAY', 'PAYPAL')),
  status           text           not null
                   check (status in ('PENDING', 'PAID', 'REFUND_REQUESTED', 'REFUNDED')),
  provider_ref     text           null,
  note             text           null,
  created_at       timestamptz    not null,
  expires_at       timestamptz    not null,
  paid_at          timestamptz    null
);

create index orders_account_created_idx on orders (account_id, created_at desc);
