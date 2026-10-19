import React, { useState } from 'react';
import { Send, Trash2, HelpCircle, Sparkles, Bot, Loader2 } from 'lucide-react';

interface QueryInputBoxProps {
  onSubmit: (question: string) => void;
  loading: boolean;
}

export const QueryInputBox: React.FC<QueryInputBoxProps> = ({ onSubmit, loading }) => {
  const [question, setQuestion] = useState('');

  const sampleChips = [
    'Tổng doanh thu theo từng quốc gia?',
    'Top 10 bài hát có thời lượng dài nhất?',
    'Top 5 khách hàng chi tiêu nhiều nhất?',
    'Doanh thu theo từng thể loại nhạc (Genre)?'
  ];

  const handleSubmit = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!question.trim() || loading) return;
    onSubmit(question.trim());
  };

  const handleChipClick = (chipText: string) => {
    setQuestion(chipText);
    onSubmit(chipText);
  };

  return (
    <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200/80 mb-6">
      {/* Hero Banner Header */}
      <div className="flex items-start justify-between mb-5">
        <div>
          <h2 className="text-2xl font-bold text-slate-900 tracking-tight">
            Truy vấn dữ liệu bằng <span className="text-blue-600">ngôn ngữ tự nhiên</span>
          </h2>
          <p className="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">
            Đặt câu hỏi bằng tiếng Việt hoặc tiếng Anh để khám phá dữ liệu trong data warehouse.
            Hệ thống sẽ tự động phân tích câu hỏi bằng Gemini, tạo SQL an toàn, thực thi và trả về biểu đồ.
          </p>
        </div>
        <div className="hidden lg:flex items-center gap-2 px-3 py-2 bg-blue-50 border border-blue-200/60 rounded-xl text-blue-700 text-xs font-semibold">
          <Bot className="w-5 h-5 text-blue-600 animate-bounce" />
          <span>Gemini Text-to-SQL</span>
        </div>
      </div>

      {/* Input Form */}
      <form onSubmit={handleSubmit} className="relative">
        <div className="relative border border-slate-200 rounded-xl focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-100 transition">
          <textarea
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            disabled={loading}
            maxLength={1000}
            rows={3}
            placeholder="Ví dụ: Doanh thu theo từng quốc gia là bao nhiêu? Hoặc thử: Top 5 khách hàng mua nhiều nhất?"
            className="w-full p-4 text-sm text-slate-800 placeholder-slate-400 focus:outline-none rounded-t-xl resize-none disabled:bg-slate-50"
          />

          <div className="flex items-center justify-between px-4 py-2.5 bg-slate-50/70 border-t border-slate-100 rounded-b-xl text-xs">
            {/* Action Tools */}
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setQuestion('')}
                className="flex items-center gap-1 text-slate-500 hover:text-red-600 transition px-2 py-1 rounded hover:bg-slate-200/60"
              >
                <Trash2 className="w-3.5 h-3.5" />
                <span>Xóa</span>
              </button>

              <div className="h-3 w-px bg-slate-300"></div>

              <div className="flex items-center gap-1.5 text-slate-600 px-2 py-1 bg-white border border-slate-200 rounded-md">
                <Sparkles className="w-3.5 h-3.5 text-indigo-500" />
                <span className="text-[11px] font-medium">Model: Gemini 2.5 Flash</span>
              </div>
            </div>

            {/* Counter & Submit Button */}
            <div className="flex items-center gap-3">
              <span className="text-[11px] text-slate-400">{question.length}/1000</span>
              <button
                type="submit"
                disabled={!question.trim() || loading}
                className="flex items-center gap-2 px-5 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 active:scale-95 text-white font-medium shadow-md shadow-blue-600/20 transition disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {loading ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>Đang xử lý...</span>
                  </>
                ) : (
                  <>
                    <Send className="w-3.5 h-3.5" />
                    <span>Gửi</span>
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      </form>

      {/* Quick Question Chips */}
      <div className="flex flex-wrap items-center gap-2 mt-4 text-xs">
        <span className="font-semibold text-slate-600">Thử ngay:</span>
        {sampleChips.map((chip, idx) => (
          <button
            key={idx}
            type="button"
            onClick={() => handleChipClick(chip)}
            className="px-3 py-1 rounded-full bg-slate-100 hover:bg-blue-50 hover:text-blue-600 text-slate-700 transition border border-slate-200/60 flex items-center gap-1 cursor-pointer"
          >
            <span>{chip}</span>
            <span className="text-slate-400">&rsaquo;</span>
          </button>
        ))}
      </div>
    </div>
  );
};
