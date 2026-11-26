package com.company.aitest.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {
    @Test
    void unexpectedErrorKeepsSafeStackInLogsAndReturnsSafeMessage(CapturedOutput output) throws Exception {
        MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build()
                .perform(get("/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("系统异常，请稍后重试"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-connection-string"))));
        org.junit.jupiter.api.Assertions.assertTrue(output.getOut().contains("GET /failure"));
        org.junit.jupiter.api.Assertions.assertTrue(output.getOut().contains("IllegalStateException"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getOut().contains("private-connection-string"));
    }

    @RestController
    static class FailingController {
        @GetMapping("/failure")
        String fail() {
            throw new IllegalStateException("private-connection-string");
        }
    }
}
