package com.opspilot.procure;

/** 采购相关单据的状态。转移规则写在各自的服务里，落库都用条件更新。 */
public final class ProcureStatus {

    private ProcureStatus() {
    }

    /** 供应商 */
    public enum Supplier { ACTIVE, FROZEN }

    /**
     * 采购申请：DRAFT ─提交─▶ SUBMITTED ─批准─▶ APPROVED ─转订单─▶ ORDERED；
     * SUBMITTED ─驳回─▶ REJECTED；DRAFT / SUBMITTED ─撤销─▶ CANCELLED
     */
    public enum Request { DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED, ORDERED }

    /**
     * 采购订单：ISSUED ─部分收货─▶ PARTIALLY_RECEIVED ─收齐─▶ RECEIVED ─付清─▶ CLOSED；
     * ISSUED（没有任何收货）─取消─▶ CANCELLED
     */
    public enum Order { ISSUED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED }

    /**
     * 发票：登记时同步做三单匹配。通过 → PAYABLE（同时生成付款单）；有差异 → EXCEPTION。
     * EXCEPTION ─差异全部接受 / 重新匹配通过─▶ PAYABLE；EXCEPTION ─驳回─▶ REJECTED；PAYABLE ─付款完成─▶ PAID
     */
    public enum Invoice { PAYABLE, EXCEPTION, REJECTED, PAID }

    /** 三单匹配差异：OPEN ─接受─▶ ACCEPTED；OPEN ─驳回─▶ REJECTED；OPEN ─重新匹配后不再有差异─▶ RESOLVED */
    public enum MatchException { OPEN, ACCEPTED, REJECTED, RESOLVED }

    /**
     * 付款单：PENDING ─发起─▶ EXECUTING ─运行完成─▶ PAID；运行失败 ─▶ FAILED ─重新发起─▶ PENDING；
     * 大额审批被驳回 ─▶ REJECTED
     */
    public enum Payment { PENDING, EXECUTING, PAID, FAILED, REJECTED }
}
