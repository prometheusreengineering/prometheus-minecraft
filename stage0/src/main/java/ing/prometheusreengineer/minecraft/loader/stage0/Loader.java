package ing.prometheusreengineer.minecraft.loader.stage0;

/**
 * The platform a hook runs on. {@link #id} is what the core sees as {@code platform}.
 */
public enum Loader {
    FABRIC("fabric"),
    LAUNCHWRAPPER("launchwrapper"),
    MODLAUNCHER("modlauncher"),
    NEOFORGE("neoforge");

    public final String id;

    Loader(String id) {
        this.id = id;
    }
}
