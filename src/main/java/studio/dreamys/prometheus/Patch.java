package studio.dreamys.prometheus;

public class Patch {
    public String name;
    public String id;
    public String classPath;
    public String repositoryName;

    public Patch(String name, String id, String classPath, String repositoryName) {
        this.name = name;
        this.id = id;
        this.classPath = classPath;
        this.repositoryName = repositoryName;
    }
}
