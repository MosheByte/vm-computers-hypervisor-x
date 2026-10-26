package net.hypervisorx.platform;

import net.hypervisorx.HyperVisorX;

import java.util.ServiceLoader;

/** Grabs the loader-specific service impls. */
public final class Services {
    public static final IPlatform PLATFORM = load(IPlatform.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        T service = ServiceLoader.load(clazz, Services.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation of " + clazz.getName() + " found"));
        HyperVisorX.LOG.debug("Loaded {} for service {}", service, clazz);
        return service;
    }
}
