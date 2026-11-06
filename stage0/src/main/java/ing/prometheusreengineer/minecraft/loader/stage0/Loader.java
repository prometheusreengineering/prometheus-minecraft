package ing.prometheusreengineer.minecraft.loader.stage0;

public enum Loader {
    FABRIC("fabric"),
    LAUNCHWRAPPER("launchwrapper"),
    MODLAUNCHER("modlauncher"),
    NEOFORGE("neoforge"),
    UNKNOWN("unknown");

    public final String id;

    Loader(String id) {
        this.id = id;
    }
}
