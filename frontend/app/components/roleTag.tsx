import { actorRoleLabels } from "../lib/labels";
import { type CategoryTone, Icon, type IconName } from "./icons";

/**
 * One icon and colour per role, shared by the login page's demo cards and the audit
 * log's role tags. A role missing here falls back to grey; tests/login-audit.test.mjs
 * fails if one is missing.
 */
export const ROLE_STYLES: Record<string, { icon: IconName; tone: CategoryTone }> = {
  STUDENT: { icon: "cap", tone: "orange" },
  TEACHER: { icon: "user", tone: "blue" },
  TECHNICIAN: { icon: "wrench", tone: "green" },
  ADMIN: { icon: "gear", tone: "violet" },
  SYSTEM: { icon: "bolt", tone: "graphite" },
};

export function roleStyle(role: string) {
  return ROLE_STYLES[role] ?? { icon: "user" as IconName, tone: "graphite" as CategoryTone };
}

export function RoleTag({ role }: { role: string }) {
  const style = roleStyle(role);
  return <span className={`role-tag tone-${style.tone}`} data-role-tag={role}>
    <Icon name={style.icon} size={14} />{actorRoleLabels[role] ?? role}
  </span>;
}
