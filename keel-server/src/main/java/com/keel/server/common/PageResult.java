package com.keel.server.common;

import java.util.List;

/** PageMeta plus items, as used by every paged endpoint in console-api.openapi.yaml. */
public record PageResult<T>(long page, long size, long total, List<T> items) {}
