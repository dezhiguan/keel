package com.keel.starter.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.keel.common.model.AgentManifest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

public class ManifestLoader {
    private final ResourceLoader loader;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    public ManifestLoader(ResourceLoader loader) {
        this.loader = loader;
    }

    public AgentManifest load(String location) {
        Resource resource = loader.getResource(location);
        if (!resource.exists()) throw new IllegalStateException("Missing manifest: " + location);
        try (InputStream input = resource.getInputStream()) {
            AgentManifest manifest = yaml.readValue(input, AgentManifest.class);
            if (manifest.apiVersion() == null || manifest.metadata() == null
                    || manifest.metadata().name() == null || manifest.metadata().name().isBlank()
                    || manifest.spec() == null || manifest.spec().runtime() == null
                    || manifest.spec().runtime().endpoint() == null) {
                throw new IllegalStateException("Manifest " + location + " is missing apiVersion, metadata.name, or runtime.endpoint");
            }
            return manifest;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read manifest " + location, ex);
        }
    }

    /** Same required variables as the Python SDK. Absence fails startup; nothing is defaulted. */
    public void requireEnvironment(AgentManifest manifest) {
        requireEnvironment(manifest, System::getenv);
    }

    void requireEnvironment(AgentManifest manifest, Function<String, String> env) {
        String mode = blank(env.apply("KEEL_MODE")) ? "governed" : env.apply("KEEL_MODE").trim();
        if (!mode.equals("governed") && !mode.equals("standalone")) {
            throw new IllegalStateException("KEEL_MODE must be governed or standalone");
        }
        if (mode.equals("standalone") && production(env)) {
            throw new IllegalStateException("KEEL_MODE=standalone is forbidden in the production profile");
        }
        List<String> missing = new ArrayList<>();
        if (mode.equals("standalone")) {
            if (manifest.spec().models() != null) {
                require(env, "KEEL_LLM_BASE_URL", missing);
            }
            String keelEnv = env.apply("KEEL_ENV");
            if (keelEnv == null || !keelEnv.equals("dev") && !keelEnv.equals("test") && !keelEnv.equals("staging")) {
                missing.add("KEEL_ENV");
            }
        } else {
            if (manifest.spec().models() != null) {
                require(env, "KEEL_LLM_BASE_URL", missing);
                require(env, "KEEL_LLM_KEY", missing);
            }
            if (manifest.spec().knowledge() != null && !manifest.spec().knowledge().isEmpty()) {
                require(env, "KEEL_RAGFORGE_URL", missing);
                require(env, "KEEL_RAGFORGE_TOKEN", missing);
            }
            boolean audited = (manifest.spec().tools() != null && !manifest.spec().tools().isEmpty())
                    || manifest.spec().audit() != null;
            if (audited) {
                require(env, "KEEL_AUDIT_URL", missing);
                require(env, "KEEL_AUDIT_TOKEN", missing);
                require(env, "KEEL_AUDIT_SPOOL_PATH", missing);
                require(env, "KEEL_ENV", missing);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable(s): " + String.join(", ", missing));
        }
    }

    private static boolean production(Function<String, String> env) {
        if ("prod".equals(env.apply("KEEL_ENV"))) return true;
        String profiles = env.apply("SPRING_PROFILES_ACTIVE");
        if (profiles == null) return false;
        for (String profile : profiles.split(",")) {
            if ("prod".equals(profile.trim())) return true;
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void require(Function<String, String> env, String name, List<String> missing) {
        if (blank(env.apply(name))) missing.add(name);
    }
}
