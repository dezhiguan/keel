package com.keel.server.auth;

import java.time.Instant;

/** Remembers a client-assertion jti until it expires. A second use is authentication failure. */
public interface AssertionReplay {
    void remember(String jti, Instant expiresAt);
}
