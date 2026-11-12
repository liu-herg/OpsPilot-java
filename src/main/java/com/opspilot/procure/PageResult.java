package com.opspilot.procure;

import java.util.List;

/** 分页结果。page 从 1 开始。 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    public static int normalizePage(int page) {
        return Math.max(page, 1);
    }

    public static int normalizeSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    /** 拼在 SQL 末尾的 LIMIT 子句。page、size 已经是规范化后的整数，不会有注入问题。 */
    public static String limit(int page, int size) {
        return "LIMIT " + size + " OFFSET " + (long) (page - 1) * size;
    }
}
