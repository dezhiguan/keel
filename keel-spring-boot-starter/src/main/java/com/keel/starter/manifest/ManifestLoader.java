package com.keel.starter.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.keel.common.model.AgentManifest;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
        List<String> missing = new ArrayList<>();
        if (manifest.spec().models() != null) {
            require("KEEL_LLM_BASE_URL", missing);
            require("KEEL_LLM_KEY", missing);
        }
        if (manifest.spec().knowledge() != null && !manifest.spec().knowledge().isEmpty()) {
            require("KEEL_RAGFORGE_URL", missing);
            require("KEEL_RAGFORGE_TOKEN", missing);
        }
        boolean audited = (manifest.spec().tools() != null && !manifest.spec().tools().isEmpty())
                || manifest.spec().audit() != null;
        if (audited) {
            require("KEEL_AUDIT_URL", missing);
            require("KEEL_AUDIT_TOKEN", missing);
            require("KEEL_AUDIT_SPOOL_PATH", missing);
            require("KEEL_ENV", missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable(s): " + String.join(", ", missing));
        }
    }

    private static void require(String name, List<String> missing) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) missing.add(name);
    }
}
