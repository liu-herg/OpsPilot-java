# OpsPilot（Java 版控制面）

企业流程自动化的控制面：把"帮我处理 PO-10001 的付款"这样的请求，变成一次**可审计、可审批、不会重复付款、崩溃后能接着跑**的执行。

本项目是我之前用 Python（FastAPI + LangGraph）写的 OpsPilot 的控制面 Java 重写版。三个被自动化的业务系统（采购 / 发票 / 财务）沿用原项目的 Python 模拟服务（`mock_apps/`）。

## 流程

```
POST /tasks ──▶ 运行 QUEUED ──start──▶ 查采购单 ──▶ 查发票 ──▶ 校验供应商/金额一致
                                                                  │
                         ┌──── 金额 ≤ 10000 ────────────────────────┤
                         ▼                                         └── 金额 > 10000 ──▶ 等待审批 ──驳回──▶ REJECTED
                  READY_TO_SUBMIT ◀──────────────────────────────────────────── 批准 ──┘
                         │ 幂等抢占 → 提交 → 按业务键回查核实
                         ▼
                     COMPLETED
```

## 重点

| 问题 | 做法 |
|---|---|
| 状态被并发修改 | 所有状态转移都是 `UPDATE ... WHERE id = ? AND status = ?` 条件更新；状态机在 `RunStatus` 里检查转移是否合法 |
| 多人同时审批 | 审批单条件更新 `WHERE status = 'PENDING'`，和运行状态转移、事件在同一个事务；重复同样决定返回 `ALREADY_RESOLVED`，相反决定返回 409 |
| 同一笔付款提交多次 | 幂等记录表：主键插入抢占 → 租约过期才能被回收（条件更新）→ 已完成直接返回结果 |
| 付款请求超时，不知道付没付 | 不重试、不释放；下次先按业务键去财务系统**对账**，查到了就直接完成 |
| 事件序号 | `UPDATE event_sequence = event_sequence + 1` 拿行锁分配序号，连续不重复 |
| 远程调用拖住数据库 | HTTP 调用全部在事务外，事务里只做状态转移和写事件 |

设计说明和面试问答见 [`docs/P1-控制面.md`](docs/P1-控制面.md)。

## 运行

```powershell
docker compose up -d --build
.\scripts\demo.ps1            # 三个业务场景 + 故障注入演示
```

- 接口文档（OpenAPI JSON）：http://127.0.0.1:8090/v3/api-docs
- MySQL：`127.0.0.1:3326`（opspilot / opspilot123）

### 接口

```
POST /tasks                         {"request": "帮我处理 PO-10001 的付款"}
POST /runs/{id}/start               执行
POST /runs/{id}/resume              继续提交停在 READY_TO_SUBMIT 的运行
GET  /runs?status=&size=            列表
GET  /runs/{id}                     详情
GET  /runs/{id}/events?after=N      时间线（只返回序号 > N 的事件）
GET  /approvals?status=PENDING      审批列表
POST /approvals/{id}/decision       {"decision": "approve|reject", "actor": "finance-lead"}
POST /chaos/drop-submit-response    故障注入：下一次付款响应丢弃（CHAOS_ENABLED=true 时可用）
```

### 测试

单元测试不需要数据库；并发和流程测试需要真实 MySQL（先 `docker compose up -d mysql`）：

```powershell
docker run --rm --network opspilot_default `
  -e IT_MYSQL_URL=jdbc:mysql://mysql:3306/opspilot `
  -v "${PWD}:/workspace" -v opspilot-m2:/root/.m2 -w /workspace `
  maven:3.9.9-eclipse-temurin-21 mvn test
```

## 技术栈

Java 21 · Spring Boot 3.5 · MyBatis-Plus · MySQL 8.4 · Flyway · Docker Compose

## 路线

- [x] P1 控制面：状态机、审批事务、付款幂等与对账、事件时间线
- [ ] P2 异步执行 + 启动恢复 + 多实例抢占（`SKIP LOCKED` / 租约）、事件实时推送
- [ ] P3 消息队列解耦执行器、浏览器自动化执行器（Playwright）、工作流版本
