package com.mrfdev.walktheplank.scenario.harness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.bukkit.plugin.Plugin;

/**
 * Strict reflection boundary for the bridge injected into the test-only target artifact.
 *
 * <p>The production plugin must not contain this bridge. Requiring it here makes accidentally
 * running destructive scenarios against a production artifact fail closed.</p>
 */
final class ScenarioFailpointBridge {
    static final String BRIDGE_CLASS =
            "com.mrfdev.walktheplank.scenario.instrumentation.ScenarioFailpoints";

    private final Method arm;
    private final Method clear;
    private final Method status;
    private final Method release;

    private ScenarioFailpointBridge(
            Method arm,
            Method clear,
            Method status,
            Method release) {
        this.arm = arm;
        this.clear = clear;
        this.status = status;
        this.release = release;
    }

    static ScenarioFailpointBridge load(Plugin target) throws ReflectiveOperationException {
        Objects.requireNonNull(target, "target");
        ClassLoader targetLoader = target.getClass().getClassLoader();
        Class<?> bridgeType = Class.forName(BRIDGE_CLASS, false, targetLoader);
        if (bridgeType.getClassLoader() != targetLoader) {
            throw new ClassNotFoundException(
                    "Scenario bridge was not defined by the target plugin classloader");
        }

        Method armMethod =
                bridgeType.getMethod("arm", String.class, String.class, int.class, String.class);
        Method clearMethod = bridgeType.getMethod("clear");
        Method statusMethod = bridgeType.getMethod("status");
        Method releaseMethod = bridgeType.getMethod("release", String.class);
        requirePublicStaticVoid(armMethod);
        requirePublicStaticVoid(clearMethod);
        requirePublicStaticVoid(releaseMethod);
        requirePublicStatic(statusMethod);
        if (statusMethod.getReturnType() != String.class) {
            throw new NoSuchMethodException("ScenarioFailpoints.status() must return String");
        }
        return new ScenarioFailpointBridge(
                armMethod,
                clearMethod,
                statusMethod,
                releaseMethod);
    }

    void arm(String point, String action, int occurrence, String nonce)
            throws ReflectiveOperationException {
        invoke(arm, point, action, occurrence, nonce);
    }

    void clear() throws ReflectiveOperationException {
        invoke(clear);
    }

    String status() throws ReflectiveOperationException {
        Object result = invoke(status);
        if (!(result instanceof String text)) {
            throw new InvocationTargetException(
                    new IllegalStateException("ScenarioFailpoints.status() returned null"));
        }
        return text;
    }

    void release(String nonce) throws ReflectiveOperationException {
        invoke(release, nonce);
    }

    private static Object invoke(Method method, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(null, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvocationTargetException(exception);
        }
    }

    private static void requirePublicStaticVoid(Method method) throws NoSuchMethodException {
        requirePublicStatic(method);
        if (method.getReturnType() != void.class) {
            throw new NoSuchMethodException(method.getName() + " must return void");
        }
    }

    private static void requirePublicStatic(Method method) throws NoSuchMethodException {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
            throw new NoSuchMethodException(method.getName() + " must be public static");
        }
    }
}
