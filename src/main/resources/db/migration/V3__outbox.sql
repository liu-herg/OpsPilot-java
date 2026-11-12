-- P3：事务性发件箱（Transactional Outbox）
-- 要派发的运行先和状态变化写在同一个事务里，事务提交后再由投递器发到 RabbitMQ。
-- 这样"状态改了但消息没发出去"或"消息发了但状态回滚了"都不会发生。

CREATE TABLE outbox_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(80)  NOT NULL COMMENT '消息类型，目前只有 run.dispatch',
    message_key     VARCHAR(100) NOT NULL COMMENT '业务键，run.dispatch 就是运行 id',
    reason          VARCHAR(80)  NULL COMMENT '为什么要派发：run.started / approval.approved / run.resumed',
    status          VARCHAR(20)  NOT NULL COMMENT 'PENDING：待投递 / SENT：MQ 已确认收到 / DEAD：多次投递失败后放弃',
    attempts        INT          NOT NULL DEFAULT 0 COMMENT '已经尝试投递的次数',
    next_attempt_at DATETIME(3)  NOT NULL COMMENT '最早什么时候可以投递（数据库时钟）。投递器领取时把它推后，相当于领取租约',
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(3)  NOT NULL,
    sent_at         DATETIME(3)  NULL,
    PRIMARY KEY (id),
    -- 投递器按"待投递且到时间了"扫描
    KEY idx_status_next (status, next_attempt_at),
    KEY idx_message_key (message_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='发件箱';
