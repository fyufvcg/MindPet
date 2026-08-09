-- 发票报销记录表 (可选 — Excel 为主存储，此表用于加速查重和统计分析)
-- 一张发票只认一个人: (invoice_code, invoice_number) 联合唯一约束

CREATE TABLE IF NOT EXISTS invoice_records (
    id              SERIAL PRIMARY KEY,
    invoice_code    VARCHAR(20)  NOT NULL,
    invoice_number  VARCHAR(20)  NOT NULL,
    invoice_type    VARCHAR(30),            -- 增值税专票/普票/全电发票等
    invoice_date    DATE,                   -- 开票日期
    seller_name     VARCHAR(300),           -- 销方名称
    seller_tax_id   VARCHAR(30),            -- 销方税号
    buyer_name      VARCHAR(300),           -- 购方名称
    buyer_tax_id    VARCHAR(30),            -- 购方税号
    amount_without_tax DECIMAL(12,2),       -- 不含税金额
    tax_amount      DECIMAL(12,2),          -- 税额
    total_amount    DECIMAL(12,2),          -- 价税合计
    verify_status   VARCHAR(30),            -- 真票/假票/作废票/查无此票
    verify_time     TIMESTAMP,              -- 核验时间
    user_id         VARCHAR(100) NOT NULL,  -- 报销人微信ID
    reimburse_amount DECIMAL(12,2),         -- 报销金额 = 价税合计
    reason          TEXT,                   -- 报销理由
    reimburse_date  TIMESTAMP DEFAULT NOW(),-- 报销日期
    created_at      TIMESTAMP DEFAULT NOW(),

    UNIQUE(invoice_code, invoice_number)    -- 一张发票只认一个人
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_invoice_user ON invoice_records(user_id);
CREATE INDEX IF NOT EXISTS idx_invoice_date ON invoice_records(reimburse_date);
