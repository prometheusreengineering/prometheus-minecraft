package ing.prometheusreengineer.minecraft.loader.stage0;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A copy of minecraft-lib's ChildFirstClassLoader, so that we can load that one.
 */
@NotNullByDefault
final class ChildFirstClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    ChildFirstClassLoader(Path jar, ClassLoader parent) throws MalformedURLException {
        super(new URL[]{jar.toUri().toURL()}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null && !name.startsWith("java.")) {
                try {
                    type = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    // not in the lib jar
                }
            }
            if (type == null) type = super.loadClass(name, false);
            if (resolve) resolveClass(type);
            return type;
        }
    }

    @Override
    public @Nullable URL getResource(String name) {
        return Optional.ofNullable(findResource(name))
                .orElseGet(() -> super.getResource(name));
    }
}
