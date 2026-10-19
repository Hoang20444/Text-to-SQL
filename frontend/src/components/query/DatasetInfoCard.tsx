import React from 'react';
import { Database, ShieldCheck, HelpCircle, ArrowRight } from 'lucide-react';
import { MOCK_DATASET_INFO } from '../../services/mockData';

export const DatasetInfoCard: React.FC = () => {
  return (
    <div className="space-y-5">
      {/* Dataset Overview */}
      <div className="bg-white rounded-2xl p-5 border border-slate-200/80 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <Database className="w-5 h-5 text-blue-600" />
          <h3 className="font-bold text-sm text-slate-800">Thông tin dataset</h3>
        </div>

        <div className="p-3 bg-slate-50 rounded-xl mb-4 border border-slate-100">
          <div className="flex items-center justify-between mb-1">
            <span className="font-bold text-xs text-slate-900">{MOCK_DATASET_INFO.name}</span>
            <span className="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-100 text-emerald-700">
              {MOCK_DATASET_INFO.status}
            </span>
          </div>
          <p className="text-[11px] text-slate-500 leading-relaxed">
            Dữ liệu phân tích bán hàng âm nhạc số chuẩn hóa 3NF: Khách hàng, Hoá đơn, Bài hát, Nghệ sĩ và Doanh thu.
          </p>
        </div>

        {/* Stats Grid */}
        <div className="grid grid-cols-4 gap-2 text-center py-2 border-y border-slate-100 mb-4">
          <div>
            <div className="font-bold text-base text-slate-800">{MOCK_DATASET_INFO.tablesCount}</div>
            <div className="text-[10px] text-slate-400 uppercase font-medium">Bảng</div>
          </div>
          <div>
            <div className="font-bold text-base text-slate-800">{MOCK_DATASET_INFO.metricsCount}</div>
            <div className="text-[10px] text-slate-400 uppercase font-medium">Metrics</div>
          </div>
          <div>
            <div className="font-bold text-base text-slate-800">{MOCK_DATASET_INFO.dimensionsCount}</div>
            <div className="text-[10px] text-slate-400 uppercase font-medium">Dims</div>
          </div>
          <div>
            <div className="font-bold text-base text-slate-800">{MOCK_DATASET_INFO.version}</div>
            <div className="text-[10px] text-slate-400 uppercase font-medium">Version</div>
          </div>
        </div>

        <div className="space-y-1.5 text-xs text-slate-500 mb-3">
          <div className="flex justify-between">
            <span>Warehouse:</span>
            <span className="font-medium text-slate-700">{MOCK_DATASET_INFO.warehouse}</span>
          </div>
          <div className="flex justify-between">
            <span>Cập nhật gần nhất:</span>
            <span className="font-medium text-slate-700">{MOCK_DATASET_INFO.updatedAt}</span>
          </div>
        </div>

        <button className="w-full flex items-center justify-center gap-1 py-2 text-xs font-semibold text-blue-600 hover:text-blue-700 hover:bg-blue-50/70 rounded-lg transition">
          <span>Xem chi tiết Data Catalog</span>
          <ArrowRight className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Query Guidance & Safety */}
      <div className="bg-white rounded-2xl p-5 border border-slate-200/80 shadow-sm">
        <div className="flex items-center gap-2 mb-3">
          <HelpCircle className="w-5 h-5 text-indigo-600" />
          <h3 className="font-bold text-sm text-slate-800">Hướng dẫn &amp; Mẹo truy vấn</h3>
        </div>

        <div className="space-y-3 text-xs text-slate-600">
          <div className="flex items-start gap-2.5">
            <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold shrink-0 mt-0.5">1</span>
            <p>Hỏi tự nhiên bằng tiếng Việt hoặc tiếng Anh về doanh thu, số lượng, top bảng xếp hạng.</p>
          </div>
          <div className="flex items-start gap-2.5">
            <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold shrink-0 mt-0.5">2</span>
            <p><strong>Bảo mật:</strong> Guardrail chỉ cho phép câu lệnh SELECT, tự động chặn INSERT/UPDATE/DROP.</p>
          </div>
          <div className="flex items-start gap-2.5">
            <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold shrink-0 mt-0.5">3</span>
            <p><strong>Tự động vẽ biểu đồ:</strong> Kết quả 2 cột sẽ được tự động vẽ thành biểu đồ cột (Bar Chart) hoặc đường (Line Chart).</p>
          </div>
        </div>
      </div>
    </div>
  );
};
