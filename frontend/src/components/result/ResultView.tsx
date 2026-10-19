import React, { useState } from 'react';
import { 
  Code, 
  Copy, 
  Check, 
  Clock, 
  ListFilter, 
  Table as TableIcon, 
  BarChart3, 
  AlertTriangle,
  CheckCircle2
} from 'lucide-react';
import { 
  BarChart, 
  Bar, 
  XAxis, 
  YAxis, 
  CartesianGrid, 
  Tooltip, 
  ResponsiveContainer, 
  LineChart, 
  Line 
} from 'recharts';
import { QueryResponse } from '../../types';

interface ResultViewProps {
  result: QueryResponse;
}

export const ResultView: React.FC<ResultViewProps> = ({ result }) => {
  const [activeView, setActiveView] = useState<'TABLE' | 'CHART'>('CHART');
  const [copied, setCopied] = useState(false);

  const handleCopySql = () => {
    if (result.generatedSql) {
      navigator.clipboard.writeText(result.generatedSql);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (result.status === 'GUARDRAIL_BLOCKED') {
    return (
      <div className="bg-red-50 border border-red-200 rounded-2xl p-6 mb-6">
        <div className="flex items-center gap-3 text-red-700 font-bold text-base mb-2">
          <AlertTriangle className="w-5 h-5 text-red-600" />
          <span>Guardrail đã chặn câu lệnh!</span>
        </div>
        <p className="text-xs text-red-600 mb-3">{result.errorMessage}</p>
        {result.generatedSql && (
          <pre className="bg-red-950 text-red-200 p-3 rounded-lg text-xs font-mono overflow-x-auto">
            {result.generatedSql}
          </pre>
        )}
      </div>
    );
  }

  if (result.status === 'ERROR') {
    return (
      <div className="bg-amber-50 border border-amber-200 rounded-2xl p-6 mb-6">
        <div className="flex items-center gap-3 text-amber-800 font-bold text-base mb-2">
          <AlertTriangle className="w-5 h-5 text-amber-600" />
          <span>Lỗi thực thi truy vấn</span>
        </div>
        <p className="text-xs text-amber-700">{result.errorMessage}</p>
      </div>
    );
  }

  const columns = result.columns || [];
  const data = result.data || [];
  const xKey = columns[0] || 'category';
  const yKey = columns[1] || 'value';

  return (
    <div className="bg-white rounded-2xl p-6 border border-slate-200/80 shadow-sm mb-6">
      {/* Top Banner: Status & Metrics */}
      <div className="flex flex-wrap items-center justify-between gap-3 pb-4 border-b border-slate-100 mb-4">
        <div className="flex items-center gap-2 text-emerald-700 font-bold text-sm">
          <CheckCircle2 className="w-4 h-4 text-emerald-600" />
          <span>Thực thi thành công</span>
        </div>

        <div className="flex items-center gap-4 text-xs text-slate-500">
          <div className="flex items-center gap-1.5">
            <Clock className="w-3.5 h-3.5 text-slate-400" />
            <span>Thời gian: <strong className="text-slate-700">{result.executionTimeMs} ms</strong></span>
          </div>
          <div className="flex items-center gap-1.5">
            <ListFilter className="w-3.5 h-3.5 text-slate-400" />
            <span>Dòng trả về: <strong className="text-slate-700">{result.rowCount} dòng</strong></span>
          </div>
        </div>
      </div>

      {/* Generated SQL Snippet */}
      <div className="mb-5">
        <div className="flex items-center justify-between text-xs text-slate-500 mb-1.5">
          <span className="font-semibold text-slate-700 flex items-center gap-1.5">
            <Code className="w-3.5 h-3.5 text-blue-600" />
            Truy vấn SQL được tạo bởi Gemini:
          </span>
          <button
            onClick={handleCopySql}
            className="flex items-center gap-1 text-slate-500 hover:text-blue-600 transition"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Đã sao chép' : 'Sao chép SQL'}</span>
          </button>
        </div>
        <pre className="p-3.5 rounded-xl bg-slate-900 text-blue-300 font-mono text-xs overflow-x-auto shadow-inner leading-relaxed">
          {result.generatedSql}
        </pre>
      </div>

      {/* View Switcher: Table vs Chart */}
      <div className="flex items-center justify-between mb-4">
        <h4 className="font-bold text-sm text-slate-800">Dữ liệu phân tích</h4>
        <div className="flex items-center bg-slate-100 p-1 rounded-lg text-xs font-medium">
          <button
            onClick={() => setActiveView('CHART')}
            className={`flex items-center gap-1.5 px-3 py-1 rounded-md transition ${
              activeView === 'CHART'
                ? 'bg-white text-blue-600 shadow-sm font-semibold'
                : 'text-slate-500 hover:text-slate-800'
            }`}
          >
            <BarChart3 className="w-3.5 h-3.5" />
            <span>Biểu đồ</span>
          </button>
          <button
            onClick={() => setActiveView('TABLE')}
            className={`flex items-center gap-1.5 px-3 py-1 rounded-md transition ${
              activeView === 'TABLE'
                ? 'bg-white text-blue-600 shadow-sm font-semibold'
                : 'text-slate-500 hover:text-slate-800'
            }`}
          >
            <TableIcon className="w-3.5 h-3.5" />
            <span>Dạng Bảng</span>
          </button>
        </div>
      </div>

      {/* Render Chart or Table */}
      {activeView === 'CHART' && data.length > 0 && (
        <div className="h-72 w-full pt-2">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 20 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
              <XAxis dataKey={xKey} tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} />
              <Tooltip 
                contentStyle={{ backgroundColor: '#0f172a', borderColor: '#1e293b', borderRadius: '8px', color: '#fff', fontSize: '12px' }} 
              />
              <Bar dataKey={yKey} fill="#3b82f6" radius={[6, 6, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {activeView === 'TABLE' && (
        <div className="overflow-x-auto border border-slate-200 rounded-xl">
          <table className="w-full text-xs text-left">
            <thead className="bg-slate-50 text-slate-600 font-semibold border-b border-slate-200">
              <tr>
                {columns.map((col, idx) => (
                  <th key={idx} className="px-4 py-3">{col}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.map((row, rIdx) => (
                <tr key={rIdx} className="hover:bg-slate-50/80 transition">
                  {columns.map((col, cIdx) => (
                    <td key={cIdx} className="px-4 py-2.5 text-slate-700">
                      {String(row[col] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
