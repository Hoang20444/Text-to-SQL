import {
  createContext,
  useContext,
  useState,
  useRef,
  useEffect,
  type ReactNode,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { warehouseApi } from "../api/warehouseApi";
import { datasetApi } from "../api/datasetApi";
import type { Warehouse, Dataset } from "../api/schema";
import { useAuth, canReadApi } from "./auth";
import {
  readWarehouseSelection,
  warehouseSelectionKey,
  type WarehouseSelection,
} from "./warehouseSelection";
const en: Record<string, string> = {
  "Trang chủ": "Home",
  "Truy vấn": "Query",
  "Lịch sử truy vấn": "Query history",
  "Đánh giá": "Evaluation",
  "Nạp dữ liệu": "Ingestion",
  "Tri thức AI": "AI knowledge",
  "Pipelines dữ liệu": "Data pipelines",
  "Chất lượng dữ liệu": "Data quality",
  "Giám sát": "Monitoring",
  "Cài đặt": "Settings",
  "Ngôn ngữ": "Language",
  "Tất cả": "All",
  "Tìm kiếm": "Search",
  "Thử lại": "Retry",
  "Đang tải dữ liệu…": "Loading data…",
  "Đăng nhập": "Sign in",
  "Chưa đăng nhập": "Not signed in",
  "Đăng xuất": "Sign out",
  "Áp dụng": "Apply",
  "Đặt lại": "Reset",
  Lưu: "Save",
  Hủy: "Cancel",
  Đóng: "Close",
  "Không có dữ liệu": "No data",
  "Thêm warehouse": "Add warehouse",
  "Ngữ nghĩa nghiệp vụ": "Business semantics",
  "Quản lý định nghĩa chỉ số, chiều phân tích và quan hệ an toàn cho dataset.":
    "Manage metrics, analytical dimensions, and safe relationships for a dataset.",
  "Chỉ quản trị viên mới có thể chỉnh sửa mô hình ngữ nghĩa.":
    "Only administrators can edit the semantic model.",
  "Chưa chọn dataset": "No dataset selected",
  "Chọn một dataset đang hoạt động trên thanh đầu trang để tải mô hình thật.":
    "Select an active dataset in the top bar to load its live model.",
  "Schema version": "Schema version",
  "Semantic version": "Semantic version",
  "Gợi ý bằng AI": "Suggest with AI",
  "Gợi ý ngữ nghĩa từ AI": "AI semantic suggestions",
  "Kiểm tra mô hình": "Validate model",
  "Đang kiểm tra…": "Validating…",
  "Lưu phiên bản mới": "Save new version",
  "Đang lưu…": "Saving…",
  "Đang yêu cầu model…": "Requesting model…",
  "Vị trí cần sửa": "Field to fix",
  "Hãy kiểm tra cột, công thức hoặc cardinality được API chỉ ra rồi xác thực lại.":
    "Check the column, expression, or cardinality reported by the API, then validate again.",
  "Đã lưu mô hình ngữ nghĩa phiên bản": "Saved semantic model version",
  "Mô hình hợp lệ": "Model is valid",
  "Mô hình đã được cập nhật ở nơi khác.":
    "The semantic model changed elsewhere.",
  "Bản nháp hiện tại vẫn được giữ. Tải bản mới nhất trước khi xác thực hoặc lưu để tránh ghi đè thay đổi khác.":
    "Your draft is preserved. Load the latest model before validating or saving to avoid overwriting another change.",
  "Tải bản mới nhất và bỏ bản nháp": "Load latest model and discard this draft",
  "Bản nháp đã lệch phiên bản đang hoạt động. Hãy tải bản mới nhất trước khi tiếp tục.":
    "This draft is based on an older active version. Load the latest model before continuing.",
  "chỉ số": "metrics",
  "chiều phân tích": "dimensions",
  "mối quan hệ": "relationships",
  "Đang kiểm tra metadata và khóa nối trên warehouse thật…":
    "Checking metadata and join keys against the live warehouse…",
  "Bảng trong dataset": "Tables in dataset",
  "Chỉ số": "Metrics",
  "Chiều phân tích": "Dimensions",
  "Mối quan hệ": "Relationships",
  "Quan hệ bảng": "Table relationships",
  "Các phần mô hình ngữ nghĩa": "Semantic model sections",
  "Chỉ số nghiệp vụ": "Business metrics",
  "Thêm chỉ số": "Add metric",
  "Chưa có chỉ số": "No metrics yet",
  "Tạo một chỉ số hoặc yêu cầu AI đề xuất từ schema thật.":
    "Create a metric or ask AI for suggestions based on the live schema.",
  "Chạy thử chỉ số": "Run metric",
  "Chưa có mô tả.": "No description.",
  Bảng: "Table",
  "Chiều tương thích": "Compatible dimensions",
  "Từ đồng nghĩa": "Synonyms",
  Sửa: "Edit",
  Xóa: "Delete",
  "Thêm chiều": "Add dimension",
  "Chưa có chiều phân tích": "No dimensions yet",
  "Tạo chiều từ cột vật lý trong dataset hoặc dùng gợi ý AI để bắt đầu.":
    "Create a dimension from a physical dataset column or start with AI suggestions.",
  "Cột nguồn": "Source column",
  "Quan hệ giữa các bảng": "Relationships between tables",
  "Thêm quan hệ": "Add relationship",
  "Quan hệ khóa ngoại được phát hiện từ warehouse và không thể sửa tại đây. Quan hệ khai báo phải qua kiểm tra dữ liệu thật trước khi lưu.":
    "Foreign-key relationships are discovered from the warehouse and cannot be edited here. Declared relationships are checked against live data before saving.",
  "Chưa có quan hệ bảng": "No table relationships",
  "Quan hệ vật lý sẽ xuất hiện sau khi metadata được đọc từ warehouse.":
    "Physical relationships appear after metadata is read from the warehouse.",
  "Đường nối": "Join path",
  Cardinality: "Cardinality",
  Nguồn: "Source",
  "Trạng thái kiểm tra": "Validation status",
  "Thao tác": "Actions",
  "Khóa ngoại vật lý": "Physical foreign key",
  "Khai báo nghiệp vụ": "Business declaration",
  "Đọc từ schema": "Read from schema",
  "Sẽ được xác thực khi lưu": "Validated when saved",
  "Xóa quan hệ": "Remove relationship",
  "Quy tắc nghiệp vụ": "Business rules",
  "Mỗi dòng là một quy tắc": "Enter one rule per line",
  "Các quy tắc này cung cấp ngữ cảnh cho lập kế hoạch truy vấn; không thay thế quyền và kiểm tra SQL của backend.":
    "These rules provide query-planning context; they do not replace backend authorization or SQL validation.",
  "quy tắc": "rules",
  "Ví dụ câu hỏi đã duyệt": "Approved question examples",
  "Ví dụ câu hỏi": "Question examples",
  "Chưa có ví dụ câu hỏi": "No question examples yet",
  "Thêm câu hỏi và SQL đã kiểm tra vào mô hình để cung cấp ví dụ sát với dataset này.":
    "Add validated questions and SQL to provide examples tailored to this dataset.",
  "Không chọn metric": "No metric selected",
  "Không chọn dimension": "No dimension selected",
  "Chạy thử truy vấn theo mô hình": "Run a query using the semantic model",
  "Chọn một hoặc nhiều chỉ số. Chiều chỉ được chọn nếu tương thích với tất cả chỉ số đang chọn.":
    "Select one or more metrics. A dimension is selectable only when compatible with every selected metric.",
  "Đã chọn": "Selected",
  chiều: "dimensions",
  "Biên dịch và chạy": "Compile and run",
  "Đang chạy…": "Running…",
  "Java biên dịch lựa chọn theo mô hình đã lưu, sau đó kiểm tra an toàn và chạy trên warehouse.":
    "Java compiles the selection from the saved model, validates it, and executes it on the warehouse.",
  "Đang hiển thị mô hình đã lưu từ API.":
    "Showing the saved model from the API.",
  "Có thay đổi chưa lưu": "Unsaved changes",
  "đã kiểm tra": "validated",
  "chưa kiểm tra": "not validated",
  "Thêm chiều phân tích": "Add dimension",
  "Sửa chiều phân tích": "Edit dimension",
  "Sửa chỉ số": "Edit metric",
  "Thêm quan hệ bảng": "Add table relationship",
  "Áp dụng vào bản nháp": "Apply to draft",
  "AI chỉ nhận metadata bảng và cột của dataset này. Đề xuất được kiểm tra bằng schema thật; không tự lưu hoặc kích hoạt.":
    "AI receives only this dataset's table and column metadata. Suggestions are checked against the live schema and are never saved or activated automatically.",
  "Hướng dẫn bổ sung (tùy chọn)": "Additional instructions (optional)",
  "Ví dụ: ưu tiên chỉ số doanh thu và chiều theo thời gian.":
    "Example: prioritize revenue metrics and time dimensions.",
  "Tạo gợi ý": "Generate suggestions",
  "Rà soát gợi ý AI": "Review AI suggestions",
  "Bản nháp từ": "Draft from",
  Gốc: "Base",
  "phiên bản đề xuất": "suggested version",
  "Chỉ số đề xuất": "Suggested metrics",
  "Tên chỉ số": "Metric name",
  "Mô tả": "Description",
  "Đơn vị": "Unit",
  "Định nghĩa hiện tại": "Current definition",
  "Chiều đề xuất": "Suggested dimensions",
  "Tên chiều": "Dimension name",
  "Quy tắc đề xuất": "Suggested rules",
  "Chọn áp dụng": "Select to apply",
  Mới: "New",
  "Đề xuất thay đổi": "Changed",
  "Không thay đổi": "Unchanged",
  "Không hợp lệ": "Invalid",
  "Gợi ý chưa hợp lệ tại trường": "The suggestion is invalid at field",
  "Một số gợi ý không hợp lệ. Hãy sửa chúng trước khi áp dụng.":
    "Some suggestions are invalid. Fix them before applying.",
  "Đưa lựa chọn vào bản nháp": "Apply selected items to draft",
  "Bản nháp AI chỉ cập nhật bộ soạn thảo trên trang này. Lưu phiên bản mới mới thay đổi mô hình đang hoạt động.":
    "The AI draft only updates this editor. Save a new version to change the active model.",
  "Biểu thức tổng hợp": "Aggregate expression",
  "Biểu thức sẽ được backend parse và kiểm tra trên các bảng đã chọn.":
    "The backend parses and validates the expression against the selected tables.",
  "Bảng nguồn": "Source tables",
  "Chọn bảng chứa biểu thức metric.":
    "Select the tables referenced by this metric.",
  "Chỉ các chiều có đường nối an toàn với metric mới được đưa vào lựa chọn chạy thử.":
    "Only dimensions with a safe join path from this metric are selectable for a trial query.",
  "Phân tách bằng dấu phẩy.": "Separate items with commas.",
  "Bảng bắt đầu": "From table",
  "Bảng đích": "To table",
  Cột: "Column",
  "Cardinality là giả định nghiệp vụ, backend sẽ kiểm tra khóa ở warehouse thật khi xác thực/lưu.":
    "Cardinality is a business assumption. The backend checks the keys against live warehouse data when validating or saving.",
  "Kiểm tra lại thông tin chỉ số.": "Review the metric fields.",
  "Kiểm tra lại thông tin chiều phân tích.": "Review the dimension fields.",
  "Kiểm tra lại hai đầu mối quan hệ.": "Review both relationship endpoints.",
  "Mối quan hệ này đã được phát hiện từ khóa ngoại của warehouse.":
    "This relationship was discovered from a warehouse foreign key.",
  "Lựa chọn chạy thử": "Trial selection",
  "Chưa tải được mô hình ngữ nghĩa.": "Could not load the semantic model.",
  "Lưu mô hình trước khi chạy thử; semantic-query chỉ sử dụng phiên bản đang hoạt động.":
    "Save the model before running a trial; semantic queries use only the active version.",
  "Câu hỏi tiếp nối": "Follow-up context",
  "Chỉ câu hỏi trước được gửi làm ngữ cảnh; các dòng kết quả trước không được gửi lại cho model.":
    "Only the previous question is sent as context; prior result rows are not sent to the model.",
  "Truy vấn gốc thuộc dataset hoặc warehouse khác. Hãy chọn đúng nguồn trước khi tiếp tục.":
    "The source query belongs to a different dataset or warehouse. Select the same source before continuing.",
  "Đang tải câu hỏi trước để tạo ngữ cảnh tiếp nối…":
    "Loading the previous question to build follow-up context…",
  "Câu hỏi trước trong luồng tiếp nối": "Previous question in this follow-up",
  "Đang tải câu hỏi trước…": "Loading the previous question…",
  "Đặt câu hỏi tiếp theo": "Ask a follow-up question",
  "Bạn cần quyền ANALYST để tiếp tục truy vấn.":
    "You need the ANALYST role to continue querying.",
  "Chỉ có thể hỏi tiếp từ truy vấn đã chạy thành công.":
    "You can only continue from a successful query.",
  "Ngữ cảnh câu hỏi vượt giới hạn 1000 ký tự. Hãy rút gọn câu hỏi tiếp theo.":
    "The combined question context exceeds the 1000-character limit. Shorten the follow-up question.",
  "Đặt câu hỏi tiếp theo từ ngữ cảnh này": "Ask a follow-up using this context",
  "Model nhận câu hỏi trước và câu hỏi mới; các dòng kết quả không được tự động gửi lại.":
    "The model receives the previous and new questions; result rows are not sent automatically.",
  "Ví dụ: Chỉ hiển thị tháng 9 và nhóm theo khu vực":
    "Example: Show only September and group by region",
  "Tiếp tục": "Continue",
};
const LocaleContext = createContext({
  locale: "vi",
  setLocale: (_v: string) => {},
  t: (s: string) => s,
});
export function LocaleProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState(() =>
    localStorage.getItem("nla-locale") === "en" ? "en" : "vi",
  );
  const setLocale = (v: string) => {
    setLocaleState(v);
    localStorage.setItem("nla-locale", v);
  };
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  return (
    <LocaleContext
      value={{
        locale,
        setLocale,
        t: (s) => (locale === "en" ? en[s] || s : s),
      }}
    >
      {children}
    </LocaleContext>
  );
}
export const useLocale = () => useContext(LocaleContext);
type ContextState = {
  warehouse: Warehouse | undefined;
  dataset: Dataset | undefined;
  warehouseId: string;
  datasetId: string;
  selectWarehouse: (w: Warehouse) => void;
  selectDataset: (d: Dataset) => void;
  warehouseLoading: boolean;
  datasetLoading: boolean;
  error: Error | null;
  reload: () => void;
  enabled: boolean;
};
const WarehouseContext = createContext<ContextState | null>(null);
export function WarehouseProvider({ children }: { children: ReactNode }) {
  const auth = useAuth(),
    enabled = canReadApi(auth),
    client = useQueryClient();
  const identity = auth.user?.profile.sub || "anonymous";
  const previousIdentity = useRef(identity);
  useEffect(() => {
    if (previousIdentity.current !== identity) {
      void client.cancelQueries();
      client.clear();
      previousIdentity.current = identity;
    }
  }, [identity, client]);
  const [selection, setSelection] = useState(() =>
    readWarehouseSelection(sessionStorage, identity),
  );
  const activeSelection =
    selection.identity === identity
      ? selection
      : readWarehouseSelection(sessionStorage, identity);
  const { warehouseId, datasetId } = activeSelection;
  useEffect(() => {
    if (selection.identity !== identity) setSelection(activeSelection);
  }, [activeSelection, identity, selection.identity]);
  const warehouseList = useQuery({
    queryKey: ["warehouses", identity, "initial"],
    queryFn: ({ signal }) =>
      warehouseApi.list({ page: 1, pageSize: 20 }, signal),
    enabled,
  });
  const warehouseDetail = useQuery({
    queryKey: ["warehouse", identity, warehouseId],
    queryFn: ({ signal }) => warehouseApi.get(warehouseId, signal),
    enabled: enabled && Boolean(warehouseId),
  });
  const warehouse = warehouseDetail.data;
  useEffect(() => {
    if (enabled && !warehouseId && warehouseList.data?.items.length) {
      const w =
        warehouseList.data.items.find((w) => w.status === "ACTIVE") ||
        warehouseList.data.items[0];
      setSelection({ identity, warehouseId: w.warehouseId, datasetId: "" });
    }
  }, [enabled, identity, warehouseId, warehouseList.data]);
  const datasets = useQuery({
    queryKey: ["datasets", identity, "initial", warehouseId],
    queryFn: ({ signal }) =>
      datasetApi.list({ warehouseId, page: 1, pageSize: 20 }, signal),
    enabled: enabled && Boolean(warehouseId),
  });
  const datasetDetail = useQuery({
    queryKey: ["dataset", identity, datasetId],
    queryFn: ({ signal }) => datasetApi.get(datasetId, signal),
    enabled: enabled && Boolean(datasetId),
  });
  const dataset =
    datasetDetail.data?.warehouseId === warehouseId
      ? datasetDetail.data
      : undefined;
  useEffect(() => {
    if (enabled && warehouseId && !datasetId && datasets.data?.items.length) {
      const d =
        datasets.data.items.find((d) => d.status === "ACTIVE") ||
        datasets.data.items[0];
      setSelection({ identity, warehouseId, datasetId: d.datasetId });
    }
  }, [enabled, identity, warehouseId, datasetId, datasets.data]);
  useEffect(() => {
    if (datasetDetail.data && datasetDetail.data.warehouseId !== warehouseId)
      setSelection({ identity, warehouseId, datasetId: "" });
  }, [datasetDetail.data, identity, warehouseId]);
  useEffect(() => {
    sessionStorage.setItem(
      warehouseSelectionKey(identity),
      JSON.stringify({ warehouseId, datasetId }),
    );
  }, [identity, warehouseId, datasetId]);
  const selectWarehouse = (w: Warehouse) => {
    if (w.warehouseId === warehouseId) return;
    void client.cancelQueries();
    const next = { identity, warehouseId: w.warehouseId, datasetId: "" };
    setSelection(next);
    sessionStorage.setItem(
      warehouseSelectionKey(identity),
      JSON.stringify(next),
    );
  };
  const selectDataset = (d: Dataset) => {
    if (d.warehouseId !== warehouseId) return;
    void client.cancelQueries();
    client.setQueryData(["dataset", identity, d.datasetId], d);
    const next = { ...activeSelection, datasetId: d.datasetId };
    setSelection(next);
    sessionStorage.setItem(
      warehouseSelectionKey(identity),
      JSON.stringify(next),
    );
  };
  useEffect(() => {
    if (!enabled && auth.ready) {
      client.clear();
    }
  }, [enabled, auth.ready, client]);
  return (
    <WarehouseContext
      value={{
        warehouse,
        dataset,
        warehouseId,
        datasetId,
        selectWarehouse,
        selectDataset,
        warehouseLoading:
          enabled && (warehouseList.isLoading || warehouseDetail.isLoading),
        datasetLoading:
          enabled && (datasets.isLoading || datasetDetail.isLoading),
        error:
          warehouseList.error ||
          warehouseDetail.error ||
          datasets.error ||
          datasetDetail.error,
        reload: () => {
          void client.invalidateQueries();
        },
        enabled,
      }}
    >
      {children}
    </WarehouseContext>
  );
}
export function useWarehouse() {
  const context = useContext(WarehouseContext);
  if (!context) throw new Error("WarehouseProvider is required");
  return context;
}
export function retryApiRead(failureCount: number, error: unknown) {
  return error instanceof ApiError && error.retryable && failureCount < 2;
}

export const queryClientDefaults = {
  queries: {
    retry: retryApiRead,
    retryDelay: (attempt: number) => Math.min(150 * 2 ** attempt, 600),
    refetchOnWindowFocus: false,
    staleTime: 15000,
  },
  mutations: { retry: false as const },
};
