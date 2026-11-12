# OpsPilot（Java 版控制面）

企业采购到付款系统：采购申请 → 审批 → 采购订单 → 分批收货 → 发票登记（三单匹配）→ 付款单 → 付款执行。
付款执行是一套**可审计、可审批、不会重复付款、崩溃后能接着跑**的引擎。

外部财务系统（真正付款的地方）用 Python 模拟服务（`mock_apps/finance`，沿用我之前 Python 版 OpsPilot 的代码）。

## 流程

业务单据：

```
供应商
  │
采购申请 ──审批──▶ 采购订单 ──▶ 收货（可分批） ──▶ 发票登记 ──三单匹配通过──▶ 付款单 ──发起──▶ 付款执行
                                                       └──有差异──▶ 差异单 ──接受 / 补收货后重新匹配──▶ 付款单
```

付款执行（每张付款单一次运行）：

```
发起付款 ──▶ 运行 QUEUED ──start──▶ 取付款依据 ──▶ 取发票 ──▶ 复核供应商/金额一致、供应商未冻结
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
| 并发收货超收 | 先锁订单（同一订单串行），每行条件递增 `received_qty + ? <= quantity` |
| 同一批货被开两次票 | 开票占用同样先锁订单、条件递增 `invoiced_qty + ? <= received_qty`，抢不到的发票进差异单 |
| 发票重复登记 / 一张发票两张付款单 | 唯一索引 (supplier_id, invoice_number)、payment_order.invoice_id |
| 同一张付款单被发起多次 | 创建运行时锁付款单、确认 PENDING，同一个事务里改成 EXECUTING |
| 业务单号重号 | `UPDATE ... SET value = LAST_INSERT_ID(value + 1)` 原子取号；建行放独立短事务，避开插入死锁和间隙锁 |
| 付款结果回写 | 运行进入终态时同步发布事件，监听者在同一个事务里把付款单、发票、订单改成对应状态 |
| 状态被并发修改 | 所有状态转移都是 `UPDATE ... WHERE id = ? AND status = ?` 条件更新；状态机在 `RunStatus` 里检查转移是否合法 |
| 多人同时审批 | 审批单条件更新 `WHERE status = 'PENDING'`，和运行状态转移、事件在同一个事务；重复同样决定返回 `ALREADY_RESOLVED`，相反决定返回 409 |
| 同一笔付款提交多次 | 幂等记录表：主键插入抢占 → 租约过期才能被回收（条件更新）→ 已完成直接返回结果 |
| 付款请求超时，不知道付没付 | 不重试、不释放；下次先按业务键去财务系统**对账**，查到了就直接完成 |
| 事件序号 | `UPDATE event_sequence = event_sequence + 1` 拿行锁分配序号，连续不重复 |
| 远程调用拖住数据库 | HTTP 调用全部在事务外，事务里只做状态转移和写事件 |
| 进程崩溃，运行卡住 | 执行租约（行级、状态转移即续租）+ 恢复扫描自动接手；启动时释放本实例上次留下的租约 |
| 多实例重复执行 | 恢复扫描用 `FOR UPDATE SKIP LOCKED` 批量领取；状态写入带 `lease_owner` 校验（fencing） |
| 外部系统暂时不可用 | 指数退避，`retry_at` / `retry_count` 持久化在库里，重启不丢 |
| 状态改了，派发消息却丢了 / 发了 | 事务性发件箱：派发消息和状态变化同一个事务写入，提交后由投递器投到 RabbitMQ |
| 多个投递器重复投递 | 投递器用 `FOR UPDATE SKIP LOCKED` 领取，领取后推后 `next_attempt_at`；broker 确认（publisher confirm）后才标记已投递 |
| MQ 重复投递 | 至少一次投递；消费端先抢执行租约，抢不到就跳过；坏消息进死信队列 |
| 负载集中在收到请求的实例 | 消费者 prefetch=1、执行完才 ack，所有实例按处理能力一起消费 |
| MQ 挂了 | 消息留在发件箱里退避重试，运行由恢复扫描照样执行；RabbitMQ 不影响健康检查 |

设计说明和面试问答：[`docs/P1-控制面.md`](docs/P1-控制面.md)、[`docs/P2-故障恢复.md`](docs/P2-故障恢复.md)、[`docs/P3-消息队列.md`](docs/P3-消息队列.md)、[`docs/P4-业务主干设计.md`](docs/P4-业务主干设计.md)。

## 运行

```powershell
docker compose up -d --build
.\scripts\demo.ps1            # 采购到付款全流程 + 故障注入（响应丢失、付款后进程崩溃）+ 双实例 + RabbitMQ 停机
```

- 接口文档（OpenAPI JSON）：http://127.0.0.1:8090/v3/api-docs
- MySQL：`127.0.0.1:3326`（opspilot / opspilot123）
- RabbitMQ 管理界面：http://127.0.0.1:15673（opspilot / opspilot123）
- 第二个实例（演示多实例）：`docker compose --profile multi up -d opspilot-2`，端口 8091

### 接口

```
POST /suppliers                     供应商；/suppliers/{id}/freeze | /unfreeze
POST /purchase-requests             采购申请（草稿）；/{id}/submit | /approve | /reject | /cancel
POST /purchase-requests/{id}/order  转采购订单
GET  /purchase-orders/{id 或单号}    订单详情（明细、已收、已开票、收货记录）
POST /purchase-orders/{id}/receipts 收货（可分批）
POST /invoices                      登记发票 + 三单匹配，返回 PAYABLE 或 EXCEPTION
POST /invoices/{id}/rematch         补收货后重新匹配
GET  /match-exceptions              待处理差异；/{id}/accept | /reject
GET  /payment-orders                付款单；/{id}/pay 发起付款，/{id}/retry 失败后重新发起
POST /tasks                         快捷入口 {"request": "帮我处理 PO-10001 的付款"}（找待付款的付款单）
POST /runs/{id}/start               启动（202，后台执行）
POST /runs/{id}/resume              不等退避时间，马上重试
GET  /runs?status=&size=            列表
GET  /runs/{id}                     详情
GET  /runs/{id}/events?after=N      时间线（只返回序号 > N 的事件）
GET  /approvals?status=PENDING      审批列表
POST /approvals/{id}/decision       {"decision": "approve|reject", "actor": "finance-lead"}
POST /chaos/drop-submit-response    故障注入：下一次付款响应丢弃（CHAOS_ENABLED=true 时可用）
POST /chaos/crash-after-submit      故障注入：下一次付款成功后进程立即退出
GET  /outbox/stats                  发件箱各状态消息数、本实例消费统计
```

### 测试

单元测试不需要数据库；并发和流程测试需要真实 MySQL，RabbitMQ 的测试还需要 RabbitMQ。
先把控制面停掉，只留 MySQL 和 RabbitMQ：控制面在跑的话，它的恢复扫描和消费者会去抢测试造的运行。

```powershell
docker compose stop opspilot opspilot-2
docker compose up -d mysql rabbitmq
docker run --rm --network opspilot_default `
  -e IT_MYSQL_URL=jdbc:mysql://mysql:3306/opspilot `
  -e IT_RABBIT_HOST=rabbitmq `
  -v "${PWD}:/workspace" -v opspilot-m2:/root/.m2 -w /workspace `
  maven:3.9.9-eclipse-temurin-21 mvn test
```

## 技术栈

Java 21 · Spring Boot 3.5 · MyBatis-Plus · MySQL 8.4 · Flyway · RabbitMQ 3.13 · Docker Compose

## 路线

- [x] P1 控制面：状态机、审批事务、付款幂等与对账、事件时间线
- [x] P2 异步执行 + 故障恢复 + 多实例（租约、`SKIP LOCKED`、fencing、退避重试）
- [x] P3 事务性发件箱 + RabbitMQ 派发（publisher confirm、消费端去重、死信队列、MQ 故障降级）
- [x] P4 采购到付款业务主干（[设计](docs/P4-业务主干设计.md)）
  - [x] 第一步：供应商、采购申请、采购订单、分批收货、业务单号
  - [x] 第二步：发票登记、三单匹配、差异单、付款单接入执行引擎
- [ ] P5 可配置审批流 + 部门预算（冻结 / 扣减 / 释放，防超支）
- [ ] P6 角色与数据权限 + Vue 管理台
- [ ] P7 每日对账
- [ ] P8 压测与监控（Micrometer + Prometheus、JMeter）
