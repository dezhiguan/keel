package com.keel.starter.manifest;

import com.keel.common.error.ErrorCode;
import com.keel.starter.web.KeelException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentRegistry {
    private final Map<String, AgentBinding> agents = new LinkedHashMap<>();

    public void add(AgentBinding binding) {
        if (agents.putIfAbsent(binding.name(), binding) != null) {
            throw new IllegalStateException("Duplicate agent name: " + binding.name());
        }
    }

    public Collection<AgentBinding> all() { return agents.values(); }

    public boolean single() { return agents.size() == 1; }

    public AgentBinding resolve(HttpServletRequest request) {
        if (agents.isEmpty()) throw new KeelException(ErrorCode.SERVER_NOT_FOUND);
        if (agents.size() == 1) return agents.values().iterator().next();
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        String[] parts = path.split("/");
        if (parts.length < 2) throw new KeelException(ErrorCode.SERVER_NOT_FOUND);
        AgentBinding binding = agents.get(parts[1]);
        if (binding == null) throw new KeelException(ErrorCode.SERVER_NOT_FOUND);
        return binding;
    }

    public String prefix(AgentBinding binding) {
        return agents.size() == 1 ? "" : "/" + binding.name();
    }
}
