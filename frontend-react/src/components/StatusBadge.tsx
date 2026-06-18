interface StatusBadgeProps {
  status: 'online' | 'offline' | 'warning' | 'active' | 'archived' | string;
  label?: string;
}

const statusConfig: Record<string, { dotColor: string; textColor: string; label: string }> = {
  online: { dotColor: 'bg-green-500', textColor: 'text-green-600', label: '在线' },
  offline: { dotColor: 'bg-gray-400', textColor: 'text-gray-500', label: '离线' },
  warning: { dotColor: 'bg-yellow-500', textColor: 'text-yellow-600', label: '警告' },
  active: { dotColor: 'bg-green-500', textColor: 'text-gray-500', label: 'ACTIVE' },
  archived: { dotColor: 'bg-gray-400', textColor: 'text-gray-500', label: 'ARCHIVED' },
};

export default function StatusBadge({ status, label }: StatusBadgeProps) {
  const config = statusConfig[status] || { dotColor: 'bg-gray-400', textColor: 'text-gray-500', label: status };
  const displayLabel = label || config.label;

  return (
    <div className="flex items-center gap-2">
      <span className="relative flex h-2 w-2">
        <span className={`absolute inline-flex h-full w-full animate-ping rounded-full opacity-20 ${config.dotColor}`}></span>
        <span className={`relative inline-flex h-2 w-2 rounded-full ${config.dotColor}`}></span>
      </span>
      <span className={`text-sm ${config.textColor}`}>{displayLabel}</span>
    </div>
  );
}
