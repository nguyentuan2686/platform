-- Every payment a provider reported, whatever came of it. The unique key makes a transaction
-- idempotent: webhook retries, webhook plus reconciliation and two replicas yield one row (the
-- insert is "on conflict do nothing", so there is no check-then-insert race).
create table provider_transaction (
  id              bigserial      primary key,
  provider        text           not null check (provider in ('SEPAY', 'PAYPAL')),
  provider_tx_id  text           not null,
  amount          numeric(12, 2) not null,
  currency        text           not null,
  -- Free text typed by the payer: shown to the operator in the unmatched queue, never logged.
  content         text           null,
  reference_code  text           null,
  occurred_at     timestamptz    not null,
  received_via    text           not null check (received_via in ('WEBHOOK', 'RECONCILE')),
  match_status    text           not null check (match_status in ('MATCHED', 'UNMATCHED', 'RESOLVED')),
  unmatched_reason text          null,
  order_id        uuid           null references orders (id),
  resolution      text           null,
  note            text           null,
  created_at      timestamptz    not null default now(),
  unique (provider, provider_tx_id)
);

create index provider_transaction_unmatched_idx
  on provider_transaction (id) where match_status = 'UNMATCHED';
create index provider_transaction_order_idx on provider_transaction (order_id);

-- A customer's request for the money back; at most one per order.
create table refund_request (
  id                 uuid           primary key,
  order_id           uuid           not null unique references orders (id),
  provider           text           not null check (provider in ('SEPAY', 'PAYPAL')),
  amount             numeric(12, 2) not null,
  currency           text           not null,
  status             text           not null check (status in ('OPEN', 'DONE')),
  provider_refund_id text           null,
  note               text           null,
  created_at         timestamptz    not null default now(),
  completed_at       timestamptz    null
);

create index refund_request_status_idx on refund_request (status, created_at);
create index orders_provider_ref_idx on orders (provider_ref) where provider_ref is not null;
