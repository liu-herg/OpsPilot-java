package com.opspilot.gateway;

/** @param created true = 财务系统新建了付款（201）；false = 财务系统里已经有这笔（200） */
public record SubmitResult(Payment payment, boolean created) {
}
