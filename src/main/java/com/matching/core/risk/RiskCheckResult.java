package com.matching.core.risk;

import lombok.Data;

/**
 * 风控检查结果
 */
@Data
public class RiskCheckResult {
    private boolean allowed;        // 是否允许执行
    private String rejectReason;   // 拒绝原因
    private RiskErrorCode errorCode; // 错误码

    public static RiskCheckResult allowed() {
        RiskCheckResult result = new RiskCheckResult();
        result.setAllowed(true);
        return result;
    }

    public static RiskCheckResult rejected(String reason, RiskErrorCode code) {
        RiskCheckResult result = new RiskCheckResult();
        result.setAllowed(false);
        result.setRejectReason(reason);
        result.setErrorCode(code);
        return result;
    }
}
