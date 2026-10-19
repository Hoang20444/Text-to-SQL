import React, { useState } from 'react';
import { BarChart, Trophy, MapPin, Users, Music, DollarSign, ChevronRight } from 'lucide-react';
import { FewShotItem } from '../../types';

interface SampleQueryCardsProps {
  onSelectQuery: (question: string) => void;
}

export const SampleQueryCards: React.FC<SampleQueryCardsProps> = ({ onSelectQuery }) => {
  const [activeTab, setActiveTab] = useState('ALL');

  const tabs = [
    { id: 'ALL', label: 'Tất cả' },
    { id: 'Revenue', label: 'Doanh thu & Bán hàng' },
    { id: 'Product', label: 'Bài hát & Album' },
    { id: 'Customer', label: 'Khách hàng' },
    { id: 'Artist', label: 'Nghệ sĩ' }
  ];

  const sampleCards = [
    {
      category: 'Revenue',
      title: 'Tổng quan doanh thu',
      desc: 'Tổng doanh thu theo từng quốc gia của khách hàng?',
      icon: DollarSign,
      color: 'bg-emerald-50 text-emerald-600'
    },
    {
      category: 'Product',
      title: 'Top bài hát dài nhất',
      desc: 'Danh sách 10 bài hát có thời lượng dài nhất?',
      icon: Trophy,
      color: 'bg-amber-50 text-amber-600'
    },
    {
      category: 'Customer',
      title: 'Khách hàng trung thành',
      desc: 'Top 5 khách hàng chi tiêu nhiều nhất trong hệ thống?',
      icon: Users,
      color: 'bg-blue-50 text-blue-600'
    },
    {
      category: 'Revenue',
      title: 'Doanh thu theo thể loại',
      desc: 'Doanh thu theo từng thể loại nhạc (Genre)?',
      icon: BarChart,
      color: 'bg-purple-50 text-purple-600'
    },
    {
      category: 'Artist',
      title: 'Thống kê nghệ sĩ',
      desc: 'Có bao nhiêu nghệ sĩ trong cơ sở dữ liệu?',
      icon: Music,
      color: 'bg-pink-50 text-pink-600'
    },
    {
      category: 'Customer',
      title: 'Phân bố địa lý',
      desc: 'Số lượng khách hàng theo từng quốc gia?',
      icon: MapPin,
      color: 'bg-cyan-50 text-cyan-600'
    }
  ];

  const filteredCards = activeTab === 'ALL' 
    ? sampleCards 
    : sampleCards.filter(c => c.category === activeTab);

  return (
    <div className="bg-white rounded-2xl p-6 border border-slate-200/80 shadow-sm mt-6">
      <div className="flex items-center justify-between mb-4">
        <h3 className="font-bold text-base text-slate-800">Câu hỏi mẫu cho Chinook Music Store</h3>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-2 border-b border-slate-100 pb-3 mb-4 overflow-x-auto text-xs">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`px-3 py-1.5 rounded-lg font-medium transition cursor-pointer shrink-0 ${
              activeTab === tab.id
                ? 'bg-blue-50 text-blue-600 font-semibold'
                : 'text-slate-500 hover:text-slate-800 hover:bg-slate-50'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Grid of Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3.5">
        {filteredCards.map((card, idx) => {
          const Icon = card.icon;
          return (
            <div
              key={idx}
              onClick={() => onSelectQuery(card.desc)}
              className="p-4 rounded-xl border border-slate-200/70 hover:border-blue-400/80 hover:shadow-md transition-all cursor-pointer bg-slate-50/50 hover:bg-white group"
            >
              <div className="flex items-center gap-3 mb-2">
                <div className={`w-8 h-8 rounded-lg ${card.color} flex items-center justify-center`}>
                  <Icon className="w-4 h-4" />
                </div>
                <div className="font-semibold text-xs text-slate-800 group-hover:text-blue-600 transition">
                  {card.title}
                </div>
              </div>
              <p className="text-xs text-slate-500 line-clamp-2 leading-relaxed">
                {card.desc}
              </p>
              <div className="flex items-center justify-end text-[11px] text-blue-600 font-medium mt-2 opacity-0 group-hover:opacity-100 transition">
                <span>Thực hiện ngay</span>
                <ChevronRight className="w-3.5 h-3.5" />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
