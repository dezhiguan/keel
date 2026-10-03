package com.keel.starter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.model.SuspendEvent;
import com.keel.starter.annotation.KeelAgent;
import com.keel.starter.annotation.KeelEntry;
import com.keel.starter.context.KeelContext;
import com.keel.starter.contract.SchemaSubset;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

class ProtocolEndpointTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Talker.class)
    static class SingleApp {}

    @KeelAgent(manifest = "classpath:agents/alpha.yaml")
    public static class Talker {
        static volatile CountDownLatch go = new CountDownLatch(0);

        @KeelEntry
        public Object run(Map<String, Object> request, KeelContext ctx) throws Exception {
            go.await(5, TimeUnit.SECONDS);
            String text = String.valueOf(((Map<?, ?>) request.get("input")).get("text"));
            if ("boom".equals(text)) throw new IllegalStateException("user secret");
            if ("suspend".equals(text)) {
                return ctx.suspend(SuspendEvent.SuspendEventReasonValue.APPROVAL, "ap_1", "approve?", null);
            }
            ctx.step("prepare");
            ctx.token("world");
            return ctx.finish("world");
        }
    }

    @Nested
    @SpringBootTest(classes = SingleApp.class, properties = {
            "keel.manifest=classpath:agents/alpha.yaml",
            "spring.mvc.async.request-timeout=100"
    })
    @AutoConfigureMockMvc
    class SingleAgent {
        @Autowired MockMvc mvc;

        @Test
        void invokeHealthManifestFeedbackAndRuns() throws Exception {
            List<JsonNode> frames = frames(invoke("hello"));
            assertEquals(List.of("step", "token", "final"), frames.stream().map(frame -> frame.get("event").asText()).toList());
            assertEquals("world", frames.get(2).get("data").get("answer").asText());
            assertFalse(frames.get(2).get("data").get("run_id").asText().isBlank());
            validate(frames);

            assertEquals("alpha", MAPPER.readTree(mvc.perform(get("/v1/health")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("agent").asText());
            JsonNode manifest = MAPPER.readTree(mvc.perform(get("/v1/manifest")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("alpha", manifest.get("manifest").get("metadata").get("name").asText());
            assertEquals("keel/v1", manifest.get("version").asText());

            mvc.perform(post("/v1/feedback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"trace_id\":\"trace-1\",\"value\":1}")).andExpect(status().isNoContent());
            mvc.perform(post("/v1/feedback").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"trace_id\":\"trace-1\",\"value\":0}")).andExpect(status().isBadRequest());
            mvc.perform(get("/v1/runs/missing")).andExpect(status().isNotFound());
            mvc.perform(post("/v1/runs/missing/resume").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/v1/invoke").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void businessFailureIsAnErrorEventAndSuspendCompletes() throws Exception {
            List<JsonNode> failed = frames(invoke("boom"));
            assertEquals("error", failed.get(0).get("event").asText());
            assertFalse(failed.get(0).toString().contains("user secret"));
            validate(failed);
            List<JsonNode> suspended = frames(invoke("suspend"));
            assertEquals("suspend", suspended.get(0).get("event").asText());
            assertEquals("approval", suspended.get(0).get("data").get("reason").asText());
            validate(suspended);
        }

        @Test
        void asyncTimeoutDoesNotDropTheStream() throws Exception {
            Talker.go = new CountDownLatch(1);
            MvcResult result = mvc.perform(post("/v1/invoke").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"input\":{\"text\":\"hello\"}}"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            Thread.sleep(150);
            assertTrue(result.getRequest().isAsyncStarted());
            Talker.go.countDown();
            result.getRequest().getAsyncContext().setTimeout(5_000);
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertTrue(body.contains("event:final") || body.contains("event: final"));
            Talker.go = new CountDownLatch(0);
        }

        private MvcResult invoke(String text) throws Exception {
            Talker.go = new CountDownLatch(1);
            MvcResult result = mvc.perform(post("/v1/invoke").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"input\":{\"text\":\"" + text + "\"}}"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            Talker.go.countDown();
            return result;
        }

        private List<JsonNode> frames(MvcResult started) throws Exception {
            started.getRequest().getAsyncContext().setTimeout(5_000);
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(started))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<JsonNode> frames = new ArrayList<>();
            for (String block : body.strip().split("\n\n")) {
                String event = null;
                String data = null;
                for (String line : block.split("\n")) {
                    if (line.startsWith("event:")) event = line.substring("event:".length()).trim();
                    if (line.startsWith("data:")) data = line.substring("data:".length()).trim();
                }
                ObjectNode frame = MAPPER.createObjectNode();
                frame.put("event", event);
                frame.set("data", MAPPER.readTree(data));
                frames.add(frame);
            }
            return frames;
        }
    }

    @Nested
    @SpringBootTest(classes = ProtocolEndpointTest.TwoAgents.MultiApp.class, properties = "keel.manifest=classpath:agents/alpha.yaml")
    @AutoConfigureMockMvc
    class TwoAgents {
        @Autowired MockMvc mvc;

        @SpringBootConfiguration
        @EnableAutoConfiguration
        @Import({Alpha.class, Bravo.class})
        static class MultiApp {}

        @KeelAgent(manifest = "classpath:agents/alpha.yaml")
        public static class Alpha {
            @KeelEntry
            public Object run(KeelContext ctx) { return ctx.finish(ctx.agent()); }
        }

        @KeelAgent(manifest = "classpath:agents/bravo.yaml")
        public static class Bravo {
            @KeelEntry
            public Object run(KeelContext ctx) { return ctx.finish(ctx.agent()); }
        }

        @Test
        void eachAgentServesItsOwnManifest() throws Exception {
            assertEquals("alpha", name("/alpha/v1/manifest"));
            assertEquals("bravo", name("/bravo/v1/manifest"));
            mvc.perform(get("/v1/manifest")).andExpect(status().isNotFound());
        }

        private String name(String path) throws Exception {
            return MAPPER.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("manifest").get("metadata").get("name").asText();
        }
    }

    @Nested
    @SpringBootTest(classes = ProtocolEndpointTest.WithoutManifest.PlainApp.class)
    @AutoConfigureMockMvc
    class WithoutManifest {
        @Autowired MockMvc mvc;

        @SpringBootConfiguration
        @EnableAutoConfiguration
        static class PlainApp {}

        @Test
        void invokeIsAbsent() throws Exception {
            mvc.perform(post("/v1/invoke").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"input\":{\"text\":\"hi\"}}")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @SpringBootTest(classes = ProtocolEndpointTest.ClientDisconnect.DisconnectApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "keel.manifest=classpath:agents/alpha.yaml")
    class ClientDisconnect {
        static final CountDownLatch started = new CountDownLatch(1);
        static final CountDownLatch hold = new CountDownLatch(1);

        @LocalServerPort int port;
        @Autowired KeelSessions sessions;

        @SpringBootConfiguration
        @EnableAutoConfiguration
        @Import(Waiter.class)
        static class DisconnectApp {}

        @KeelAgent(manifest = "classpath:agents/alpha.yaml")
        public static class Waiter {
            @KeelEntry
            public Object run(Map<String, Object> request, KeelContext ctx) throws Exception {
                ctx.step("wait");
                started.countDown();
                hold.await(5, TimeUnit.SECONDS);
                return ctx.finish("done");
            }
        }

        @Test
        void completionReleasesTheRun() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/invoke"))
                    .timeout(Duration.ofSeconds(5))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"text\":\"hi\"}}"))
                    .build();
            AtomicBoolean closed = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                try {
                    HttpResponse<java.io.InputStream> response = HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofInputStream());
                    response.body().readNBytes(16);
                    closed.set(true);
                    response.body().close();
                } catch (Exception ignored) {
                    closed.set(true);
                }
            });
            reader.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            reader.join(2000);
            assertTrue(closed.get());
            hold.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (sessions.size() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0, sessions.size());
        }
    }

    @Test
    void hostRouteConflictFailsStartup() {
        SpringApplication app = new SpringApplication(ConflictApp.class);
        app.setDefaultProperties(Map.of(
                "keel.manifest", "classpath:agents/alpha.yaml",
                "server.port", "0"));
        Exception failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, app::run);
        boolean found = false;
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor.getMessage() != null && cursor.getMessage().contains("Route conflict: /v1/health")) found = true;
        }
        assertTrue(found, failure.toString());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ConflictAgent.class, Host.class})
    static class ConflictApp {}

    @KeelAgent(manifest = "classpath:agents/alpha.yaml")
    public static class ConflictAgent {
        @KeelEntry
        public Object run(KeelContext ctx) { return ctx.finish("ok"); }
    }

    @RestController
    static class Host {
        @GetMapping("/v1/health")
        String health() { return "host"; }
    }

    @Test
    void sourcesDoNotContainErrorCodeLiterals() throws Exception {
        Pattern literal = Pattern.compile("\"(?:SERVER|GW|TOOL|APPROVAL|RUN|AUDIT|GUARD|DELEGATE)_[A-Z0-9_]+\"");
        Path root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                var matcher = literal.matcher(source);
                if (matcher.find()) fail(file + " contains " + matcher.group());
            }
        }
    }

    private static void validate(List<JsonNode> frames) throws Exception {
        JsonNode schema = MAPPER.readTree(Path.of("..", "contracts", "sse-events.schema.json").toFile());
        SchemaSubset.assertSupported(schema);
        for (JsonNode frame : frames) {
            assertTrue(SchemaSubset.errors(schema, schema, frame).isEmpty(), frame.toString());
        }
    }
}
