import { useState, useRef, useEffect } from 'react';

interface MultiSelectFilterProps {
  label: string;
  options: string[];
  value: string[];
  onChange: (values: string[]) => void;
  placeholder?: string;
}

export default function MultiSelectFilter({ label, options, value, onChange, placeholder }: MultiSelectFilterProps) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const toggle = (opt: string) => {
    if (value.includes(opt)) {
      onChange(value.filter(v => v !== opt));
    } else {
      onChange([...value, opt]);
    }
  };

  const clear = () => { onChange([]); setOpen(false); };

  const displayText = value.length === 0
    ? (placeholder || `全部${label}`)
    : value.length === options.length
      ? `全部${label}`
      : `已选 ${value.length} 项`;

  return (
    <div className="relative" ref={ref}>
      <label className="block text-xs font-medium text-gray-500 mb-1">{label}</label>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="w-full h-10 px-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-left flex items-center justify-between focus:border-gray-400 outline-none"
      >
        <span className={value.length === 0 ? 'text-gray-400' : 'text-gray-900'}>{displayText}</span>
        <svg className="w-4 h-4 text-gray-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={open ? 'M5 15l7-7 7 7' : 'M19 9l-7 7-7-7'} />
        </svg>
      </button>
      {open && (
        <div className="absolute z-[110] mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg max-h-48 overflow-y-auto">
          {options.length > 0 && (
            <div className="px-3 py-1.5 flex items-center justify-between border-b border-gray-100">
              <button type="button" onClick={() => onChange([...options])} className="text-[10px] text-purple-600 hover:text-purple-700">全选</button>
              <button type="button" onClick={clear} className="text-[10px] text-gray-400 hover:text-gray-600">清空</button>
            </div>
          )}
          {options.map(opt => (
            <label key={opt} className="flex items-center gap-2 px-3 py-1.5 hover:bg-gray-50 cursor-pointer">
              <input type="checkbox" checked={value.includes(opt)} onChange={() => toggle(opt)}
                className="h-3.5 w-3.5 rounded border-gray-300 accent-purple-600" />
              <span className="text-sm text-gray-700 truncate">{opt}</span>
            </label>
          ))}
          {options.length === 0 && (
            <div className="px-3 py-2 text-xs text-gray-400 text-center">无选项</div>
          )}
        </div>
      )}
    </div>
  );
}
