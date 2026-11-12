package com.opspilot.run;

/**
 * 创建运行时，决定这次运行付的是哪一笔，并在同一个事务里占住它。
 * 生产环境的实现是"找到待付款的付款单"（procure 模块）；没有实现时（P1~P3 的测试）只按采购单号创建运行。
 */
public interface RunTargetResolver {

    /**
     * 在创建运行的事务里调用，可以加锁。
     *
     * @param paymentOrderId 指定付款单；为空时按采购单号找最早的一张待付款付款单
     */
    RunTarget resolve(String poNumber, Long paymentOrderId);

    /** 运行已经插入（同一个事务里），把付款单标记为执行中。 */
    void onRunCreated(RunTarget target, String runId);
}
