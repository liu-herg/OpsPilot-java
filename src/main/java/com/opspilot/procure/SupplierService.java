package com.opspilot.procure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opspilot.api.ConflictException;
import com.opspilot.api.NotFoundException;
import com.opspilot.store.SupplierEntity;
import com.opspilot.store.SupplierMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 供应商：新建、修改、冻结 / 解冻。冻结的供应商不能下单（P4 第二步起也不能付款）。 */
@Service
public class SupplierService {

    private final SupplierMapper mapper;

    public SupplierService(SupplierMapper mapper) {
        this.mapper = mapper;
    }

    public SupplierView create(ProcureCommands.CreateSupplier cmd) {
        SupplierEntity s = new SupplierEntity();
        s.setCode(cmd.code().trim());
        s.setName(cmd.name().trim());
        s.setTaxNo(cmd.taxNo().trim());
        s.setBankName(cmd.bankName().trim());
        s.setBankAccount(cmd.bankAccount().trim());
        s.setStatus(ProcureStatus.Supplier.ACTIVE.name());
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        try {
            mapper.insert(s);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发新建同一个供应商，只有一个成功
            throw new ConflictException("供应商编码或税号已存在", null);
        }
        return SupplierView.of(s);
    }

    public SupplierView update(Long id, ProcureCommands.UpdateSupplier cmd) {
        require(id);
        SupplierEntity patch = new SupplierEntity();
        patch.setId(id);
        patch.setName(cmd.name().trim());
        patch.setBankName(cmd.bankName().trim());
        patch.setBankAccount(cmd.bankAccount().trim());
        patch.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(patch);
        return get(id);
    }

    public SupplierView freeze(Long id) {
        return move(id, ProcureStatus.Supplier.ACTIVE, ProcureStatus.Supplier.FROZEN);
    }

    public SupplierView unfreeze(Long id) {
        return move(id, ProcureStatus.Supplier.FROZEN, ProcureStatus.Supplier.ACTIVE);
    }

    /** 冻结 / 解冻是幂等的：已经是目标状态就直接返回。 */
    private SupplierView move(Long id, ProcureStatus.Supplier from, ProcureStatus.Supplier to) {
        if (mapper.transition(id, from.name(), to.name()) == 0) {
            SupplierEntity current = require(id);
            if (!to.name().equals(current.getStatus())) {
                throw new ConflictException("供应商当前状态为 " + current.getStatus(), current.getStatus());
            }
        }
        return get(id);
    }

    public SupplierView get(Long id) {
        return SupplierView.of(require(id));
    }

    public PageResult<SupplierView> list(String status, String keyword, int page, int size) {
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        LambdaQueryWrapper<SupplierEntity> where = new LambdaQueryWrapper<SupplierEntity>()
                .eq(status != null && !status.isBlank(), SupplierEntity::getStatus, status == null ? null : status.trim().toUpperCase())
                .and(keyword != null && !keyword.isBlank(), w -> w.like(SupplierEntity::getName, keyword.trim())
                        .or().like(SupplierEntity::getCode, keyword.trim()));
        long total = mapper.selectCount(where);
        where.orderByDesc(SupplierEntity::getId).last(PageResult.limit(p, s));
        return new PageResult<>(mapper.selectList(where).stream().map(SupplierView::of).toList(), total, p, s);
    }

    /** 下单、付款前调用：供应商必须存在且没有被冻结。 */
    public SupplierEntity requireActive(Long id) {
        SupplierEntity s = require(id);
        if (!ProcureStatus.Supplier.ACTIVE.name().equals(s.getStatus())) {
            throw new ConflictException("供应商 " + s.getName() + " 已冻结", s.getStatus());
        }
        return s;
    }

    public SupplierEntity require(Long id) {
        SupplierEntity s = id == null ? null : mapper.selectById(id);
        if (s == null) {
            throw new NotFoundException("供应商不存在: " + id);
        }
        return s;
    }
}
