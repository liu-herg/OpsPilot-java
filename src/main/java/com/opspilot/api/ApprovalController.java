package com.opspilot.api;

import com.opspilot.approval.ApprovalService;
import com.opspilot.approval.ApprovalView;
import com.opspilot.approval.DecisionResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<ApprovalView> list(@RequestParam(defaultValue = "PENDING") String status,
                                   @RequestParam(defaultValue = "50") int size) {
        return approvalService.list(status, size);
    }

    /** body: {"decision": "approve" | "reject", "actor": "finance-lead"} */
    @PostMapping("/{approvalId}/decision")
    public DecisionResult decide(@PathVariable String approvalId, @Valid @RequestBody DecisionRequest body) {
        return approvalService.decide(approvalId, body.decision(), body.actor());
    }
}
