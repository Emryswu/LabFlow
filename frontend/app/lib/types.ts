export type Role = "STUDENT" | "TEACHER" | "TECHNICIAN" | "ADMIN";
export type View = "dashboard" | "equipment" | "reservations" | "workorders" | "audit";
export type ModalKind = "equipment" | "reservation" | "workorder";

export type UserProfile = { id: number; username: string; displayName: string; role: Role };
export type Equipment = { id: number; code: string; name: string; category: string; location: string; manufacturer: string | null; model: string | null; responsiblePerson: string | null; purchaseDate: string | null; description: string | null; status: string };
/** A slot-holding reservation that a pending request overlaps; requesterName is null for students looking at others' bookings. */
export type ReservationConflict = { id: number; startTime: string; endTime: string; status: string; requesterName: string | null };
export type Reservation = { id: number; equipmentId: number; requesterId: number; requesterName: string; purpose: string; startTime: string; endTime: string; status: string; createdAt: string; expiresAt: string;
  /** Only on PENDING rows from the list endpoint: non-empty means approving now would be rejected. */
  conflictsWith?: ReservationConflict[] };
export type WorkOrder = { id: number; equipmentId: number; reporterId: number; reporterName: string; title: string; description: string; priority: string; status: string; assigneeId: number | null; createdAt: string; updatedAt: string; resolvedAt: string | null };
export type AuditLog = { id: number; actorUsername: string; actorRole: string; action: string; targetType: string; targetId: number | null; details: string; createdAt: string };
export type Stats = { usersTotal: number; equipmentTotal: number; equipmentAvailable: number; equipmentMaintenance: number; equipmentInUse: number; equipmentRetired: number; reservationsPending: number; reservationsApproved: number; reservationsToday: number; workOrdersOpen: number; workOrdersUrgent: number; paymentDiscrepanciesOpen: number };
/** Daily counts from /api/dashboard/trends, oldest first; the last day is today. */
export type Trends = {
  days: string[];
  reservationsCreated: number[];
  reservationsScheduled: number[];
  workOrdersCreated: number[];
  workOrdersResolved: number[];
  /** Admins only; null for everyone else. */
  paymentDiscrepanciesCreated: number[] | null;
};
export type PageData<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
export type Technician = { id: number; username: string; displayName: string };

export type EquipmentForm = { code: string; name: string; category: string; location: string; manufacturer: string; model: string; responsiblePerson: string; purchaseDate: string; description: string };
export type ReservationForm = { equipmentId: string; purpose: string; startTime: string; endTime: string };
export type WorkOrderForm = { equipmentId: string; title: string; description: string; priority: string };
export type ToastState = { message: string; error?: boolean } | null;
