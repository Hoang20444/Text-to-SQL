import axios from 'axios';
import { QueryRequest, QueryResponse, FewShotItem, DataCatalogItem } from '../types';
import { MOCK_QUERY_RESPONSE, MOCK_FEW_SHOTS } from './mockData';

const apiClient = axios.create({
  baseURL: 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json'
  },
  timeout: 15000
});

// Toggle this flag if backend is not yet started
const USE_MOCK = false;

export const queryApi = {
  // Send natural language query
  execute: async (payload: QueryRequest): Promise<QueryResponse> => {
    if (USE_MOCK) {
      return new Promise((resolve) => setTimeout(() => resolve(MOCK_QUERY_RESPONSE), 800));
    }
    try {
      const response = await apiClient.post<QueryResponse>('/query', payload);
      return response.data;
    } catch (error: any) {
      if (error.response?.data) {
        return error.response.data;
      }
      throw error;
    }
  },

  // Get sample questions
  getFewShots: async (category?: string): Promise<FewShotItem[]> => {
    if (USE_MOCK) {
      return MOCK_FEW_SHOTS;
    }
    try {
      const response = await apiClient.get<FewShotItem[]>('/few-shots', {
        params: { category }
      });
      return response.data;
    } catch (error) {
      return MOCK_FEW_SHOTS; // fallback
    }
  },

  // Get data catalog
  getCatalog: async (): Promise<DataCatalogItem[]> => {
    try {
      const response = await apiClient.get<DataCatalogItem[]>('/catalog');
      return response.data;
    } catch (error) {
      return [];
    }
  }
};
