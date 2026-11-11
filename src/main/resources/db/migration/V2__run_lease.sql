-- P2：后台异步执行 + 故障恢复 + 多实例
-- 执行租约：哪个实例正在执行这个运行、到什么时候。实例崩溃后租约过期，其他实例（或重启后的自己）接手。

ALTER TABLE workflow_run
    ADD COLUMN lease_owner      VARCHAR(100) NULL COMMENT '正在执行该运行的实例 ID；NULL 表示没人在执行',
    ADD COLUMN lease_expires_at DATETIME(3)  NULL COMMENT '执行租约到期时间（数据库时钟）；每次状态转移自动续期',
    ADD COLUMN retry_count      INT          NOT NULL DEFAULT 0 COMMENT '因外部系统故障被推迟的次数，用于指数退避',
    ADD KEY idx_lease_owner (lease_owner);
