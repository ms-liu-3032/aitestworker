package com.company.aitest.dsh;

import com.company.aitest.common.TimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class DshConnectionServiceTest {
    @Test
    void agentAndHumanTokenPrefixesCannotCrossRoutes() {
        JdbcClient jdbc = mock(JdbcClient.class);
        var service = new DshConnectionService(jdbc, mock(JdbcTemplate.class), mock(TimeProvider.class));

        assertNull(service.authenticate("dsh_agent_token", true));
        assertNull(service.authenticate("dsh_user_token", false));
        verifyNoInteractions(jdbc);
    }
}
