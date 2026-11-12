package com.opspilot.dispatch;

import com.opspilot.store.OutboxMessageEntity;

/**
 * 发件箱消息的投递方式。投递成功（对方确认收到）才返回，失败抛 {@link DispatchException}，
 * 投递器会把消息推迟后重试。
 */
public interface DispatchPublisher {

    void publish(OutboxMessageEntity message);
}
