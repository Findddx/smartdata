import { useState, type FormEvent } from "react";
import {
  ArrowRight,
  Braces,
  Database,
  Globe,
  LockKeyhole,
  Upload,
} from "lucide-react";
import { Dialog } from "./Dialog";
import type { SourceDraft, SourceKind, WorkspaceSource } from "./types";

const example =
  '[\n  { "order_id": "A-001", "amount": 128, "updated_at": "2026-10-25T08:00:00Z" },\n  { "order_id": "A-002", "amount": 256, "updated_at": "2026-10-25T09:00:00Z" }\n]';
export function SourceDialog({
  version,
  onClose,
  onSubmit,
  pending,
  error,
  replaceSource,
}: {
  version: number;
  onClose: () => void;
  onSubmit: (draft: SourceDraft) => void;
  pending: boolean;
  error?: string;
  replaceSource?: WorkspaceSource;
}) {
  const [kind, setKind] = useState<SourceKind>("JSON_SNAPSHOT");
  const [name, setName] = useState(replaceSource?.name ?? "");
  const [endpoint, setEndpoint] = useState("");
  const [credentialEnvRef, setCredentialEnvRef] = useState("");
  const [usernameEnvRef, setUsernameEnvRef] = useState("");
  const [observedAt, setObservedAt] = useState("");
  const [rows, setRows] = useState("");
  const [primaryKey, setPrimaryKey] = useState("");
  const [freshnessColumn, setFreshnessColumn] = useState("");
  const [maxHours, setMaxHours] = useState("24");
  const [schema, setSchema] = useState("public");
  const [localError, setLocalError] = useState("");
  function submit(event: FormEvent) {
    event.preventDefault();
    setLocalError("");
    try {
      const draft: SourceDraft = {
        expectedStateVersion: version,
        name: name.trim(),
        kind,
      };
      if (!draft.name) throw new Error("请先为这个来源起一个便于识别的名字。");
      if (kind === "JSON_SNAPSHOT") {
        const parsed: unknown = JSON.parse(rows);
        if (
          !Array.isArray(parsed) ||
          !parsed.length ||
          parsed.length > 1000 ||
          parsed.some(
            (row) => !row || typeof row !== "object" || Array.isArray(row),
          )
        )
          throw new Error("请提供 1–1,000 条 JSON 对象记录。");
        draft.snapshotRows = parsed as Record<string, unknown>[];
        if (!observedAt || Number.isNaN(new Date(observedAt).getTime()))
          throw new Error("请填写这份数据实际采集的时间。");
        draft.observedAt = new Date(observedAt).toISOString();
      } else {
        draft.endpoint = endpoint.trim();
        if (credentialEnvRef.trim())
          draft.credentialEnvRef = credentialEnvRef.trim();
      }
      if (kind === "JDBC_POSTGRES_READONLY") {
        draft.schema = schema.trim();
        draft.usernameEnvRef = usernameEnvRef.trim();
      }
      if (primaryKey.trim()) draft.primaryKey = primaryKey.trim();
      if (freshnessColumn.trim()) {
        draft.freshnessColumn = freshnessColumn.trim();
        draft.freshnessMaxHours = Number(maxHours);
      }
      onSubmit(draft);
    } catch (failure) {
      setLocalError(
        failure instanceof Error ? failure.message : "请检查输入内容。",
      );
    }
  }
  return (
    <Dialog
      title={replaceSource ? "更新来源快照" : "连接一个数据来源"}
      subtitle="从你授权的范围开始。先读取，再核实。"
      onClose={onClose}
      wide
    >
      <form className="sd-source-form" onSubmit={submit}>
        {!replaceSource && (
          <div className="sd-source-kinds" aria-label="选择连接方式">
            {(
              [
                {
                  id: "JSON_SNAPSHOT",
                  label: "数据文件",
                  hint: "导入 JSON",
                  icon: Braces,
                },
                {
                  id: "HTTP_JSON",
                  label: "业务 API",
                  hint: "只读 GET",
                  icon: Globe,
                },
                {
                  id: "JDBC_POSTGRES_READONLY",
                  label: "PostgreSQL",
                  hint: "只读盘点",
                  icon: Database,
                },
              ] as const
            ).map((item) => (
              <button
                type="button"
                key={item.id}
                className={kind === item.id ? "is-selected" : ""}
                onClick={() => setKind(item.id)}
                aria-pressed={kind === item.id}
              >
                <item.icon size={21} />
                <strong>{item.label}</strong>
                <small>{item.hint}</small>
              </button>
            ))}
          </div>
        )}
        <label>
          来源名称
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="例如：商城订单、仓储库存"
            maxLength={100}
            required
            autoFocus
            readOnly={!!replaceSource}
          />
        </label>
        {kind === "JSON_SNAPSHOT" ? (
          <>
            <div className="sd-field-heading">
              <span>JSON 记录</span>
              <label className="sd-file-control">
                <Upload size={13} />
                选择文件
                <input
                  type="file"
                  accept=".json,application/json"
                  onChange={async (event) => {
                    const file = event.target.files?.[0];
                    if (!file) return;
                    if (file.size > 200_000) {
                      setLocalError(
                        "文件超过 200 KB，请先导出一个有代表性的脱敏样本。",
                      );
                      return;
                    }
                    setRows(await file.text());
                    if (!name) setName(file.name.replace(/\.json$/, ""));
                  }}
                />
              </label>
            </div>
            <textarea
              aria-label="JSON 记录"
              className="sd-code-input"
              value={rows}
              onChange={(e) => setRows(e.target.value)}
              placeholder={example}
              rows={6}
              required
            />
            <p className="sd-field-help">
              数据保存在本地工作区。请使用脱敏数据；浏览器不会把原始记录发送给模型。
            </p>
            <label className="sd-observation-time">
              这份数据的实际采集时间
              <input
                type="datetime-local"
                value={observedAt}
                onChange={(event) => setObservedAt(event.target.value)}
                required
              />
              <span className="sd-field-help">
                按你的设备时区填写。上传时间与数据采集时间可能不同，不能混为一谈。
              </span>
            </label>
          </>
        ) : (
          <>
            <label>
              {kind === "HTTP_JSON" ? "API 地址" : "JDBC 地址"}
              <input
                value={endpoint}
                onChange={(e) => setEndpoint(e.target.value)}
                placeholder={
                  kind === "HTTP_JSON"
                    ? "https://api.your-company.com/orders"
                    : "jdbc:postgresql://db.your-company.com:5432/commerce"
                }
                required
              />
            </label>
            <label>
              凭证的环境变量名称
              <input
                value={credentialEnvRef}
                onChange={(e) => setCredentialEnvRef(e.target.value)}
                placeholder="例如 SMARTDATA_CONNECTOR_SHOP_TOKEN"
                pattern="SMARTDATA_CONNECTOR_[A-Z][A-Z0-9_]*"
                required={kind === "JDBC_POSTGRES_READONLY"}
              />
              <span className="sd-field-help">
                只填服务端环境变量的名字。不要把 API Key、密码或 Token
                填进这里。
              </span>
            </label>
            {kind === "JDBC_POSTGRES_READONLY" && (
              <>
                <label>
                  数据库用户名的环境变量名称
                  <input
                    value={usernameEnvRef}
                    onChange={(event) => setUsernameEnvRef(event.target.value)}
                    placeholder="SMARTDATA_CONNECTOR_SHOP_USER"
                    pattern="SMARTDATA_CONNECTOR_[A-Z][A-Z0-9_]*"
                    required
                  />
                </label>
                <label>
                  允许盘点的 Schema（数据表分组）
                  <input
                    value={schema}
                    onChange={(e) => setSchema(e.target.value)}
                    placeholder="public"
                  />
                </label>
              </>
            )}
            <p className="sd-source-notice">
              <LockKeyhole size={16} />
              连接目标需要已在服务端允许名单中。连接器不会执行模型生成的 SQL
              或修改来源数据。
            </p>
          </>
        )}
        {!replaceSource && (
          <details>
            <summary>检查规则 · 可选</summary>
            <div className="sd-form-pair">
              <label>
                唯一标识字段
                <input
                  value={primaryKey}
                  onChange={(e) => setPrimaryKey(e.target.value)}
                  placeholder="order_id"
                />
              </label>
              <label>
                更新时间字段
                <input
                  value={freshnessColumn}
                  onChange={(e) => setFreshnessColumn(e.target.value)}
                  placeholder="updated_at"
                />
              </label>
            </div>
            <label>
              允许多久未更新（小时）
              <input
                type="number"
                min="1"
                max="8760"
                value={maxHours}
                onChange={(e) => setMaxHours(e.target.value)}
              />
            </label>
          </details>
        )}
        {(localError || error) && (
          <p className="sd-error" role="alert">
            {localError || error}
          </p>
        )}
        <footer>
          <span>
            <LockKeyhole size={13} />
            默认只读
          </span>
          <button
            type="submit"
            className="sd-button sd-primary"
            disabled={pending}
          >
            {pending
              ? "正在保存来源…"
              : replaceSource
                ? "更新快照并重新盘点"
                : "保存并开始盘点"}
            <ArrowRight size={15} />
          </button>
        </footer>
      </form>
    </Dialog>
  );
}
