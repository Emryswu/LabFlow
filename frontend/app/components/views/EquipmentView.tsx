import { useState } from "react";
import { isReservableStatus } from "../../lib/labels";
import type { Equipment, EquipmentFilter, PageData } from "../../lib/types";
import { categoryIcon, Icon, KNOWN_CATEGORIES } from "../icons";
import { CategoryTag, menuEntries, MoreMenu, StateBadge } from "../table";
import { Empty, Modal } from "../ui";

/** Derived from isReservableStatus so the table, the detail view and the booking modal never disagree. */
const reserveBlockedReason = (status: string) => isReservableStatus(status) ? undefined
  : status === "RETIRED" ? "设备已退役，不能预约" : "设备维护中，暂不能预约";

export function EquipmentView({ equipment, categories, filter, canManage, canReserve, onFilterChange, onLoad, onReset, onCreate, onReserve, onReport, onRetire, onRestore }: {
  equipment: PageData<Equipment> | null;
  /** Every category in use (GET /api/equipment/categories); null while loading or if that request failed. */
  categories: string[] | null;
  filter: EquipmentFilter;
  canManage: boolean;
  /** Technicians repair equipment; they cannot book it (POST /api/reservations rejects them). */
  canReserve: boolean;
  onFilterChange: (filter: EquipmentFilter) => void;
  onLoad: () => void;
  onReset: () => void;
  onCreate: () => void;
  onReserve: (item: Equipment) => void;
  onReport: (item: Equipment) => void;
  onRetire?: (item: Equipment) => void;
  onRestore?: (item: Equipment) => void;
}) {
  const [detail, setDetail] = useState<Equipment | null>(null);
  const [menuFor, setMenuFor] = useState<number | null>(null);
  const items = equipment?.content ?? [];
  // Options come from their own request, never from the filtered, paged rows on screen;
  // the chosen value stays listed so the select can always show it.
  const categoryOptions = [...new Set([...(categories ?? KNOWN_CATEGORIES), filter.category].filter(Boolean))];

  return <div className="view-stack">
    <section className="filter-bar" aria-label="设备筛选">
      <label className="filter-field">状态
        <select data-equipment-filter="status" value={filter.status} onChange={(e) => onFilterChange({ ...filter, status: e.target.value })}>
          <option value="">全部状态</option>
          <option value="AVAILABLE">可预约</option>
          <option value="IN_USE">使用中</option>
          <option value="MAINTENANCE">维护中</option>
          <option value="RETIRED">已退役</option>
        </select>
      </label>
      <label className="filter-field">设备类别
        <select data-equipment-filter="category" value={filter.category} onChange={(e) => onFilterChange({ ...filter, category: e.target.value })}>
          <option value="">全部类别</option>
          {categoryOptions.map((category) => <option key={category} value={category}>{category}</option>)}
        </select>
      </label>
      <div className="search">
        <span><Icon name="search" size={17} /></span>
        <input data-equipment-filter="keyword" placeholder="搜索设备名称、编号或位置" value={filter.keyword} onChange={(e) => onFilterChange({ ...filter, keyword: e.target.value })} onKeyDown={(e) => e.key === "Enter" && onLoad()} />
      </div>
      <button type="button" className="solid-button" data-action="search" onClick={onLoad}>搜索</button>
      <button type="button" className="ghost-button" data-action="reset" onClick={onReset}>重置</button>
    </section>

    <section className="panel table-panel equipment-panel">
      <header>
        <h3><Icon name="microscope" size={20} />设备列表<small>共 {equipment?.totalElements ?? 0} 台</small></h3>
        {canManage && <button type="button" className="primary" data-action="create" onClick={onCreate}><Icon name="plus" size={16} />新增设备</button>}
      </header>
      {items.length > 0 && <div className="table-scroll"><table className="equipment-table">
        <thead><tr><th>设备名称</th><th>设备编号</th><th>类别</th><th>所在位置</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{items.map((item) => {
          const blocked = reserveBlockedReason(item.status);
          const menu = menuEntries(
            item.status !== "RETIRED" && { key: "report", label: "故障报修", onClick: () => onReport(item) },
            canManage && item.status === "AVAILABLE" && onRetire && { key: "retire", label: "退役", danger: true, onClick: () => onRetire(item) },
            canManage && item.status === "RETIRED" && onRestore && { key: "restore", label: "恢复可用", onClick: () => onRestore(item) },
          );
          return <tr key={item.id} data-equipment-id={item.id}>
            <td><div className="equipment-name">
              <span className="equip-thumb" title={item.category}><Icon name={categoryIcon(item.category)} size={22} /></span>
              <div><b>{item.name}</b><small>{[item.manufacturer, item.model].filter(Boolean).join(" · ") || "厂商型号待补充"}</small></div>
            </div></td>
            <td className="mono">{item.code}</td>
            <td><CategoryTag category={item.category} /></td>
            <td>{item.location}</td>
            <td>{item.responsiblePerson || <span className="muted">待分配</span>}</td>
            <td><StateBadge status={item.status} /></td>
            <td><div className="equipment-actions">
              <button type="button" className="ghost-button" data-action="view" onClick={() => setDetail(item)}>查看</button>
              {canReserve && <button type="button" className="solid-button" data-action="reserve" disabled={Boolean(blocked)} title={blocked} onClick={() => onReserve(item)}>预约</button>}
              {menu.length > 0 && <MoreMenu id={item.id} open={menuFor === item.id} label={`${item.name} 更多操作`} entries={menu}
                onToggle={(open) => setMenuFor(open ? item.id : null)} />}
            </div></td>
          </tr>;
        })}</tbody>
      </table></div>}
      {!items.length && <Empty text="没有符合条件的设备" />}
      {items.length > 0 && (equipment?.totalElements ?? 0) > items.length
        && <p className="table-note">当前显示最近添加的 {items.length} 台，可用筛选条件缩小范围</p>}
    </section>

    {detail && <EquipmentDetail item={detail} canReserve={canReserve} onClose={() => setDetail(null)}
      onReserve={() => { setDetail(null); onReserve(detail); }}
      onReport={() => { setDetail(null); onReport(detail); }} />}
  </div>;
}

function EquipmentDetail({ item, canReserve, onClose, onReserve, onReport }: {
  item: Equipment; canReserve: boolean; onClose: () => void; onReserve: () => void; onReport: () => void;
}) {
  const blocked = reserveBlockedReason(item.status);
  return <Modal title={item.name} subtitle={`${item.code} · ${item.location}`} onClose={onClose}>
    <div className="equipment-detail">
      <div className="equipment-detail-head">
        <span className="equip-thumb"><Icon name={categoryIcon(item.category)} size={26} /></span>
        <CategoryTag category={item.category} />
        <StateBadge status={item.status} />
      </div>
      <p>{item.description || "暂无设备用途说明"}</p>
      <dl>
        <div><dt>制造厂商</dt><dd>{item.manufacturer || "待补充"}</dd></div>
        <div><dt>设备型号</dt><dd>{item.model || "待补充"}</dd></div>
        <div><dt>负责人</dt><dd>{item.responsiblePerson || "待分配"}</dd></div>
        <div><dt>购置日期</dt><dd>{item.purchaseDate || "待补充"}</dd></div>
      </dl>
      <footer>
        {item.status !== "RETIRED" && <button type="button" className="secondary" onClick={onReport}>故障报修</button>}
        {canReserve && isReservableStatus(item.status) && <button type="button" className="primary" onClick={onReserve}>预约这台设备</button>}
        {canReserve && blocked && <small className="muted">{blocked}</small>}
      </footer>
    </div>
  </Modal>;
}
