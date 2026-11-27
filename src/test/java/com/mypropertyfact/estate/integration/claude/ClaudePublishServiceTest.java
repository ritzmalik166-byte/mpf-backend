package com.mypropertyfact.estate.integration.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import com.mypropertyfact.estate.integration.claude.service.ClaudePublishService;
import com.mypropertyfact.estate.services.ListingActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** No-database checks of the publish request rules (the full flow is covered by ClaudePublishDbTest). */
class ClaudePublishServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClaudePublishService service =
            new ClaudePublishService(jdbc, new ObjectMapper(), mock(ListingActivityService.class),
                    mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void projectIdMustBeAPositiveInteger() {
        assertThat(service.parseProjectId("1394")).isEqualTo(1394);
        assertThat(service.parseProjectId(" 7 ")).isEqualTo(7);
        for (String bad : new String[]{null, "", "0", "-5", "abc", "1.5", "99999999999", "1;DROP"}) {
            ClaudeIntegrationException e = catchThrowableOfType(() -> service.parseProjectId(bad), ClaudeIntegrationException.class);
            assertThat(e).as(String.valueOf(bad)).isNotNull();
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getCode()).isEqualTo("INVALID_PROJECT_ID");
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void onlyAnExplicitBooleanTrueConfirms() {
        assertThatCode(() -> service.requireConfirmation("{\"confirm\": true}", 1)).doesNotThrowAnyException();
        for (String body : new String[]{null, "", "  ", "{}", "{\"confirm\": false}", "{\"confirm\": \"true\"}", "{\"confirm\": 1}", "[true]"}) {
            ClaudeIntegrationException e = catchThrowableOfType(() -> service.requireConfirmation(body, 1), ClaudeIntegrationException.class);
            assertThat(e).as(String.valueOf(body)).isNotNull();
            assertThat(e.getCode()).isEqualTo("CONFIRMATION_REQUIRED");
        }
        ClaudeIntegrationException bad = catchThrowableOfType(() -> service.requireConfirmation("{oops", 1), ClaudeIntegrationException.class);
        assertThat(bad.getCode()).isEqualTo("INVALID_JSON");
        verifyNoInteractions(jdbc);
    }

    @Test
    void failuresUseTheMpfResponseShape() {
        ClaudeIntegrationException e = catchThrowableOfType(() -> service.requireConfirmation(null, 42), ClaudeIntegrationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) e.getBody();
        assertThat(body).containsEntry("isSuccess", 0).containsEntry("projectId", 42).containsEntry("error", "CONFIRMATION_REQUIRED")
                .containsKeys("message", "timestamp", "status");
    }
}
