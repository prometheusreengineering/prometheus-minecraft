package studio.dreamys.prometheus.stage0.modlauncher;

import studio.dreamys.prometheus.stage0.Stage0Log;

import java.net.URL;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ModLauncher 4-8 (Forge 1.15-1.16): what puts the patch jars in the game's class loader there.
 * <br>
 * {@code runScan}'s jars are only offered to launch plugins (Mixin reads their configs from them), not added to the
 * {@code TransformingClassLoader}, so Mixin found the configs but not the mixin classes. ModLauncher 4-10 instead ask
 * each service for {@code additionalClassesLocator} and {@code additionalResourcesLocator} when they build that class
 * loader (OptiFine used them on 1.14-1.16).
 * <br>
 * ModLauncher rejects the whole launch ({@code IllegalArgumentException}) on a prefix that is malformed, under
 * {@code net.minecraft.} or {@code net.minecraftforge.}, or claimed by another service, so this only claims the exact
 * packages and resource names in our jars, and leaves out the ones ModLauncher would refuse. Only JDK types, so it
 * loads on every ModLauncher version. Never throws.
 */
final class LegacyLocator {
    /**
     * Entry name (a/b/C.class, a/b.json) to the jar that has it. The first jar wins.
     */
    private final Map<String, Path> entries = new HashMap<>();
    private final Set<String> packages = new LinkedHashSet<>();
    private final Set<String> resources = new LinkedHashSet<>();

    LegacyLocator(List<Path> jars) {
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> all = file.entries();
                while (all.hasMoreElements()) add(all.nextElement(), jar);
            } catch (Throwable t) {
                Stage0Log.warn("could not index %s for the class loader: %s", jar.getFileName(), t);
            }
        }
        Stage0Log.info("ModLauncher 4-8: serving %d packages and %d resources from %d jars", packages.size(), resources.size(), jars.size());
    }

    private void add(JarEntry entry, Path jar) {
        String name = entry.getName();
        if (entry.isDirectory() || name.startsWith("META-INF/") || name.startsWith("/")) return;

        if (name.endsWith(".class")) {
            int slash = name.lastIndexOf('/');
            if (slash <= 0) return; // the default package can't be a prefix
            String prefix = name.substring(0, slash + 1).replace('/', '.');
            if (prefix.startsWith("net.minecraft.") || prefix.startsWith("net.minecraftforge.") || prefix.startsWith("."))
                return;
            packages.add(prefix);
        } else {
            // pack.mcmeta, mcmod.info, logo.png... are in many jars, and a name another service claims is fatal.
            // root-level json stays: mixin configs and refmaps are there
            if (name.indexOf('/') < 0 && (!name.endsWith(".json") || name.equals("fabric.mod.json"))) return;
            resources.add(name);
        }
        if (!entries.containsKey(name)) entries.put(name, jar);
    }

    /**
     * null when there's nothing to serve, which ModLauncher skips.
     */
    Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> classes() {
        return entry(unclaimed(packages, "classPrefixes"));
    }

    Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> resources() {
        return entry(unclaimed(resources, "resourceNames"));
    }

    private Map.Entry<Set<String>, Supplier<Function<String, Optional<URL>>>> entry(Set<String> keys) {
        if (keys.isEmpty()) return null;
        Function<String, Optional<URL>> lookup = this::find;
        Supplier<Function<String, Optional<URL>>> supplier = () -> lookup;
        return new AbstractMap.SimpleImmutableEntry<>(Collections.unmodifiableSet(keys), supplier);
    }

    /**
     * Leaves out what services before us already claimed (ModLauncher keeps them in a static set on
     * TransformationServiceDecorator). Best effort: services after us still see ours, and if the field can't be read,
     * everything is kept.
     */
    private static Set<String> unclaimed(Set<String> ours, String field) {
        Set<String> result = new LinkedHashSet<>(ours);
        try {
            Class<?> decorator = Class.forName("cpw.mods.modlauncher.TransformationServiceDecorator", false, LegacyLocator.class.getClassLoader());
            java.lang.reflect.Field claimed = decorator.getDeclaredField(field);
            claimed.setAccessible(true);
            Object value = claimed.get(null);
            if (value instanceof Set) {
                for (Object name : (Set<?>) value) {
                    //noinspection SuspiciousMethodCalls nothing suspicious here
                    if (result.remove(name))
                        Stage0Log.warn("%s is already claimed by another ModLauncher service, skipping it", name);
                }
            }
        } catch (Throwable t) {
            Stage0Log.info("could not read ModLauncher's %s, keeping all ours: %s", field, t);
        }
        return result;
    }

    /**
     * ModLauncher passes entry names: a/b/C.class for classes, the exact name for resources.
     */
    private Optional<URL> find(String name) {
        Path jar = entries.get(name);
        if (jar == null) return Optional.empty();
        try {
            return Optional.of(new URL("jar:" + jar.toUri().toURL() + "!/" + name));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }
}
