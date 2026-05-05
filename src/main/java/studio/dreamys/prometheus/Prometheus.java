package studio.dreamys.prometheus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.spongepowered.asm.mixin.Mixins;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public abstract class Prometheus {
    public static final Gson gson = new Gson();
    public static final Logger logger = Logger.getLogger("Prometheus");

    protected abstract void addToClasspath0(Path jar);

    static {
        //overwrite aggressive environment logger settings
        Handler handler = new StandardOutputHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }

    protected void patch() {
        List<Patch> availablePatches = getAvailablePatches();
        logger.log(Level.INFO, String.format("Found %d available patches in remote repository", availablePatches.size()));

        List<Patch> applicablePatches = availablePatches.stream().filter(patch -> isClassPresent(patch.classPath)).collect(Collectors.toList());
        logger.log(Level.INFO, String.format("Found %d applicable patches for %s", applicablePatches.size(), applicablePatches.stream().map(patch -> patch.name).collect(Collectors.joining(", "))));

        for (Patch patch : applicablePatches) {
            String url = getLatestReleaseDownloadUrl(patch);
            Path file = downloadCachedFile(url);

            addToClasspath(file);

            addMixinConfigs(file);
        }
    }

    protected boolean isClassPresent(String classPath) {
        try {
            Class.forName(classPath, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    protected void addToClasspath(Path jar) {
        logger.log(Level.INFO, String.format("Attempting to add %s to classpath", jar));
        addToClasspath0(jar);
    }

    protected void addMixinConfigs(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".mixins.json")).forEach(Mixins::addConfiguration);

            logger.log(Level.INFO, "Successfully added mixin configs from " + jar);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read mixin configs from " + jar, e);
            throw new RuntimeException(e);
        }
    }

    protected List<Patch> getAvailablePatches() {
        try (InputStream inputStream = new URL("https://raw.githubusercontent.com/prometheusreengineering/prometheus-minecraft/main/patches.json").openConnection().getInputStream()) {
            return gson.fromJson(new InputStreamReader(inputStream), new TypeToken<List<Patch>>() {}.getType());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to fetch available patches from remote repository", e);
            throw new RuntimeException(e);
        }
    }

    protected String getLatestReleaseDownloadUrl(Patch patch) {
        try (InputStream inputStream = new URL(String.format("https://api.github.com/repos/prometheusreengineering/%s/releases/latest", patch.repositoryName)).openConnection().getInputStream()) {
            JsonObject body = gson.fromJson(new InputStreamReader(inputStream), JsonObject.class);
            JsonArray assets = body.getAsJsonArray("assets");

            return assets.get(0).getAsJsonObject().get("browser_download_url").getAsString();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to fetch latest release download url for " + patch.repositoryName, e);
            throw new RuntimeException(e);
        }
    }

    protected Path downloadCachedFile(String url) {
        try {
            logger.log(Level.INFO, "Preparing to download from " + url);

            Path cacheDir = Paths.get("prometheus", "cache");
            Files.createDirectories(cacheDir);

            String fileName = url.substring(url.lastIndexOf("/") + 1);
            Path filePath = cacheDir.resolve(fileName);
            if (Files.exists(filePath)) {
                logger.log(Level.INFO, String.format("File %s already exists in cache, skipping download", fileName));
                return filePath;
            }

            try (InputStream inputStream = new URL(url).openConnection().getInputStream()) {
                Files.copy(inputStream, filePath);
                logger.log(Level.INFO, "Successfully downloaded file to " + filePath);
            }

            return filePath;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to download file from " + url, e);
            throw new RuntimeException(e);
        }
    }
}