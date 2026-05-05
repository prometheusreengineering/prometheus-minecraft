package studio.dreamys.prometheus;

public class Patch {
    public String name;
    public String classPath;
    public String repositoryName;

    public Patch(String name, String classPath, String repositoryName) {
        this.name = name;
        this.classPath = classPath;
        this.repositoryName = repositoryName;
    }
}
