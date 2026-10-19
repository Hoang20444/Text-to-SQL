import React, { useState } from 'react';
import { Sidebar } from './components/layout/Sidebar';
import { Header } from './components/layout/Header';
import { QueryInputBox } from './components/query/QueryInputBox';
import { DatasetInfoCard } from './components/query/DatasetInfoCard';
import { SampleQueryCards } from './components/query/SampleQueryCards';
import { ResultView } from './components/result/ResultView';
import { queryApi } from './services/api';
import { QueryResponse } from './types';
import { MOCK_QUERY_RESPONSE } from './services/mockData';

export const App: React.FC = () => {
  const [currentTab, setCurrentTab] = useState('query');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<QueryResponse | null>(null);

  const handleRunQuery = async (question: string) => {
    setLoading(true);
    try {
      const resp = await queryApi.execute({ question });
      setResult(resp);
    } catch (err: any) {
      console.warn('API error, showing mock fallback for UI demonstration', err);
      setResult({
        ...MOCK_QUERY_RESPONSE,
        question
      });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#f8fafc] flex">
      {/* Fixed Sidebar */}
      <Sidebar currentTab={currentTab} onTabChange={setCurrentTab} />

      {/* Main Content Area */}
      <div className="flex-1 ml-64 flex flex-col min-h-screen">
        <Header />

        <main className="flex-1 p-8 max-w-[1600px] w-full mx-auto">
          {currentTab === 'query' && (
            <div className="grid grid-cols-1 xl:grid-cols-12 gap-8 items-start">
              {/* Left Column: Query Box, Results, Sample Cards */}
              <div className="xl:col-span-8 space-y-6">
                <QueryInputBox onSubmit={handleRunQuery} loading={loading} />

                {result && <ResultView result={result} />}

                <SampleQueryCards onSelectQuery={handleRunQuery} />
              </div>

              {/* Right Column: Dataset Info & Guidance */}
              <div className="xl:col-span-4">
                <DatasetInfoCard />
              </div>
            </div>
          )}

          {currentTab !== 'query' && (
            <div className="bg-white rounded-2xl p-12 text-center border border-slate-200">
              <h3 className="text-lg font-bold text-slate-800 mb-1">Màn hình {currentTab.toUpperCase()}</h3>
              <p className="text-xs text-slate-500">
                Tính năng này đang trong backlog Sprint 2. Vui lòng chuyển sang tab <strong>Truy vấn</strong> để trải nghiệm.
              </p>
            </div>
          )}
        </main>
      </div>
    </div>
  );
};

export default App;
