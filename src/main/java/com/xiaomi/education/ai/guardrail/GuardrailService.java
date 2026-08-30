package com.xiaomi.education.ai.guardrail;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class GuardrailService {

    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)(忽略.{0,12}(之前|以上|系统).{0,8}(指令|提示)|system\\s*prompt|ignore\\s+(all\\s+)?previous|jailbreak|越狱)"
    );
    private static final Pattern EXAM_CHEATING = Pattern.compile(
            "(替我.{0,8}(考试|答题|代写)|直接给我.{0,8}(考试|测验).{0,8}答案|绕过.{0,8}(监考|检测))"
    );
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    public GuardrailResult inspect(String input) {
        var value = input == null ? "" : input;
        var codes = new ArrayList<String>();
        var allowed = true;
        var riskLevel = "LOW";

        if (PROMPT_INJECTION.matcher(value).find()) {
            codes.add("PROMPT_INJECTION");
            riskLevel = "HIGH";
            allowed = false;
        }
        if (EXAM_CHEATING.matcher(value).find()) {
            codes.add("ACADEMIC_CHEATING");
            riskLevel = "HIGH";
            allowed = false;
        }

        var sanitized = value;
        if (EMAIL.matcher(sanitized).find() || PHONE.matcher(sanitized).find() || ID_CARD.matcher(sanitized).find()) {
            codes.add("PII_REDACTED");
            sanitized = EMAIL.matcher(sanitized).replaceAll("[EMAIL_REDACTED]");
            sanitized = PHONE.matcher(sanitized).replaceAll("[PHONE_REDACTED]");
            sanitized = ID_CARD.matcher(sanitized).replaceAll("[ID_REDACTED]");
            if (!"HIGH".equals(riskLevel)) {
                riskLevel = "MEDIUM";
            }
        }
        return new GuardrailResult(allowed, sanitized, riskLevel, List.copyOf(codes));
    }

    public record GuardrailResult(boolean allowed, String sanitizedText, String riskLevel, List<String> riskCodes) {
    }
}
