package ing.prometheusreengineer.minecraft.loader.core;

import java.util.List;

/**
 * One entry of patches.json, read with Gson.
 */
public class Patch {
    /**
     * For logging.
     */
    public String name;
    /**
     * Classes that only exist when the patch's target is installed. Any one of them is enough: a target usually has a
     * different entry class on each platform (Essential has one for Fabric, LaunchWrapper and ModLauncher).
     */
    public List<String> classPaths;
    /**
     * The GitHub repo (under prometheusreengineering) whose latest release has the patch jar.
     */
    public String repositoryName;

    public Patch(String name, List<String> classPaths, String repositoryName) {
        this.name = name;
        this.classPaths = classPaths;
        this.repositoryName = repositoryName;
    }
}
