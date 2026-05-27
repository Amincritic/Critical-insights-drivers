package org.mdpnp.simulator.launcher;

import java.lang.reflect.Method;

final class DefaultPackageMain {
    private DefaultPackageMain() { }

    static void invoke(String className, String[] args) {
        try {
            Class<?> target = Class.forName(className);
            Method main = target.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not launch " + className, e);
        }
    }
}
