package com.xiaomi.education.intervention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/interventions")
public class InterventionController {

    private final InterventionService service;

    public InterventionController(InterventionService service) {
        this.service = service;
    }

    /**
     * 创建学习干预，并生成待审批通知
     * @param request
     * @return
     */
    @PostMapping
    public InterventionService.InterventionResponse create(
            @Valid @RequestBody InterventionRequest request
    ) {
        return service.create(request.learnerId(), request.courseId(), request.objective());
    }

    /**
     * 查询干预审批列表
     * @return
     */
    @GetMapping("/approvals")
    public List<InterventionService.ApprovalView> approvals() {
        return service.approvals();
    }

    /**
     * 批准审批，并受控发送通知
     * @param id
     * @param request
     * @return
     */
    @PostMapping("/approvals/{id}/approve")
    public InterventionService.ApprovalView approve(
            @PathVariable String id,
            @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        return service.approve(id, request.comment());
    }

    /**
     * 拒绝审批，不发送通知
     * @param id
     * @param request
     * @return
     */
    @PostMapping("/approvals/{id}/reject")
    public InterventionService.ApprovalView reject(
            @PathVariable String id,
            @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        return service.reject(id, request.comment());
    }

    /**
     * 查询已经发送的通知记录
     * @return
     */
    @GetMapping("/notifications")
    public List<InterventionService.NotificationView> notifications() {
        return service.notifications();
    }

    public record InterventionRequest(
            @NotBlank @Size(max = 64) String learnerId,
            @NotBlank @Size(max = 64) String courseId,
            @NotBlank @Size(max = 1000) String objective
    ) {
    }

    public record ApprovalDecisionRequest(@Size(max = 1000) String comment) {
    }
}
