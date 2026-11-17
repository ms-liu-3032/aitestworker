package com.company.aitest.dsh;

import com.company.aitest.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dsh/tools/telemetry")
public class DshTelemetryController {
    private static final Logger log = LoggerFactory.getLogger(DshTelemetryController.class);

    @PostMapping
    public ApiResponse<Void> record(@Valid @RequestBody Event event, Authentication auth) {
        var principal = (DshConnectionService.DshPrincipal) auth.getPrincipal();
        if (!event.toolName().matches("aitest_[a-z_]{1,80}")
                || !event.state().matches("STARTED|SUCCEEDED|FAILED|ABORTED")
                || event.callId().length() > 128 || (event.errorCode() != null && event.errorCode().length() > 80)) {
            throw new IllegalArgumentException("DSH 运行事件格式错误");
        }
        log.info("dsh_tool userId={} connectionId={} tool={} callId={} state={} durationMs={} errorCode={}",
                principal.user().id(), principal.connectionId(), event.toolName(), event.callId(), event.state(),
                event.durationMs(), event.errorCode());
        return ApiResponse.ok(null);
    }

    public record Event(@NotBlank String toolName, @NotBlank String callId, @NotBlank String state,
                        Long durationMs, String errorCode) {}
}
