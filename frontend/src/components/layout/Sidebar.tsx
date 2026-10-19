import React from 'react';
import { 
  Home, 
  Search, 
  History, 
  Database, 
  BarChart3, 
  UploadCloud, 
  Activity, 
  Settings,
  Layers
} from 'lucide-react';

interface SidebarProps {
  currentTab: string;
  onTabChange: (tab: string) => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ currentTab, onTabChange }) => {
  const menuItems = [
    { id: 'home', label: 'Trang chủ', icon: Home },
    { id: 'query', label: 'Truy vấn', icon: Search },
    { id: 'history', label: 'Lịch sử truy vấn', icon: History },
    { id: 'catalog', label: 'Data Catalog', icon: Database },
    { id: 'eval', label: 'Đánh giá (Eval)', icon: BarChart3 },
    { id: 'upload', label: 'Nạp dữ liệu', icon: UploadCloud },
    { id: 'monitor', label: 'Giám sát', icon: Activity },
    { id: 'settings', label: 'Cài đặt', icon: Settings },
  ];

  return (
    <aside className="w-64 bg-[#0e1726] text-slate-300 flex flex-col justify-between h-screen fixed left-0 top-0 z-30 select-none border-r border-slate-800">
      <div>
        {/* Brand */}
        <div className="flex items-center gap-3 px-5 py-6 border-b border-slate-800/80">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-500 flex items-center justify-center text-white shadow-lg shadow-blue-500/20">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <h1 className="font-bold text-sm text-white leading-tight">Natural Language</h1>
            <p className="text-xs text-slate-400">Analytics Warehouse</p>
          </div>
        </div>

        {/* Menu Navigation */}
        <nav className="p-3 space-y-1">
          {menuItems.map((item) => {
            const Icon = item.icon;
            const isActive = currentTab === item.id;
            return (
              <button
                key={item.id}
                onClick={() => onTabChange(item.id)}
                className={`w-full flex items-center gap-3 px-3.5 py-2.5 rounded-lg text-sm font-medium transition-all ${
                  isActive
                    ? 'bg-blue-600 text-white shadow-md shadow-blue-600/30'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                }`}
              >
                <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-slate-400'}`} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
      </div>

      {/* Workspace Footer */}
      <div className="p-3 border-t border-slate-800/80">
        <div className="bg-slate-800/60 p-3 rounded-lg border border-slate-700/50">
          <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 mb-1">Dataset Đang Dùng</div>
          <div className="text-xs font-medium text-white flex items-center gap-2">
            <span className="w-2 h-2 rounded-full bg-emerald-400"></span>
            Chinook Music Store
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">PostgreSQL (11 bảng)</div>
        </div>
      </div>
    </aside>
  );
};
