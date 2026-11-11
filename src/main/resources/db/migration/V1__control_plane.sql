-- OpsPilot 控制面：任务、运行、事件、审批、付款幂等
-- 工作流版本、轨迹、Agent 调用记录等表随后续功能加入（V2、V3…）。

-- 任务：用户提交的一句话请求，例如"帮我处理 PO-10001 的付款"
CREATE TABLE task (
    id         VARCHAR(40)  NOT NULL,
    request    TEXT         NOT NULL,
    task_type  VARCHAR(80)  NOT NULL,
    po_number  VARCHAR(40)  NOT NULL,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='任务';

-- 运行：一个任务的一次执行。状态机见 RunStatus
CREATE TABLE workflow_run (
    id             VARCHAR(40)  NOT NULL,
    task_id        VARCHAR(40)  NOT NULL,
    po_number      VARCHAR(40)  NOT NULL,
    status         VARCHAR(40)  NOT NULL,
    current_step   VARCHAR(80)  NULL,
    data           JSON         NULL COMMENT '执行过程中提取的业务变量、付款结果等',
    error_type     VARCHAR(80)  NULL,
    error_message  VARCHAR(1000) NULL,
    event_sequence INT          NOT NULL DEFAULT 0 COMMENT '本运行已分配的最大事件序号',
    retry_at       DATETIME(3)  NULL COMMENT '付款被其他执行者占用时，下次可重试的时间',
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    started_at     DATETIME(3)  NULL,
    finished_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_task (task_id),
    -- 故障恢复时按状态扫描
    KEY idx_status_updated (status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='运行';

-- 运行事件：时间线。(run_id, sequence) 唯一，序号由 workflow_run.event_sequence 原子分配
CREATE TABLE run_event (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    run_id     VARCHAR(40)  NOT NULL,
    sequence   INT          NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload    JSON         NULL,
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_sequence (run_id, sequence)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='运行事件';

-- 审批：大额付款前的人工审批
CREATE TABLE approval (
    id             VARCHAR(50)  NOT NULL,
    run_id         VARCHAR(40)  NOT NULL,
    risk           VARCHAR(20)  NOT NULL,
    payload        JSON         NOT NULL COMMENT '审批人看到的内容：PO、发票、供应商、金额',
    status         VARCHAR(20)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED',
    decision_actor VARCHAR(120) NULL,
    created_at     DATETIME(3)  NOT NULL,
    resolved_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_run_status (run_id, status),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审批';

-- 付款幂等记录：同一笔付款（PO + 发票）全局只能提交一次
CREATE TABLE idempotency_record (
    idem_key         VARCHAR(200) NOT NULL COMMENT '业务键，例如 payment:PO-10001:INV-8921',
    operation        VARCHAR(80)  NOT NULL,
    status           VARCHAR(20)  NOT NULL COMMENT 'PENDING：有人正在提交 / COMPLETED：已确认完成',
    owner_run_id     VARCHAR(40)  NULL COMMENT '当前持有租约的运行',
    lease_expires_at DATETIME(3)  NULL COMMENT '租约到期时间；到期仍是 PENDING 说明持有者可能已崩溃',
    attempt          INT          NOT NULL DEFAULT 1 COMMENT '第几次抢占（被回收一次 +1）',
    result           JSON         NULL COMMENT '完成后的付款结果，重复请求直接返回它',
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (idem_key),
    KEY idx_status_lease (status, lease_expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='付款幂等记录';
