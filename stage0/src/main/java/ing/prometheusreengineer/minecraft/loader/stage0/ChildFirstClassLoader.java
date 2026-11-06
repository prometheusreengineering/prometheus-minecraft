package ing.prometheusreengineer.minecraft.loader.stage0;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Loads the core jar's classes (the core and minecraft-lib) from the jar first, and everything else (the JDK, the
 * game, the platform's APIs) from the hook's class loader.
 * <br>
 * Child first, because the core adds its own jar to the game's class path so patches can use the lib. With the normal
 * parent-first order, classes asked for after that would come from the game's copy and mix with the ones already
 * loaded from here, which breaks package-private access between them.
 */
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
                    // not in the core jar
                }
            }
            if (type == null) type = super.loadClass(name, false);
            if (resolve) resolveClass(type);
            return type;
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : super.getResource(name);
    }
}
