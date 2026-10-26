package studio.dreamys.prometheus.core;

/**
 * One entry of patches.json. Only add fields: loaders from before stage 0 read the same file with Gson, which ignores
 * fields it doesn't know.
 */
public class Patch {
    /** For logging. */
    public String name;
    /** A class that only exists when the patch's target is installed. */
    public String classPath;
    /** The GitHub repo (under prometheusreengineering) whose latest release has the patch jar. */
    public String repositoryName;

    public Patch(String name, String classPath, String repositoryName) {
        this.name = name;
        this.classPath = classPath;
        this.repositoryName = repositoryName;
    }
}
