import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Database,
  Layers3,
  Lightbulb,
  MessageCircle,
  Send,
} from "lucide-react";
import { useWarehouse } from "../app/providers";
import { catalogApi } from "../api/catalogApi";
import { datasetApi } from "../api/datasetApi";
import { warehouseApi } from "../api/warehouseApi";
import { engineLabel } from "../lib/format";
import { Artwork } from "../components/Artwork";
import { PageHeading } from "../components/Shell";
import { Badge, Button, Card, Empty, Resource, Stat } from "../components/ui";

export function HomePage() {
  const context = useWarehouse();
  const navigate = useNavigate();
  const [question, setQuestion] = useState("");

  const warehouses = useQuery({
    queryKey: ["home-warehouses"],
    queryFn: ({ signal }) =>
      warehouseApi.list({ page: 1, pageSize: 20 }, signal),
    enabled: context.enabled,
  });
  const datasets = useQuery({
    queryKey: ["home-datasets"],
    queryFn: ({ signal }) => datasetApi.list({ page: 1, pageSize: 20 }, signal),
    enabled: context.enabled,
  });
  const examples = useQuery({
    queryKey: ["home-examples", context.datasetId],
    queryFn: ({ signal }) =>
      catalogApi.examples(context.datasetId, { page: 1, pageSize: 8 }, signal),
    enabled: context.enabled && context.dataset?.status === "ACTIVE",
  });

  useEffect(() => {
    setQuestion("");
  }, [context.warehouseId, context.datasetId]);

  const activeWarehouseCount =
    warehouses.data?.items.filter((warehouse) => warehouse.status === "ACTIVE")
      .length ?? 0;

  return (
    <>
      <PageHeading
        title={
          <>
            Trợ lý phân tích dữ liệu bằng <em>ngôn ngữ tự nhiên</em>
          </>
        }
        description="Đặt câu hỏi bằng tiếng Việt hoặc tiếng Anh, chọn warehouse và dataset bạn được cấp quyền, rồi xem kết quả truy vấn thực tế."
        art={<Artwork />}
      />

      <form
        className="home-query card"
        onSubmit={(event) => {
          event.preventDefault();
          if (question.trim()) {
            navigate("/query", { state: { question: question.trim() } });
          }
        }}
      >
        <span className="icon-tile small" aria-hidden="true">
          <MessageCircle size={23} />
        </span>
        <input
          aria-label="Câu hỏi nhanh"
          value={question}
          maxLength={1000}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Bạn muốn tìm hiểu điều gì từ dữ liệu của mình?"
        />
        <Button type="submit" variant="primary" disabled={!question.trim()}>
          <Send size={18} />
          Bắt đầu truy vấn
        </Button>
      </form>

      {examples.data?.items.length ? (
        <div className="suggestions" aria-label="Câu hỏi mẫu">
          <strong>Thử ngay:</strong>
          {examples.data.items.slice(0, 4).map((example, index) => (
            <button
              className="chip"
              key={`${example.question}-${index}`}
              onClick={() => {
                navigate("/query", {
                  state: { question: example.question },
                });
              }}
            >
              {example.question}
              <ArrowRight size={15} />
            </button>
          ))}
        </div>
      ) : null}

      <div className="stats-grid three">
        <Stat
          icon={<Database />}
          label="Warehouses được cấp quyền"
          value={warehouses.data?.totalItems}
          note="Danh sách tải từ Java API"
        />
        <Stat
          icon={<Database />}
          label="Warehouse đang hoạt động"
          value={warehouses.data ? activeWarehouseCount : undefined}
          note="Theo trạng thái kết nối được đăng ký"
          tone="green"
        />
        <Stat
          icon={<Layers3 />}
          label="Datasets được cấp quyền"
          value={datasets.data?.totalItems}
          note="Phạm vi hiển thị theo tài khoản hiện tại"
          tone="purple"
        />
      </div>

      <div className="content-grid equal">
        <Card title="Warehouse của bạn" icon={<Database size={22} />}>
          <Resource
            resource={warehouses}
            empty={
              <Empty
                title="Chưa có warehouse được cấp quyền"
                description="Hãy kiểm tra quyền truy cập hoặc đăng ký warehouse trong cấu hình hệ thống."
              />
            }
          >
            {(page) =>
              page.items.length ? (
                <div className="warehouse-cards">
                  {page.items.map((warehouse) => (
                    <article
                      className="warehouse-card"
                      key={warehouse.warehouseId}
                    >
                      <div className="inline-actions">
                        <span className="icon-tile">
                          <Database size={25} />
                        </span>
                        <div>
                          <h3>{warehouse.name}</h3>
                          <Badge status={warehouse.status} />
                        </div>
                      </div>
                      <p>{engineLabel[warehouse.engine]}</p>
                      <div className="capabilities">
                        <span>Metadata discovery</span>
                        <strong>
                          {warehouse.capabilities.metadataDiscovery
                            ? "Có"
                            : "Không"}
                        </strong>
                      </div>
                      <Button
                        variant={
                          warehouse.warehouseId === context.warehouseId
                            ? "primary"
                            : "secondary"
                        }
                        disabled={warehouse.status !== "ACTIVE"}
                        onClick={() => {
                          context.selectWarehouse(warehouse);
                          navigate("/query");
                        }}
                      >
                        {warehouse.warehouseId === context.warehouseId
                          ? "Đang chọn"
                          : "Chọn warehouse"}
                        <ArrowRight size={16} />
                      </Button>
                    </article>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Chưa có warehouse được cấp quyền"
                  description="Hãy kiểm tra quyền truy cập hoặc cấu hình warehouse của hệ thống."
                />
              )
            }
          </Resource>
        </Card>

        <Card
          title="Dataset đang chọn"
          icon={<Layers3 size={22} />}
          actions={
            <Link className="text-link" to="/query">
              Mở truy vấn
              <ArrowRight size={16} />
            </Link>
          }
        >
          {context.dataset ? (
            <div className="selected-dataset">
              <div className="inline-actions">
                <span className="icon-tile large">
                  <Database size={30} />
                </span>
                <div>
                  <h3>{context.dataset.name}</h3>
                  <Badge status={context.dataset.status} />
                </div>
              </div>
              <p className="muted">
                {context.dataset.description ||
                  "Dataset đã đăng ký cho warehouse đang chọn."}
              </p>
              <div className="dataset-facts">
                <span>Warehouse</span>
                <strong>{context.warehouse?.name || "—"}</strong>
                <span>Hệ quản trị</span>
                <strong>
                  {context.warehouse
                    ? engineLabel[context.warehouse.engine]
                    : "—"}
                </strong>
                <span>Bảng trong phạm vi</span>
                <strong>{context.dataset.allowedTables.length}</strong>
                <span>Schema version</span>
                <strong>{context.dataset.schemaVersion}</strong>
              </div>
            </div>
          ) : (
            <Empty
              title="Chưa chọn dataset"
              description="Chọn warehouse và dataset từ thanh trên cùng để tải câu hỏi mẫu theo ngữ cảnh thật."
            />
          )}
        </Card>
      </div>

      <Card
        title={`Câu hỏi mẫu${context.dataset ? ` · ${context.dataset.name}` : ""}`}
        icon={<Lightbulb size={22} />}
      >
        {context.enabled && context.dataset?.status === "ACTIVE" ? (
          <Resource
            resource={examples}
            empty={
              <Empty
                title="Chưa có câu hỏi mẫu"
                description="Dataset hiện chưa có ví dụ câu hỏi được cấu hình. Bạn vẫn có thể nhập câu hỏi ở màn Truy vấn."
              />
            }
          >
            {(page) =>
              page.items.length ? (
                <div className="home-example-list">
                  {page.items.map((example, index) => (
                    <button
                      className="home-example"
                      key={`${example.question}-${index}`}
                      onClick={() =>
                        navigate("/query", {
                          state: { question: example.question },
                        })
                      }
                    >
                      <span className="icon-tile small">
                        <Lightbulb size={19} />
                      </span>
                      <span>
                        <strong>{example.category || "Câu hỏi mẫu"}</strong>
                        <small>{example.question}</small>
                      </span>
                      <ArrowRight size={17} />
                    </button>
                  ))}
                </div>
              ) : (
                <Empty
                  title="Chưa có câu hỏi mẫu"
                  description="Dataset hiện chưa có ví dụ câu hỏi được cấu hình. Bạn vẫn có thể nhập câu hỏi ở màn Truy vấn."
                />
              )
            }
          </Resource>
        ) : (
          <Empty
            title="Chọn dataset đang hoạt động"
            description="Danh sách câu hỏi mẫu được tải từ cấu hình của dataset, không dùng câu hỏi giả trong giao diện."
          />
        )}
      </Card>
    </>
  );
}
