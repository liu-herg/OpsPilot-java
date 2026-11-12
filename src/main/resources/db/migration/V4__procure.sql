-- P4 第一步：供应商、采购申请、采购订单、收货
-- 发票、三单匹配、付款单在 V5。金额和数量一律 DECIMAL，Java 侧用 BigDecimal。

-- 业务单号：按"前缀 + 日期"原子递增，例如 PR-20261112-0001。
-- 取号：当天第一次先在独立短事务里 INSERT IGNORE 建行；然后在业务事务里
-- UPDATE biz_sequence SET value = LAST_INSERT_ID(value + 1) WHERE name = ? AND day = ?，同一个连接上 SELECT LAST_INSERT_ID()。
-- 和 MAX+1 不同，并发取号不会重号。
CREATE TABLE biz_sequence (
    name  VARCHAR(40) NOT NULL COMMENT 'PR / PO / GR / INV / PAY',
    day   DATE        NOT NULL,
    value INT         NOT NULL,
    PRIMARY KEY (name, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='业务单号序列';

CREATE TABLE supplier (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(40)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    tax_no       VARCHAR(40)  NOT NULL COMMENT '统一社会信用代码',
    bank_name    VARCHAR(200) NOT NULL,
    bank_account VARCHAR(60)  NOT NULL,
    status       VARCHAR(20)  NOT NULL COMMENT 'ACTIVE / FROZEN',
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    UNIQUE KEY uk_tax_no (tax_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='供应商';

CREATE TABLE purchase_request (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    request_no   VARCHAR(40)   NOT NULL,
    department   VARCHAR(80)   NOT NULL,
    requester    VARCHAR(80)   NOT NULL,
    title        VARCHAR(200)  NOT NULL,
    total_amount DECIMAL(14, 2) NOT NULL COMMENT '按预估单价计算',
    status       VARCHAR(20)   NOT NULL COMMENT 'DRAFT / SUBMITTED / APPROVED / REJECTED / CANCELLED / ORDERED',
    decided_by   VARCHAR(80)   NULL,
    decided_at   DATETIME(3)   NULL,
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_no (request_no),
    KEY idx_status (status),
    KEY idx_department_status (department, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='采购申请';

CREATE TABLE purchase_request_line (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    request_id     BIGINT        NOT NULL,
    line_no        INT           NOT NULL,
    item_name      VARCHAR(200)  NOT NULL,
    spec           VARCHAR(200)  NULL,
    quantity       DECIMAL(12, 2) NOT NULL,
    unit           VARCHAR(20)   NOT NULL,
    est_unit_price DECIMAL(12, 2) NOT NULL,
    amount         DECIMAL(14, 2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_line (request_id, line_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='采购申请明细';

CREATE TABLE purchase_order (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    po_number    VARCHAR(40)   NOT NULL,
    request_id   BIGINT        NULL COMMENT '来源申请；唯一，一个申请只能转一张订单',
    supplier_id  BIGINT        NOT NULL,
    buyer        VARCHAR(80)   NOT NULL,
    total_amount DECIMAL(14, 2) NOT NULL,
    status       VARCHAR(30)   NOT NULL COMMENT 'ISSUED / PARTIALLY_RECEIVED / RECEIVED / CLOSED / CANCELLED',
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_po_number (po_number),
    UNIQUE KEY uk_request (request_id),
    KEY idx_supplier (supplier_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='采购订单';

CREATE TABLE purchase_order_line (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    po_id        BIGINT        NOT NULL,
    line_no      INT           NOT NULL,
    item_name    VARCHAR(200)  NOT NULL,
    unit         VARCHAR(20)   NOT NULL,
    quantity     DECIMAL(12, 2) NOT NULL,
    unit_price   DECIMAL(12, 2) NOT NULL,
    amount       DECIMAL(14, 2) NOT NULL,
    received_qty DECIMAL(12, 2) NOT NULL DEFAULT 0 COMMENT '已收货，条件递增，不超过 quantity',
    invoiced_qty DECIMAL(12, 2) NOT NULL DEFAULT 0 COMMENT '已开票，条件递增，不超过 received_qty（V5 使用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_po_line (po_id, line_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='采购订单明细';

CREATE TABLE goods_receipt (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    gr_number  VARCHAR(40) NOT NULL,
    po_id      BIGINT      NOT NULL,
    receiver   VARCHAR(80) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gr_number (gr_number),
    KEY idx_po (po_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收货单';

CREATE TABLE goods_receipt_line (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    gr_id      BIGINT        NOT NULL,
    po_line_id BIGINT        NOT NULL,
    quantity   DECIMAL(12, 2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_gr (gr_id),
    KEY idx_po_line (po_line_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='收货明细';
