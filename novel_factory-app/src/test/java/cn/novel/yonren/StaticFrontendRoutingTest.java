package cn.novel.yonren;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFrontendRoutingTest {

    @Test
    void packagedFrontendUsesSameOriginOnNonDefaultServerPort() throws IOException {
        String script;
        try (InputStream input = getClass().getResourceAsStream("/static/app.js")) {
            assertTrue(input != null, "packaged app.js must exist");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(script.contains("location.port === '63342' ? 'http://localhost:8080' : ''"),
                "only IDEA's static preview should be redirected to the development backend");
        assertFalse(script.contains("(location.port === '8080' || location.port === '') ? '' : 'http://localhost:8080'"),
                "a packaged app running on 8081 must not send API calls to another service on 8080");
    }
}
