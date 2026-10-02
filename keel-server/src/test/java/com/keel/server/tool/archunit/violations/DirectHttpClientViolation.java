package com.keel.server.tool.archunit.violations;

import java.net.http.HttpClient;

public class DirectHttpClientViolation {
    public HttpClient create() {
        return HttpClient.newHttpClient();
    }
}
