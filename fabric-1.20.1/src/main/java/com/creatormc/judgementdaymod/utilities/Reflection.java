package com.creatormc.judgementdaymod.utilities;

import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

/**
 * Finds a Minecraft method by its intermediary name, the stable name Fabric runs the game
 * with (in a development environment the game runs with the Mojang names: the loader maps
 * the name to the one the game runs with). As Forge's ObfuscationReflectionHelper did with
 * the SRG names.
 */
public final class Reflection {
    private Reflection() {
    }

    /**
     * The method of owner (declared there) with the given intermediary owner class and
     * name, return type and parameter types.
     */
    public static Method findMethod(Class<?> owner, String intermediaryOwner, String intermediaryName,
            Class<?> returnType, Class<?>... parameterTypes) throws NoSuchMethodException {
        final MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        final StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> type : parameterTypes)
            descriptor.append(intermediaryDescriptor(resolver, type));
        descriptor.append(')').append(intermediaryDescriptor(resolver, returnType));
        final String name = resolver.mapMethodName("intermediary", intermediaryOwner, intermediaryName,
                descriptor.toString());
        final Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static String intermediaryDescriptor(MappingResolver resolver, Class<?> type) {
        if (type.isArray())
            return "[" + intermediaryDescriptor(resolver, type.getComponentType());
        if (type == void.class)
            return "V";
        if (type == boolean.class)
            return "Z";
        if (type == int.class)
            return "I";
        if (type == float.class)
            return "F";
        if (type == double.class)
            return "D";
        if (type == long.class)
            return "J";
        if (type == byte.class)
            return "B";
        if (type == short.class)
            return "S";
        if (type == char.class)
            return "C";
        return "L" + resolver.unmapClassName("intermediary", type.getName()).replace('.', '/') + ";";
    }
}
