package com.keel.starter.manifest;

import com.keel.common.model.AgentManifest;
import com.keel.starter.context.KeelContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

public final class AgentBinding {
    private final AgentManifest manifest;
    private final Object bean;
    private final Method entry;

    public AgentBinding(AgentManifest manifest, Object bean, Method entry) {
        this.manifest = manifest;
        this.bean = bean;
        this.entry = entry;
        this.entry.setAccessible(true);
    }

    public AgentManifest manifest() { return manifest; }

    public String name() { return manifest.metadata().name(); }

    public Object invoke(Map<String, Object> request, KeelContext context) throws Throwable {
        Class<?>[] types = entry.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (KeelContext.class.isAssignableFrom(types[i])) args[i] = context;
            else if (Map.class.isAssignableFrom(types[i])) args[i] = request;
            else throw new IllegalStateException("Unsupported @KeelEntry parameter: " + types[i].getName());
        }
        try {
            return entry.invoke(bean, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause() == null ? ex : ex.getCause();
        }
    }
}
