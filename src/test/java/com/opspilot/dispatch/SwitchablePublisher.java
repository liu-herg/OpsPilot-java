package com.opspilot.dispatch;

import com.opspilot.store.OutboxMessageEntity;

/**
 * 测试用投递方式：包一层本地派发，可以模拟两种故障。
 * <ul>
 *   <li>{@link Mode#DOWN}：MQ 不可用，什么都没投出去就失败</li>
 *   <li>{@link Mode#DELIVER_THEN_FAIL}：消息其实已经送达（运行被执行了），但确认丢了 / 投递器在标记 SENT 之前崩溃</li>
 * </ul>
 */
public class SwitchablePublisher implements DispatchPublisher {

    public enum Mode { UP, DOWN, DELIVER_THEN_FAIL }

    private final LocalDispatchPublisher delegate;
    private volatile Mode mode = Mode.UP;

    public SwitchablePublisher(LocalDispatchPublisher delegate) {
        this.delegate = delegate;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public void publish(OutboxMessageEntity message) {
        switch (mode) {
            case UP -> delegate.publish(message);
            case DOWN -> throw new DispatchException("模拟：连不上 RabbitMQ");
            case DELIVER_THEN_FAIL -> {
                delegate.publish(message);
                throw new DispatchException("模拟：消息已送达，但没收到确认");
            }
        }
    }
}
