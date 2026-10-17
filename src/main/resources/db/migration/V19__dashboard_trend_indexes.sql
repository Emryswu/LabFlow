-- Dashboard trends read two-week windows by these timestamps. The existing
-- reservation index leads with equipment_id, so it cannot serve a range on
-- start_time alone.
create index idx_reservation_created_at on equipment_reservations(created_at);
create index idx_reservation_start_time on equipment_reservations(start_time);
create index idx_work_order_created_at on fault_work_orders(created_at);
