-- Composite indexes for DashboardService work-order status aggregations and
-- pending reservation expiry compensation scans.
create index idx_work_order_category_status_priority
    on fault_work_orders(category, status, priority);

create index idx_work_order_status_priority
    on fault_work_orders(status, priority);

create index idx_reservation_status_expires_at
    on equipment_reservations(status, expires_at);
