-- The resolved series filters by resolved_at, independently of creation time.
create index idx_work_order_resolved_at on fault_work_orders(resolved_at);
