package com.opspilot.procure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购相关接口的请求体（JSON 字段是下划线风格，例如 tax_no）。 */
public final class ProcureCommands {

    private ProcureCommands() {
    }

    public record CreateSupplier(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 40) String taxNo,
            @NotBlank @Size(max = 200) String bankName,
            @NotBlank @Size(max = 60) String bankAccount) {
    }

    public record UpdateSupplier(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String bankName,
            @NotBlank @Size(max = 60) String bankAccount) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 80) String department,
            @NotBlank @Size(max = 80) String requester,
            @NotBlank @Size(max = 200) String title,
            @NotEmpty @Size(max = 50) List<@Valid RequestLine> lines) {
    }

    public record RequestLine(
            @NotBlank @Size(max = 200) String itemName,
            @Size(max = 200) String spec,
            @NotNull BigDecimal quantity,
            @NotBlank @Size(max = 20) String unit,
            @NotNull BigDecimal estUnitPrice) {
    }

    /** 批准 / 驳回采购申请。P6 接入登录后，审批人从登录态取。 */
    public record Decide(@NotBlank @Size(max = 80) String actor) {
    }

    /** 申请转采购订单：选供应商；单价默认用申请里的预估单价，可以按行覆盖。 */
    public record CreateOrder(
            @NotNull Long supplierId,
            @NotBlank @Size(max = 80) String buyer,
            List<@Valid PriceOverride> prices) {
    }

    public record PriceOverride(@NotNull Integer lineNo, @NotNull BigDecimal unitPrice) {
    }

    /** 收货：本次每一行收多少。 */
    public record CreateReceipt(
            @NotBlank @Size(max = 80) String receiver,
            @NotEmpty @Size(max = 50) List<@Valid ReceiptLine> lines) {
    }

    public record ReceiptLine(@NotNull Integer lineNo, @NotNull BigDecimal quantity) {
    }

    /**
     * 登记发票。明细单价不含税；amount = 明细合计（不含税），total_amount = amount + tax_amount。
     * 登记时同步做三单匹配。
     */
    public record RegisterInvoice(
            @NotBlank @Size(max = 60) String invoiceNumber,
            @NotNull Long supplierId,
            @NotBlank @Size(max = 40) String poNumber,
            @NotNull LocalDate invoiceDate,
            @NotNull BigDecimal amount,
            @NotNull BigDecimal taxAmount,
            @NotNull BigDecimal totalAmount,
            @NotBlank @Size(max = 80) String registeredBy,
            @NotEmpty @Size(max = 50) List<@Valid InvoiceLine> lines) {
    }

    public record InvoiceLine(@NotNull Integer lineNo, @NotNull BigDecimal quantity, @NotNull BigDecimal unitPrice) {
    }
}
