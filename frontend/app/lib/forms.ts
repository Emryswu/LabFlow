import type { EquipmentFilter, EquipmentForm, ReservationForm, WorkOrderForm } from "./types";

export const emptyEquipmentForm: EquipmentForm = {
  code: "",
  name: "",
  category: "",
  location: "",
  manufacturer: "",
  model: "",
  responsiblePerson: "",
  purchaseDate: "",
  description: "",
};

export const emptyReservationForm: ReservationForm = {
  equipmentId: "",
  purpose: "",
  startTime: "",
  endTime: "",
};

export const emptyWorkorderForm: WorkOrderForm = {
  equipmentId: "",
  title: "",
  description: "",
  priority: "MEDIUM",
};

export const emptyEquipmentFilter: EquipmentFilter = {
  keyword: "",
  status: "",
  category: "",
};

export function parseReservationWindow(
  startTime: string,
  endTime: string,
): { ok: true; startIso: string; endIso: string } | { ok: false; message: string } {
  const start = new Date(startTime);
  const end = new Date(endTime);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return { ok: false, message: "请填写有效的开始和结束时间" };
  }
  if (end <= start) {
    return { ok: false, message: "结束时间需晚于开始时间" };
  }
  return { ok: true, startIso: start.toISOString(), endIso: end.toISOString() };
}
