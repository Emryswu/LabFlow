import type { Role } from "./types";

export const roleLabels: Record<Role, string> = {
  STUDENT: "学生", TEACHER: "教师", TECHNICIAN: "维修员", ADMIN: "管理员",
};

export const statusLabels: Record<string, string> = {
  AVAILABLE: "可预约", IN_USE: "使用中", MAINTENANCE: "维护中", RETIRED: "已退役",
  PENDING: "待审批", APPROVED: "已批准", REJECTED: "已拒绝", CANCELLED: "已取消", EXPIRED: "已过期", COMPLETED: "已完成",
  AWAITING_PAYMENT: "待支付", PAID: "已支付", REFUNDING: "退款中",
  SUBMITTED: "待派单", ASSIGNED: "已派单", IN_PROGRESS: "处理中", RESOLVED: "已解决", CLOSED: "已关闭",
  LOW: "低", MEDIUM: "中", HIGH: "高", URGENT: "紧急",
};

export function isReservableStatus(status: string) {
  return status !== "RETIRED" && status !== "MAINTENANCE";
}

export const actionLabels: Record<string, string> = {
  EQUIPMENT_CREATED: "创建设备", EQUIPMENT_UPDATED: "更新设备", EQUIPMENT_RETIRED: "退役设备",
  EQUIPMENT_RESTORED: "恢复设备", RESERVATION_CREATED: "提交预约", RESERVATION_APPROVED: "批准预约",
  RESERVATION_REJECTED: "拒绝预约", RESERVATION_CANCELLED: "取消预约", RESERVATION_COMPLETED: "完成预约",
  RESERVATION_EXPIRED: "预约过期", WORK_ORDER_CREATED: "提交工单", WORK_ORDER_ASSIGNED: "派单",
  WORK_ORDER_CLAIMED: "维修员接单", WORK_ORDER_IN_PROGRESS: "开始维修", WORK_ORDER_RESOLVED: "解决故障",
  WORK_ORDER_CLOSED: "关闭工单", WORK_ORDER_CANCELLED: "取消工单",
  RESERVATION_PAYMENT_EXPIRED: "支付超时关单", PAYMENT_SUCCEEDED: "支付成功", REFUND_SUCCEEDED: "退款成功",
  PAYMENT_ARRIVED_LATE: "迟到支付退款", PAYMENT_CALLBACK_UNSUCCESSFUL: "支付回调未成功",
  PAYMENT_CALLBACK_AMOUNT_REJECTED: "回调金额不符", PAYMENT_DISPATCH_ABANDONED: "支付请求放弃",
  RECONCILIATION_DISCREPANCY: "对账差异", RECONCILIATION_UNKNOWN_ORDER: "对账未知订单",
  RECONCILIATION_UNKNOWN_EQUIPMENT: "对账设备缺失",
};

/** Who an audit entry belongs to: the four user roles, plus SYSTEM for timers and payment callbacks. */
export const actorRoleLabels: Record<string, string> = { ...roleLabels, SYSTEM: "系统" };

export const targetTypeLabels: Record<string, string> = {
  EQUIPMENT: "设备", RESERVATION: "预约", WORK_ORDER: "工单", PAYMENT: "支付",
};

export type ActionDomain = "equipment" | "reservation" | "workorder" | "payment";

/** Audit actions grouped by the business area they belong to; drives the filter and the row icon. */
export function actionDomain(action: string): ActionDomain {
  if (action.startsWith("EQUIPMENT_")) return "equipment";
  if (action.startsWith("WORK_ORDER_")) return "workorder";
  if (action.startsWith("RESERVATION_") && action !== "RESERVATION_PAYMENT_EXPIRED") return "reservation";
  return "payment";
}

export const actionDomainLabels: Record<ActionDomain, string> = {
  equipment: "设备", reservation: "预约", workorder: "工单", payment: "支付与对账",
};

/** Backend details embed status codes ("工单从 ASSIGNED 流转到 IN_PROGRESS"); show them as labels. */
export function readableDetails(details: string) {
  return details.replace(/\b[A-Z][A-Z_]+\b/g, (code) => statusLabels[code] ?? code);
}

export const formatDate = (value?: string | null) => value
  ? new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value))
  : "—";

export function formatDateRange(start: string, end: string, dayLabel = (date: Date) => `${date.getMonth() + 1}月${date.getDate()}日`) {
  const from = new Date(start);
  const to = new Date(end);
  const clock = (date: Date) => `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  const sameDay = from.getFullYear() === to.getFullYear()
    && from.getMonth() === to.getMonth()
    && from.getDate() === to.getDate();
  return `${dayLabel(from)} ${clock(from)}–${sameDay ? "" : `${dayLabel(to)} `}${clock(to)}`;
}

