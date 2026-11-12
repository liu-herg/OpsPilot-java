package com.opspilot.procure;

import com.opspilot.store.BizSequenceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务单号：前缀-日期-当天序号，例如 PR-20261112-0001。
 *
 * <p>不用 {@code SELECT MAX(...) + 1}：两个事务同时读到同一个最大值就会重号（P1 事件序号测过，10 个线程 9 个重复）。
 * 做法：
 * <ol>
 *   <li>从数据库取当天日期（普通读），后面的语句都用这个日期，跨零点也不会错位</li>
 *   <li>本实例当天第一次用这个前缀时，在<b>独立的短事务</b>里 INSERT IGNORE 建好这一行（并发建行只有一个成功，其余忽略）</li>
 *   <li>在业务事务里 {@code UPDATE ... SET value = LAST_INSERT_ID(value + 1)}：递增在数据库里原子完成，
 *       行锁让并发取号排队，LAST_INSERT_ID 把本连接分到的值带回来</li>
 * </ol>
 * 第 2 步必须在第 3 步之前：行不存在时 UPDATE 会加间隙锁，再去别的事务里建行就会被自己挡住。
 * 代价：同一前缀的取号在业务事务提交前互斥，所以业务事务要短。业务事务回滚时号也一起回滚，不跳号。
 */
@Service
public class BizNumberService {

    private final BizSequenceMapper mapper;
    private final TransactionTemplate newTx;
    /** 本实例已经确认存在的序列行："前缀|日期"。 */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public BizNumberService(BizSequenceMapper mapper, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public String next(String prefix) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // LAST_INSERT_ID 是连接级的：不在事务里，两条语句可能拿到不同的连接
            throw new IllegalStateException("取业务单号必须在事务里调用");
        }
        String day = mapper.today();
        String key = prefix + "|" + day;
        if (!ensured.contains(key)) {
            newTx.executeWithoutResult(s -> mapper.ensure(prefix, day));
            ensured.add(key);
        }
        if (mapper.bump(prefix, day) != 1) {
            // 行被删了（只有测试会这么做）：忘掉缓存，让这次业务事务失败，重试时会重新建行
            ensured.remove(key);
            throw new IllegalStateException("业务单号序列不存在，请重试: " + prefix);
        }
        return "%s-%s-%04d".formatted(prefix, day.replace("-", ""), mapper.lastValue());
    }
}
