# 从订单到客户、指标和决策计划：合成血缘实验

本实验已在用户授权的远端 GPU 节点实际运行。计算使用该节点的 CPU 和 SQLite
关系数据库；**没有使用 GPU 加速**，也没有接入真实企业数据。节点连接信息与私有
目录保存在本地个人协作资料，不进入公开仓库。

| 层级 | 实际产生了什么 | 独立检查 |
|---|---|---|
| L0 原始输入 | 固定种子的25万条订单、1万名合成客户 | 输入文件散列与客户编号引用 |
| L1 业务实体 | 按明确客户编号关联，生成1万条客户汇总 | 另一个只读程序逐客户重算订单数、净额与地区 |
| L2 分析指标 | 28天的订单数、客户数、支付、退款、净额与延迟订单数 | 不使用流水线 SQL，按 CSV 逐日重算全部字段 |
| L3 决策计划 | 28条“核查履约”的候选计划 | 核对计划日期、触发阈值和引用的指标；未执行任何业务动作 |

净额为23,975,487,278分，金额使用整数分避免浮点误差。实验假设延迟订单超过
10%时生成核查计划，这个阈值只是合成规则，未经业务专家确认。它不能证明真实
客户身份识别能力、真实经营收益、GPU 加速效果或企业级容量。

执行限制为2个 CPU 核、低优先级，峰值内存约58MiB；没有使用 sudo、容器或停止
已有推理任务。原始 CSV、客户级派生 CSV 和 SQLite 文件留在远端私有目录，不下载、
不进入 Git，也没有自动导入产品的 USER 来源。

## 固定证据

审查过的[实验清单](../../docs/verification/lineage-synthetic-20261025/manifest-index.json)
固定七份原始说明：输入、方法与环境、假设、输出、独立检查、限制和实际执行血缘。
索引 SHA-256 为 `2bff8d1e127a30870df63d6dc0d0d40674152e5e216717a9335297d02ef3752b`。

生成程序 SHA-256 为 `8cd898fee66fa8f81a78409c5a4b46364ea6c78f64db95109035478c4271f885`。
另有[第二个只读验证程序的实跑结果](../../docs/verification/lineage-synthetic-20261025/run-001-independent-check.json)
和[资源协调记录](../../docs/verification/lineage-synthetic-20261025/coordination.json)；这两份
补充记录不改写原始索引。第二个程序只读取输入、输出 CSV 与散列，不执行流水线
SQL、不读取数据库结果来生成期望值；逐客户、逐日和逐计划检查全部通过。

另外，[数据库引擎的字段读取审计](../../docs/verification/lineage-synthetic-20261025/run-001-sql-lineage-audit.json)
已在同一远端实际通过：对冻结 SQL 仅编译查询计划，使用 SQLite 的
[编译期回调](https://www.sqlite.org/c3ref/set_authorizer.html)记录真正引用的表与列，
核对三段 SQL 的来源声明完全匹配。数据库以只读方式打开，不重新执行流水线、
不读取结果行，前后文件指纹相同。这能证明查询引用了哪些列，仍不冒称已经自动
推导出每个输出字段对应的完整转换表达式。

执行血缘记录只覆盖本脚本真正执行的三段 SQL。它没有分析任何外部系统，也没有
声称发现了真实 ERP 的字段转换。产品在线图仍只展示其实际盘点的元数据与人工
来源声明；本实验的已生成演示产物与在线声明不能混为一谈。

## 在另一个获授权节点复现

先检查已有任务、可用 CPU、内存和磁盘；创建新的私有实验目录，把本目录三个
Python 文件复制进去。使用 Python 3.12 和 SQLite 3.45 或记录另一环境的实际版本。
下面命令必须在远端执行，不在本地工作机运行数据生成：

```sh
umask 077
: "${EXPERIMENT_CPUS:?先填写经检查可用的两个CPU编号}"
taskset -c "$EXPERIMENT_CPUS" nice -n 15 python3 generate_synthetic_lineage.py --orders 250000 --output run-001
taskset -c "$EXPERIMENT_CPUS" nice -n 15 python3 verify_synthetic_lineage.py run-001
taskset -c "$EXPERIMENT_CPUS" nice -n 15 python3 verify_sql_read_lineage.py run-001
```

生成程序拒绝覆盖已经存在的运行目录。`nvidia-smi` 只记录硬件名称和总显存，不读
环境变量或其他项目数据。只把人工审查过的清单、统计、散列和验证报告带回本地。
