import { useApp } from '../context/AppContext';

export default function ToastContainer() {
  const { toasts, removeToast } = useApp();

  if (toasts.length === 0) return null;

  return (
    <div className="fixed top-16 left-1/2 -translate-x-1/2 z-[110] flex flex-col gap-2">
      {toasts.map(toast => (
        <div
          key={toast.id}
          onClick={() => removeToast(toast.id)}
          className={`
            px-4 py-2 rounded-lg text-sm shadow-lg cursor-pointer animate-fade-in
            transition-all duration-200
            ${toast.type === 'error' ? 'bg-red-600 text-white' :
              toast.type === 'info' ? 'bg-slate-700 text-white' :
              'bg-slate-900 text-white'}
          `}
        >
          {toast.message}
        </div>
      ))}
    </div>
  );
}
