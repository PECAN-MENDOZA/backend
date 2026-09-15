package com.mvp.backend.correction.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.mvp.backend.config.AiProperties;
import com.mvp.backend.shared.exception.AiServiceException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class HttpAiCorrectionClientTests {

    private static final String SECRET_BODY = "{\"detail\":\"mi mama me dijo que baya\"}";

    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpAiCorrectionClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockRestServiceServer server;
    private HttpAiCorrectionClient client;

    @BeforeEach
    void setUp() {
        logger.setLevel(Level.TRACE);
        logs.start();
        logger.addAppender(logs);
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAiCorrectionClient(builder.build(), new AiProperties(
                "http", "http://ai.test", "/interno/corregir", "/interno/feedback",
                Duration.ofSeconds(5), Duration.ofSeconds(90)));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    @Test
    void failedCorrectionLogsOnlyTheExceptionClassAndStatus() {
        server.expect(requestTo("http://ai.test/interno/corregir"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body(SECRET_BODY));
        UUID studentId = UUID.randomUUID();

        assertThatThrownBy(() -> client.correct("mi mama me dijo que baya", studentId))
                .isInstanceOf(AiServiceException.class);

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("InternalServerError", "status=500")
                    .doesNotContain("baya", "detail", studentId.toString());
            assertThat(event.getThrowableProxy()).as("no stack trace carrying the response body").isNull();
        });
    }

    @Test
    void failedFeedbackIsLoggedWithoutPayloadAndNeverThrows() {
        server.expect(requestTo("http://ai.test/interno/feedback"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body(SECRET_BODY));
        UUID studentId = UUID.randomUUID();

        assertThatCode(() -> client.sendFeedback(studentId, "mi lapis se callo", "mi lapiz se cayo", true))
                .doesNotThrowAnyException();

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("InternalServerError", "status=500")
                    .doesNotContain("lapis", "lapiz", "baya", studentId.toString());
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void describeOmitsTheMessageAndAddsTheStatusOnlyWhenThereWasAResponse() {
        var noResponse = new ResourceAccessException("connect refused: texto secreto");
        assertThat(HttpAiCorrectionClient.describe(noResponse)).isEqualTo("ResourceAccessException");

        var withResponse = HttpClientErrorException.create(
                HttpStatus.BAD_GATEWAY, "texto secreto", null, SECRET_BODY.getBytes(), null);
        assertThat(HttpAiCorrectionClient.describe(withResponse))
                .isEqualTo("HttpClientErrorException status=502")
                .doesNotContain("secreto");
    }
}
