package com.opspilot.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

public final class TestData {

    private TestData() {
    }

    /** 随机 PO 号，避免和演示数据（PO-10001…）以及其他测试冲突。 */
    public static String randomPo() {
        return "PO-9" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    }

    public static void cleanup(JdbcTemplate jdbc, Collection<String> poNumbers) {
        for (String po : poNumbers) {
            jdbc.update("DELETE e FROM run_event e JOIN workflow_run r ON r.id = e.run_id WHERE r.po_number = ?", po);
            jdbc.update("DELETE a FROM approval a JOIN workflow_run r ON r.id = a.run_id WHERE r.po_number = ?", po);
            jdbc.update("DELETE FROM workflow_run WHERE po_number = ?", po);
            jdbc.update("DELETE FROM task WHERE po_number = ?", po);
            jdbc.update("DELETE FROM idempotency_record WHERE idem_key LIKE ?", "payment:" + po + ":%");
        }
    }
}
