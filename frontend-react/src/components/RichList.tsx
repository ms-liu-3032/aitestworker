import type { ReactNode } from 'react';

interface RichListProps<T> {
  items: T[];
  columns: { key: string; label: string; width?: string; renderHeader?: () => ReactNode }[];
  renderRow: (item: T, index: number) => ReactNode;
  loading?: boolean;
  emptyText?: string;
}

function getMinWidth(columns: { key: string; label: string; width?: string }[]) {
  return columns.reduce((sum, col) => {
    const width = col.width ? Number.parseInt(col.width, 10) : 180;
    return sum + (Number.isFinite(width) ? width : 180);
  }, 0);
}

function SkeletonRow({ columns, minWidth }: { columns: { key: string; label: string; width?: string; renderHeader?: () => ReactNode }[]; minWidth: number }) {
  return (
    <div className="flex items-center px-5 py-3.5 border-b border-gray-100" style={{ minWidth }}>
      {columns.map((col, i) => (
        <div
          key={col.key}
          className="animate-pulse shrink-0 bg-gray-100 rounded h-4"
          style={{ width: col.width || '100px', marginRight: i < columns.length - 1 ? '16px' : 0 }}
        />
      ))}
    </div>
  );
}

export default function RichList<T>({ items, columns, renderRow, loading, emptyText }: RichListProps<T>) {
  const minWidth = getMinWidth(columns);

  return (
    <div className="bg-white rounded-xl border border-gray-200 overflow-x-auto overflow-y-hidden">
      {/* Header */}
      <div className="flex items-center px-5 py-2.5 border-b border-gray-100 bg-gray-50/50" style={{ minWidth }}>
        {columns.map(col => (
          <div
            key={col.key}
            className={col.renderHeader ? 'shrink-0 flex items-center justify-center' : 'shrink-0 truncate text-[10px] uppercase tracking-wider text-gray-400 font-semibold'}
            style={{ width: col.width, flex: col.width ? undefined : 1 }}
          >
            {col.renderHeader ? col.renderHeader() : col.label}
          </div>
        ))}
      </div>

      {/* Rows */}
      {loading ? (
        <>
          <SkeletonRow columns={columns} minWidth={minWidth} />
          <SkeletonRow columns={columns} minWidth={minWidth} />
          <SkeletonRow columns={columns} minWidth={minWidth} />
          <SkeletonRow columns={columns} minWidth={minWidth} />
          <SkeletonRow columns={columns} minWidth={minWidth} />
        </>
      ) : items.length === 0 ? (
        <div className="px-5 py-12 text-center">
          <div className="text-gray-400 text-sm">{emptyText || '暂无数据'}</div>
        </div>
      ) : (
        items.map((item, index) => (
          <div
            key={index}
            className="group flex items-center px-5 py-3 border-b border-gray-100 bg-white hover:bg-gray-50/80 transition-colors duration-150 last:border-b-0"
            style={{ minWidth }}
          >
            {renderRow(item, index)}
          </div>
        ))
      )}
    </div>
  );
}
