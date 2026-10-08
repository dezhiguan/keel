package com.keel.server.auth;

import jakarta.servlet.http.HttpServletRequest;

public interface ServiceIdentity {
    /** True when the request is trying to authenticate as an agent, even if the assertion is bad. */
    boolean applies(HttpServletRequest request);

    void authenticate(HttpServletRequest request);
}
