-- P4 第二步：发票、三单匹配差异、付款单；运行关联到付款单。
-- 金额口径：订单单价、发票明细单价都是不含税；发票价税合计 = 明细合计 + 税额；付款金额 = 价税合计。

CREATE TABLE invoice (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_number VARCHAR(60)   NOT NULL,
    supplier_id    BIGINT        NOT NULL,
    po_id          BIGINT        NOT NULL,
    invoice_date   DATE          NOT NULL,
    amount         DECIMAL(14, 2) NOT NULL COMMENT '不含税金额（明细合计）',
    tax_amount     DECIMAL(14, 2) NOT NULL,
    total_amount   DECIMAL(14, 2) NOT NULL COMMENT '价税合计，即应付金额',
    status         VARCHAR(20)   NOT NULL COMMENT 'PAYABLE：匹配通过待付款 / EXCEPTION：有差异待处理 / REJECTED / PAID',
    registered_by  VARCHAR(80)   NOT NULL,
    created_at     DATETIME(3)   NOT NULL,
    updated_at     DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    -- 同一供应商的发票号只能登记一次：重复登记就是重复付款的源头
    UNIQUE KEY uk_supplier_invoice (supplier_id, invoice_number),
    KEY idx_po (po_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='发票';

CREATE TABLE invoice_line (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT        NOT NULL,
    po_line_id BIGINT        NOT NULL,
    quantity   DECIMAL(12, 2) NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    amount     DECIMAL(14, 2) NOT NULL,
    occupied   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已占用订单行的可开票数量（invoiced_qty）',
    PRIMARY KEY (id),
    KEY idx_invoice (invoice_id),
    KEY idx_po_line (po_line_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='发票明细';

CREATE TABLE match_exception (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    invoice_id  BIGINT      NOT NULL,
    po_line_id  BIGINT      NULL,
    type        VARCHAR(30) NOT NULL COMMENT 'QTY_EXCEEDS_RECEIVED / PRICE_MISMATCH / TOTAL_MISMATCH',
    detail      JSON        NOT NULL COMMENT '期望值、实际值、偏差',
    status      VARCHAR(20) NOT NULL COMMENT 'OPEN / ACCEPTED / REJECTED / RESOLVED（重新匹配后已无差异）',
    resolver    VARCHAR(80) NULL,
    resolved_at DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_invoice (invoice_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='三单匹配差异';

CREATE TABLE payment_order (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    pay_number         VARCHAR(40)   NOT NULL,
    invoice_id         BIGINT        NOT NULL COMMENT '唯一：一张发票只能生成一张付款单',
    po_id              BIGINT        NOT NULL,
    supplier_id        BIGINT        NOT NULL,
    amount             DECIMAL(14, 2) NOT NULL,
    status             VARCHAR(20)   NOT NULL COMMENT 'PENDING / EXECUTING / PAID / FAILED / REJECTED',
    run_id             VARCHAR(40)   NULL COMMENT '当前（最近一次）执行它的运行',
    finance_payment_id VARCHAR(40)   NULL COMMENT '财务系统里的付款单号',
    created_at         DATETIME(3)   NOT NULL,
    updated_at         DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_number (pay_number),
    UNIQUE KEY uk_invoice (invoice_id),
    KEY idx_po_status (po_id, status),
    KEY idx_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='付款单';

-- 运行关联付款单：执行时按"采购单号 + 发票号"取付款依据
ALTER TABLE workflow_run
    ADD COLUMN invoice_number   VARCHAR(60) NULL AFTER po_number,
    ADD COLUMN payment_order_id BIGINT      NULL AFTER invoice_number;
