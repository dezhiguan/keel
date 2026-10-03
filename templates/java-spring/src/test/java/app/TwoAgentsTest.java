package app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class TwoAgentsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Autowired MockMvc mvc;

    @Test void primaryManifestUsesItsOwnName() throws Exception { assertEquals(nameOf("{{name}}"), manifestName("/{{name}}/v1/manifest")); }
    @Test void auxManifestUsesItsOwnName() throws Exception { assertEquals(nameOf("{{name_aux}}"), manifestName("/{{name_aux}}/v1/manifest")); }
    @Test void agentsDoNotShareAName() throws Exception { assertNotEquals(manifestName("/{{name}}/v1/manifest"), manifestName("/{{name_aux}}/v1/manifest")); }
    @Test void unprefixedManifestIsAbsent() throws Exception { mvc.perform(get("/v1/manifest")).andExpect(status().isNotFound()); }
    @Test void primaryHealthIsOk() throws Exception { assertEquals("ok", MAPPER.readTree(body(get("/{{name}}/v1/health"))).get("status").asText()); }
    @Test void auxHealthNamesItself() throws Exception { assertEquals(nameOf("{{name_aux}}"), MAPPER.readTree(body(get("/{{name_aux}}/v1/health"))).get("agent").asText()); }
    @Test void primaryInvokeReturnsItsName() throws Exception { assertTrue(invoke("/{{name}}/v1/invoke").contains(nameOf("{{name}}"))); }
    @Test void auxInvokeReturnsItsName() throws Exception { assertTrue(invoke("/{{name_aux}}/v1/invoke").contains(nameOf("{{name_aux}}"))); }
    @Test void gitignoreCoversLocalState() throws Exception { assertTrue(Files.readString(Path.of(".gitignore")).contains(".keel/")); }
    @Test void bothManifestsDeclareJava() throws Exception {
        assertTrue(Files.readString(Path.of("src/main/resources/{{name}}.yaml")).contains("language: java"));
        assertTrue(Files.readString(Path.of("src/main/resources/{{name_aux}}.yaml")).contains("language: java"));
    }

    private String nameOf(String value) { return value; }

    private String manifestName(String path) throws Exception {
        return MAPPER.readTree(body(get(path))).get("manifest").get("metadata").get("name").asText();
    }

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String invoke(String path) throws Exception {
        MvcResult started = mvc.perform(post(path).contentType("application/json").content("{\"input\":{\"text\":\"hi\"}}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getRequest().getAsyncContext().setTimeout(5_000);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
