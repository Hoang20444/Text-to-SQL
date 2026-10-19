import React from 'react';
import { Database, ChevronDown, Bell, Globe, User } from 'lucide-react';

export const Header: React.FC = () => {
  return (
    <header className="h-16 bg-white border-b border-slate-200/80 px-8 flex items-center justify-between sticky top-0 z-20">
      {/* Left: Breadcrumbs & Warehouse Dropdowns */}
      <div className="flex items-center gap-4 text-xs">
        <div className="text-slate-400">
          Trang chủ <span className="mx-1.5">/</span> <span className="text-slate-700 font-medium">Truy vấn</span>
        </div>

        <div className="h-4 w-px bg-slate-200"></div>

        {/* Warehouse Selector */}
        <div className="flex items-center gap-2 bg-slate-100 hover:bg-slate-200/70 transition px-3 py-1.5 rounded-lg cursor-pointer text-slate-700">
          <Database className="w-3.5 h-3.5 text-blue-600" />
          <span className="font-medium">Chinook Warehouse (PostgreSQL)</span>
          <ChevronDown className="w-3.5 h-3.5 text-slate-400" />
        </div>

        {/* Dataset Status */}
        <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-emerald-50 text-emerald-700 font-medium text-[11px] border border-emerald-200/60">
          <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse"></span>
          Active
        </div>
      </div>

      {/* Right: Language, Notifications, User */}
      <div className="flex items-center gap-4">
        <button className="flex items-center gap-1.5 text-xs text-slate-600 hover:text-slate-900 px-2.5 py-1.5 rounded-md hover:bg-slate-100">
          <Globe className="w-4 h-4 text-slate-500" />
          <span>Tiếng Việt</span>
        </button>

        <button className="p-2 text-slate-500 hover:text-slate-800 hover:bg-slate-100 rounded-full relative">
          <Bell className="w-4 h-4" />
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full"></span>
        </button>

        <div className="flex items-center gap-2 pl-2 border-l border-slate-200">
          <div className="w-8 h-8 rounded-full bg-gradient-to-tr from-blue-500 to-indigo-600 flex items-center justify-center text-white text-xs font-bold">
            NA
          </div>
          <div className="text-left hidden md:block">
            <div className="text-xs font-semibold text-slate-800">Nguyễn Văn A</div>
            <div className="text-[10px] text-slate-400">Admin / Fresher</div>
          </div>
        </div>
      </div>
    </header>
  );
};
