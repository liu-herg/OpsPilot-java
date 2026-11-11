package com.opspilot.idempotency;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.IdempotencyRecordEntity;
import com.opspilot.store.IdempotencyRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 有副作用的外部调用（付款）的幂等控制：同一个业务键全局只允许一个执行者在提交。
 *
 * <p>流程：先插入（INSERT 成功 = 抢到）→ 冲突则尝试回收过期租约（条件 UPDATE）→ 都不成就看记录状态。
 * 每一步都是单条 SQL 的原子操作，不依赖"先查再改"，也不需要分布式锁。
 */
@Service
public class IdempotencyService {

    private static final int MAX_CLAIM_ROUNDS = 3;

    private final IdempotencyRecordMapper mapper;
    private final int leaseSeconds;

    public IdempotencyService(IdempotencyRecordMapper mapper, OpsPilotProperties properties) {
        this.mapper = mapper;
        this.leaseSeconds = properties.idempotencyLeaseSeconds();
    }

    public ClaimResult claim(String key, String operation, String runId) {
        for (int round = 0; round < MAX_CLAIM_ROUNDS; round++) {
            try {
                mapper.insertPending(key, operation, runId, leaseSeconds);
                return new ClaimResult(ClaimOutcome.ACQUIRED, mapper.selectById(key));
            } catch (DuplicateKeyException alreadyRegistered) {
                // 已经有记录，往下看能不能接手
            }
            if (mapper.reclaimExpired(key, runId, leaseSeconds) == 1) {
                return new ClaimResult(ClaimOutcome.RECLAIMED, mapper.selectById(key));
            }
            IdempotencyRecordEntity record = mapper.selectById(key);
            if (record == null) {
                // 插入冲突之后、读取之前，持有者释放（删除）了记录：再来一轮
                continue;
            }
            return new ClaimResult(
                    "COMPLETED".equals(record.getStatus()) ? ClaimOutcome.COMPLETED : ClaimOutcome.OCCUPIED,
                    record);
        }
        throw new IllegalStateException("幂等记录争用过于激烈: " + key);
    }

    public IdempotencyRecordEntity find(String key) {
        return mapper.selectById(key);
    }

    /** @return true 表示由本次调用标记完成；false 表示已经是完成状态（别人先确认了） */
    public boolean complete(String key, String resultJson) {
        return mapper.complete(key, resultJson) == 1;
    }

    public boolean release(String key, String runId) {
        return mapper.release(key, runId) == 1;
    }
}
