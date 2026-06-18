import { useState, useEffect, useRef } from 'react';
import { useApp } from '../context/AppContext';
import type { CommandItem } from '../types';

interface CommandPaletteProps {
  commands: CommandItem[];
}

export default function CommandPalette({ commands }: CommandPaletteProps) {
  const { commandOpen, setCommandOpen } = useApp();
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const filtered = commands.filter(c =>
    c.label.toLowerCase().includes(query.toLowerCase())
  );

  const grouped = filtered.reduce<Record<string, CommandItem[]>>((acc, cmd) => {
    if (!acc[cmd.group]) acc[cmd.group] = [];
    acc[cmd.group].push(cmd);
    return acc;
  }, {});

  const flatItems = Object.values(grouped).flat();

  useEffect(() => {
    if (commandOpen) {
      setQuery('');
      setSelectedIndex(0);
      setTimeout(() => inputRef.current?.focus(), 50);
    }
  }, [commandOpen]);

  useEffect(() => {
    if (!commandOpen) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setCommandOpen(false);
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setSelectedIndex(i => Math.min(i + 1, flatItems.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setSelectedIndex(i => Math.max(i - 1, 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        const item = flatItems[selectedIndex];
        if (item) {
          item.action();
          setCommandOpen(false);
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [commandOpen, flatItems, selectedIndex, setCommandOpen]);

  if (!commandOpen) return null;

  return (
    <div
      className="fixed inset-0 z-[100] animate-fade-in"
      onClick={() => setCommandOpen(false)}
    >
      <div className="absolute inset-0 bg-black/20 backdrop-blur-sm" />
      <div className="relative mx-4 mt-20 w-auto max-w-2xl overflow-hidden rounded-xl bg-white shadow-lg sm:mx-auto sm:mt-32 sm:w-full">
        <input
          ref={inputRef}
          type="text"
          value={query}
          onChange={e => { setQuery(e.target.value); setSelectedIndex(0); }}
          placeholder="搜索项目、用例或输入命令..."
          className="w-full border-none outline-none text-lg px-4 py-3 text-gray-900 placeholder:text-gray-400"
          onClick={e => e.stopPropagation()}
        />
        <div className="max-h-[400px] overflow-y-auto border-t border-gray-200">
          {flatItems.length === 0 ? (
            <div className="px-4 py-8 text-center text-gray-400 text-sm">
              未找到匹配结果
            </div>
          ) : (
            (() => {
              let globalIdx = 0;
              return Object.entries(grouped).map(([group, items]) => (
                <div key={group}>
                  <div className="px-4 py-1.5 text-[11px] uppercase tracking-wider text-gray-400 font-semibold bg-gray-50">
                    {group}
                  </div>
                  {items.map(item => {
                    const idx = globalIdx++;
                    const isSelected = idx === selectedIndex;
                    return (
                      <button
                        key={item.id}
                        onClick={() => { item.action(); setCommandOpen(false); }}
                        onMouseEnter={() => setSelectedIndex(idx)}
                        className={`
                          flex w-full items-start justify-between gap-3 px-4 py-2.5 text-left text-sm transition-colors
                          ${isSelected ? 'bg-gray-100' : 'hover:bg-gray-50'}
                        `}
                      >
                        <span className="min-w-0 break-words text-gray-900">{item.label}</span>
                        {item.shortcut && (
                          <span className="shrink-0 font-mono text-[11px] text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded">
                            {item.shortcut}
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              ));
            })()
          )}
        </div>
      </div>
    </div>
  );
}
