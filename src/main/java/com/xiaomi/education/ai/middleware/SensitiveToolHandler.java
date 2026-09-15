package com.xiaomi.education.ai.middleware;

public interface SensitiveToolHandler {

    String toolName();

    void execute(ToolApproval approval, String approvedBy);
}
