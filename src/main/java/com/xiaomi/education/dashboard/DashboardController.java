package com.xiaomi.education.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/courses")
    public List<Map<String, Object>> courses() {
        return service.courses();
    }

    @GetMapping("/assignments")
    public List<Map<String, Object>> assignments() {
        return service.assignments();
    }
}
