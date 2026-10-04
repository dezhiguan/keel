package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Builds an agent.yaml preview. It does not write the registry. */
public final class ManifestPreview {
    private ManifestPreview() {}

    public static Result render(JsonNode form) {
        return RegisterManifest.render(form, 8080);
    }

    public record Result(String yaml, List<String> warnings) {}
}
