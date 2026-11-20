# 海上风电故障码参考数据映射与诊断复现服务

把控制器告警、维保商工单术语和备件目录转成**可复演、可解释、可追溯**的故障诊断事实。

服务使用 **Java 21 + Spring Boot 3.5 + PostgreSQL 18**，把"来源码 / 统一概念 / 处置建议"分列建模，
并提供**两套并行的历史模型**：

| 模型 | 表 | 适用 | 代价 |
| --- | --- | --- | --- |
| **A. 有效期断言**（time-validity assertions） | `mapping_assertion` | 目录修订频繁、追溯修订少、要求灵活 | 查询时需按告警属性求当时适用关系，可能评估更多重叠断言 |
| **B. 发布快照**（release snapshot） | `release*` / `release_*` | 审计要求直接复演、吞吐稳定 | 每次发布冻结全量内容，发布与存储成本更高 |

两种模型都保证：结论可回查到**原始来源码、概念定义版本、审批记录**；且**都不会只保留当前码表**。

---

## 1. 关键业务语义（已实现并被测试覆盖）

1. **码表、概念、处置分列**：`source_code` / `term_attachment` → `mapping_assertion` → `concept` → `disposition_option`。
   一个来源码可在不同机型/固件下对应不同概念；一个概念可因库存与兼容条件产生不同可用建议。
2. **相同来源码收到相反映射时保留冲突**：两条 `ACTIVE` 断言并存，诊断返回 `CONFLICT`，**不静默取一**。
   只有管理员提交的**工程师裁定**（`mapping_adjudication`，带有效期与审批）才会显式打破平局，且不改写原断言。
3. **离线补传按发生时点解释**：解释时刻恒为 `occurredAt`；`receivedAt` 只记录迟到事实，
   `OFFLINE_BACKFILL` 只是传输属性，不改变语义。
4. **诊断运行中目录更新不得混入同一批结果**：每次运行在**评估第一个告警之前**固定一对
   catalog + inventory 快照，之后所有结果都引用它。
5. **互斥处置 → 结论待工程师裁定**：同一 `exclusive_group` 内有两个及以上可用选项时，结论为 `PENDING_ENGINEER`；
   不可用选项显式列在 `unavailable` 中，而不是被悄悄丢弃。
6. **固件/机型差异**：断言可按 `manufacturer / model / firmware 序号区间 / qualifiers` 限定；
   解析采用"最具体作用域优先"（机型 > 厂商 > 全局，固件区间与属性加分），其余低优先级候选被记录为 `suppressedBySpecificity`。

诊断结论状态：`RESOLVED` / `CONFLICT` / `PENDING_ENGINEER` / `NO_MAPPING` / `NO_DISPOSITION`。

7. **停用备件的替代链沿图前向搜索，历史与当前建议严格分时**：旧齿轮箱 `GBX-100` 停用后，目录给出两条替代——
   `GBX-200`（仅低温机组可用，温度区间外硬失败）与 `GBX-300`（需把 IF-A 接口改装为 IF-B，恒为待工程确认）。
   系统**沿备件替代图从历史建议逐边前向搜索**，逐边校验机型、温度、接口、有效期与库存，并返回完整路径；
   不预计算概念→备件闭包，适配目录频繁变动（取舍见第 7 节）。
   - **历史不倒灌**：历史工单当时的推荐存在不可变的 `work_order_part_recommendation`，当前可执行替代是独立对象，
     永远不会回写历史。
   - **循环即冲突**：`GBX-400 ↔ GBX-500` 互相指向时返回 `CYCLE` 冲突路径（如 `GBX-400 -> GBX-500 -> GBX-400`），不静默选边。
   - **库存竞争归仓储**：`CLAIM_PENDING` 时诊断只把建议标为 `PENDING_CONFIRM`；仓储 `CONFIRMED_ALLOCATED` 后
     该建议才 `BLOCKED`，诊断自身从不做库存划拨决定。
   - **温度缺失是显式边界**：替代边带温度条件而现场未提供环境温度时，边失败原因为 `AMBIENT_TEMP_MISSING`，
     同时进入概念级 `notSubstitutableReasons`，不猜温度。
   - 路径状态：`EXECUTABLE`（当前可领用）/ `PENDING_CONFIRM`（待工程或仓储确认）/ `BLOCKED`（不可替代，附原因）/ `CYCLE`（循环冲突）。

---

## 2. 快速启动

### 2.1 Docker Compose（一键，含数据库与脱敏演示数据）

```bash
docker compose up --build
# 应用:      http://localhost:8080
# Swagger:   http://localhost:8080/swagger-ui.html
# OpenAPI:   http://localhost:8080/v3/api-docs
```

首次启动会自动执行 Flyway 建表并灌入演示数据（`APP_DEMO_ENABLED=true`）。

### 2.2 本地开发（使用容器内已预装的 PostgreSQL）

```bash
# 1) 建库（本机 PostgreSQL 18 已在 127.0.0.1:5432，账号 ccdev）
createdb -h 127.0.0.1 -U ccdev faultref        # 若不存在
# 2) 构建并启动
DB_URL=jdbc:postgresql://127.0.0.1:5432/faultref \
  /opt/maven/bin/mvn spring-boot:run
```

关键环境变量：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` | 数据库连接 |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` | 数据库账号 |
| `APP_SECURITY_ENABLED` | `true` | 关闭后所有接口开放（仅本地调试） |
| `APP_DEMO_ENABLED` | `true` | 启动时灌入演示数据（仅在库为空时生效） |
| `APP_DIAGNOSIS_MAX_BATCH` | `5000` | 单次诊断最大告警数 |

---

## 3. 测试账号（HTTP Basic）

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 只读 |
| `engineer` | `engineer123` | ENGINEER | 只读 + 录入 + 运行诊断 |
| `admin` | `admin123` | ADMIN | 全部（含审批、裁定、发布 release） |

> 账号为演示用内存账号，生产部署请替换 `SecurityConfig` 中的 `UserDetailsService`。

---

## 4. 演示数据（覆盖题目要求的两类机组、目录、固件变更、离线补传）

- **两类机组**：NordWind `NW-8`（北区 A01/A02）与海鑫 `HX-6`（东区 B01/B02）。
- **相同液压异常、不同厂商代码不同**：`NORDWIND_CTRL/HYD-201` 与 `HAIXIN_CTRL/E4410` 都映射到"液压系统压力低"。
- **同码因固件改义**：`HYD-201` 在 `NW-8 V2.1` 起细分映射为"液压泵磨损"（`HYD_PUMP_WEAR`），此前为"液压系统压力低"。
- **维保商术语混称**：`振动超限` 同时映射到"齿轮箱振动超限"与"叶片不平衡" → **保留冲突**；
  仅对 `NW-8` 做了工程师裁定，`HX-6` 仍为 `CONFLICT`。
- **来源码相反映射**：`HAIXIN_CTRL/E5510` 两条 `ACTIVE` 断言互相矛盾 → 保留。
- **离线补传**：`ALT-NW-A01-20240115` 发生于 2024-01-15（当时 V1.0），2025-04-12 才上传 → 仍按 2024 时点解释。
- **目录演化**：`CAT-2023.01 → CAT-2025.01 → CAT-2025.02`，配合 `INV-*` 库存快照，
  使同一概念在不同时点分别得到"确认可用 / 互斥待裁定 / 无可用件"。
- **发布快照**：`REL-2025.01`、`REL-2025.02` 冻结全量内容并带 SHA-256 内容哈希。
- **齿轮箱替代链验收集**（`GEARBOX_REPLACEMENT` 概念 + `GBX-*` 零件族）：
  - `GBX-100` 在 `CAT-2025.02` 显式 `OBSOLETE`（`part_lifecycle` 记录停用原因与指定后继 `GBX-200`）。
  - 替代边：`GBX-100→GBX-200`（CONDITIONAL，环境温度 ≤ -10°C）、`GBX-100→GBX-300`（RETROFIT，IF-B 接口 + 工程确认）、
    `GBX-100→GBX-600`（常温通用）、`GBX-100→GBX-400→GBX-500→GBX-400`（目录维护错误造成的循环）。
  - 三条齿轮箱告警：`ALT-NW-A01-20250620-GBX`（-25°C 低温）、`ALT-NW-A02-20250621-GBX`（18°C 温暖）、
    `ALT-NW-A02-20250622-GBX`（温度属性缺失）。
  - 历史工单 `WO-2023-0510-NW` 保留 2023 年当时的 `GBX-100` 推荐（ISSUED，CAT-2023.01）。
  - 库存争用：`INV-2025-05-10` 对最后一台 `GBX-600` 记 `CLAIM_PENDING`；`INV-2025-06-01` 仓储确认
    `CONFIRMED_ALLOCATED` 给竞争工单 `WO-2025-0520-NW`。

---

## 5. 验证方式

### 5.1 自动化测试

```bash
# 使用独立测试库 faultref_test（不存在时先创建）
createdb -h 127.0.0.1 -U ccdev faultref_test
/opt/maven/bin/mvn test
```

覆盖 31 个用例：

- `DiagnosisScenarioTest`（10）：固件改义、离线补传、迟到但非补传、保留冲突、作用域优先、
  工程师裁定、互斥处置、库存不足、运行期目录固定、同码不同机型。
- `ReleaseSnapshotTest`（6）：冻结与审批回链、发布后目录变更不影响历史复演、草稿不可复演、
  模式/口径校验、内容哈希确定性、发布后禁止再改目录。
- `ApiContractTest`（8）：未认证 401、角色 403、校验错误结构、OpenAPI 公开、
  HTTP 运行诊断、补传时间校验。
- `SubstitutionScenarioTest`（7）：停用型号的低温/改装双替代、温暖环境低温边失败、
  温度缺失显式边界、替代循环冲突路径、库存争用（CLAIM_PENDING 只标记 / CONFIRMED_ALLOCATED 阻断）、
  历史工单推荐不可变且与当前替代分离、release 冻结表上的 SNAPSHOT 替代复演。

### 5.2 端到端脚本（对着已启动的服务）

```bash
./scripts/verify.sh                       # 30 项断言（基线诊断/映射/发布能力）
./scripts/verify-substitution.sh          # 21 项断言（备件替代链验收集）
# 或指定地址
BASE_URL=http://127.0.0.1:8080 ./scripts/verify-substitution.sh
```

覆盖：健康/OpenAPI、鉴权与角色、两类机型、同码改义、发生时点语义、保留冲突、可追溯性、
处置边界、目录固定、发布冻结与哈希、统计口径；以及停用型号、温度条件缺失、循环替代、
最后库存争用、历史工单推荐保留与 release 冻结复演。

---

## 6. 主要 API

完整文档见 `http://localhost:8080/swagger-ui.html`。核心端点：

**参考数据**
```
POST /api/reference/manufacturers | /models | /firmware | /units
POST /api/reference/source-systems | /source-codes | /terms
POST /api/reference/concepts            创建概念（含 v1 定义）
POST /api/reference/concepts/{code}/definitions   追加已批准定义版本
GET  /api/reference/concepts/{code}      概念及定义历史
```

**映射断言（模型 A）**
```
POST /api/mappings/assertions                    新建断言（时间有效，不覆盖历史）
POST /api/mappings/assertions/{id}/close         撤回/废止并关闭有效期
GET  /api/mappings/source-codes/{id}/history     某来源码的全部断言与裁定
GET  /api/mappings/terms/{id}/history
GET  /api/mappings/conflicts                     当前保留冲突列表
POST /api/mappings/conflicts/{key}/adjudications 工程师裁定（admin）
```

**目录 / 库存 / 处置**
```
POST /api/catalog/versions ， POST /api/catalog/versions/{label}/publish
POST /api/catalog/parts | /entries | /dispositions
POST /api/catalog/inventory-snapshots
GET  /api/catalog/versions/{label}/dispositions
```

**证据（告警 / 工单）**
```
POST /api/evidence/alarms         occurredAt 决定解释时点；receivedAt 仅记录迟到
POST /api/evidence/work-orders
GET  /api/evidence/alarms | /work-orders | /work-orders/{id}
```

**诊断与复演**
```
POST /api/diagnosis/runs                         运行诊断
GET  /api/diagnosis/runs                         运行列表
GET  /api/diagnosis/runs/{id}                    结果与 findings
GET  /api/diagnosis/runs/{id}/statistics         统计与边界口径
GET  /api/diagnosis/runs/{left}/diff/{right}     两次运行逐告警差异
GET  /api/diagnosis/results/{id}/explain         回查原始来源/定义/审批/工单
```

**发布快照（模型 B）**
```
POST /api/releases                               创建草稿（绑定已发布目录 + 库存快照）
POST /api/releases/{label}/publish               冻结并计算内容哈希
GET  /api/releases | /{label} | /{label}/content
POST /api/releases/{label}/retire
```

**备件替代链（本次新增）**
```
POST /api/substitutions/edges                              新建带条件的替代边（仅 DRAFT 目录）
POST /api/substitutions/edges/{id}/retract                 撤回替代边（历史行保留）
GET  /api/substitutions/catalogs/{label}/edges             某目录的替代边
POST /api/substitutions/part-lifecycle                     零件停用/在役生命周期（OBSOLETE 需原因）
GET  /api/substitutions/catalogs/{label}/part-lifecycle
POST /api/substitutions/work-order-recommendations         记录历史工单的不可变推荐
GET  /api/substitutions/work-order-recommendations
POST /api/substitutions/stock-contentions                  仓储库存争用（CLAIM_PENDING/CONFIRMED_ALLOCATED/RELEASED）
GET  /api/substitutions/inventory-snapshots/{label}/stock-contentions
POST /api/substitutions/advice                             按设备/环境/工单时间/库存时点给出建议
GET  /api/substitutions/diagnosis/results/{id}/part-advice 某诊断结果各概念的备件建议
```

### 6.1 备件替代建议示例

```bash
# 低温机组：GBX-200 可执行、GBX-300 待工程确认、GBX-600 因仓储划拨阻断、GBX-400/500 循环冲突
curl -u engineer:engineer123 -X POST localhost:8080/api/substitutions/advice \
  -H 'Content-Type: application/json' -d '{
    "conceptCode":"GEARBOX_REPLACEMENT",
    "alarmExternalId":"ALT-NW-A01-20250620-GBX",
    "catalogVersionLabel":"CAT-2025.02",
    "inventorySnapshotLabel":"INV-2025-06-01"}'

# 历史工单视角：originalRecommendation 保留 2023 年的 GBX-100，currentAdvice 是今天的可执行替代
curl -u engineer:engineer123 -X POST localhost:8080/api/substitutions/advice \
  -H 'Content-Type: application/json' -d '{
    "conceptCode":"GEARBOX_REPLACEMENT",
    "workOrderExternalId":"WO-2023-0510-NW","unitCode":"NW-A01",
    "ambientTempC":-25,"interfaceCode":"IF-A",
    "occurredAt":"2023-05-10T00:00:00Z",
    "catalogVersionLabel":"CAT-2025.02",
    "inventorySnapshotLabel":"INV-2025-06-01"}'
```

请求中的四个时间/身份轴都是显式的：设备（`unitCode`/告警）、环境（`ambientTempC`、`interfaceCode`）、
工单时间（`occurredAt`，决定历史解释时点；替代边有效期按当前评估时点校验）、库存时点（库存快照标签）。

### 6.2 诊断运行示例

```bash
# 断言模式，按“最新已发布目录”评估所有告警
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' -d '{"mode":"ASSERTION","basis":"LATEST"}'

# 按业务时点复演（2024 年的补传告警按 2024 年的目录/映射解释）
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' \
  -d '{"mode":"ASSERTION","basis":"AS_OF","asOf":"2024-02-01T00:00:00Z",
       "from":"2024-01-01T00:00:00Z","to":"2024-02-01T00:00:00Z"}'

# 快照模式，直接复演某个已发布 release
curl -u engineer:engineer123 -X POST localhost:8080/api/diagnosis/runs \
  -H 'Content-Type: application/json' \
  -d '{"mode":"SNAPSHOT","basis":"RELEASE","releaseLabel":"REL-2025.01",
       "from":"2025-04-01T00:00:00Z","to":"2025-05-01T00:00:00Z"}'
```

> **`knowledgeCutoff`（可选）**：仅用于"当时我们已知什么"的审计场景，按**审批时间**过滤断言；
> 它默认不启用，以免误把历史业务时点当成知识上限。

---

## 7. 两种历史模型的取舍

- 选 **A（断言）**：目录/术语修订频繁，追溯修订少，追求写入灵活与存储经济；
  代价是诊断需按告警属性做区间与作用域求值，重叠断言多时查询更重。本服务默认模式。
- 选 **B（快照）**：审计复演要求"直接、与后续修订无关"，诊断吞吐需稳定；
  代价是每次发布冻结全量（`release_code_entry` 等），发布流程与存储更高。
- 二者可**并存**：日常用 A 评估，关键结论发布为 B 以供长期审计复演；`GET /runs/{l}/diff/{r}` 可直接对比两模型结论差异。

具体选择由**目录修订频率、诊断吞吐、审计复演需求**共同决定，服务不强制其一，也不只保留当前码表。

### 7.1 备件替代：沿图前向搜索 vs 预计算闭包

| 方案 | 本服务的选择 | 代价 |
| --- | --- | --- |
| **沿替代图前向搜索**（采用） | 边显式存储，查询时从历史建议逐边校验机型/温度/接口/有效期/库存，DFS 返回完整路径与循环冲突 | 循环与分支多时单次查询成本较高（实现中有深度封顶 `MAX_DEPTH=10`） |
| **发布时预计算概念→兼容备件闭包及条件索引** | 未采用 | 现场查询快，但库存或兼容规则修订时需失效和重建大量闭包结果，且难以原样展示替代路径 |

本场景目录频繁变动且要求逐边可解释，因此选前向搜索；边条件与每条 `EdgeCheck` 结果都随路径返回，解释深度完整。
无论哪种方案，都必须在**当前固定的目录+库存条件下**产生可执行建议——系统不会把某条边/某个零件固定为
“历史用途”、另一个固定为“当前用途”：历史用途由不可变的工单推荐表承载，当前用途由每次查询的快照求值承载，
两者是不同对象而非零件的固有标签。

---

## 8. 目录结构

```
src/main/java/com/windfarm/faultref/
  config/      SecurityConfig, OpenApiConfig
  domain/      (schema-first；实体以 SQL 表为准)
  repo/        ReferenceRepo, MappingRepo, CatalogRepo, EvidenceRepo, ReleaseRepo,
               DiagnosisRepo, SubstitutionRepo, Sql
  service/     ReferenceService, MappingService, CatalogService/CatalogAdminService,
               EvidenceService, DiagnosisService (引擎), DiagnosisQueryService (统计/差异/解释),
               ReleaseService, SubstitutionService (替代图遍历与分时建议)
  seed/        DemoDataSeeder（可复现的演示数据与预置运行）
  web/         7 个 REST 控制器 + dto/（含 SubstitutionController、SubstitutionDtos）
src/main/resources/db/migration/V1__schema.sql                 Flyway 基础建表
src/main/resources/db/migration/V2__substitution_chain.sql     替代边/生命周期/历史推荐/库存争用/发布冻结表
scripts/verify.sh                                端到端验证（30 项，基线能力）
scripts/verify-substitution.sh                  端到端验证（21 项，备件替代链）
Dockerfile / docker-compose.yml                  一键启动
```

---

## 9. 已执行的验证结论（如实记录）

- `mvn test`：31/31 通过（`DiagnosisScenarioTest` 10、`ReleaseSnapshotTest` 6、`ApiContractTest` 8、
  `SubstitutionScenarioTest` 7），运行于独立库 `faultref_test`，Flyway 从 V1、V2 两个迁移全新建表。
- `./scripts/verify.sh`：30/30 通过（对着本地启动的服务，基线能力无回归）。
- `./scripts/verify-substitution.sh`：21/21 通过（对着同一服务，覆盖停用型号、温度缺失、循环、
  库存争用、历史推荐保留、release 冻结与 SNAPSHOT 复演）。
- 手工 curl 复核的关键输出：
  - 低温告警（-25°C）下 `GBX-200` 为 `EXECUTABLE`、`GBX-300` 为 `PENDING_CONFIRM`（IF-B 改装 + 工程确认）、
    `GBX-600` 因仓储 `CONFIRMED_ALLOCATED` 为 `BLOCKED`，并返回 `GBX-400→GBX-500→GBX-400` 的 `CYCLE` 冲突路径；
  - 18°C 温暖环境下 `GBX-200` 边失败 `TEMP_ABOVE_MAXIMUM`；温度属性缺失时失败 `AMBIENT_TEMP_MISSING`
    且进入概念级 `notSubstitutableReasons`；
  - `INV-2025-05-10` 的 `CLAIM_PENDING` 只把 `GBX-600` 标为 `PENDING_CONFIRM`，仓储确认后才阻断；
  - 历史工单 `WO-2023-0510-NW` 的 `originalRecommendation` 仍是 2023 年 `GBX-100`（CAT-2023.01，ISSUED），
    当前替代在独立的 `currentAdvice` 中，未回写历史；
  - `REL-2025.02` 冻结了 6 条替代边、`GBX-100` 停用事实、历史推荐与库存争用，SNAPSHOT 诊断结果的
    part-advice 仅读 `release_*` 表即可复演；OpenAPI 3 文档包含全部 10 个新端点。
- Docker / Docker Compose 未在本容器内执行（容器无 Docker daemon）；`Dockerfile` 与 `docker-compose.yml`
  已提供且无需修改即可构建（V2 迁移随应用启动自动执行、演示数据自动播种），构建逻辑为 `mvn package` + Temurin 21 JRE 运行。
- 基线既有的关键输出仍成立：`HYD-201` 在 V2.1 解析为 `HYD_PUMP_WEAR`、V1.0 解析为 `HYD_LOW_PRESSURE`；
  2024 补传告警在 `AS_OF` 复演中仍为 `HYD_LOW_PRESSURE`；`振动超限` 在 `HX-6` 为 `CONFLICT`、
  在 `NW-8` 经裁定为 `GEARBOX_VIBRATION_HIGH`；2025-06 双泵库存下结论为 `PENDING_ENGINEER`。
