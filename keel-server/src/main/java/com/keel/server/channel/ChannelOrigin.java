package com.keel.server.channel;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.Collection;

/** Embed and web channels only accept an origin that was published for the agent. */
public final class ChannelOrigin {
    private ChannelOrigin() {}

    public static void require(String origin, Collection<String> allowed) {
        if (origin == null || origin.isBlank() || allowed.stream().noneMatch(origin::equals)) {
            throw new KeelException(ErrorCode.CHANNEL_ORIGIN_DENIED, ErrorCode.CHANNEL_ORIGIN_DENIED.message());
        }
    }
}
