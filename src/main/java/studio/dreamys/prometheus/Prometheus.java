package studio.dreamys.prometheus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Prometheus implements PreLaunchEntrypoint {
    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger("Prometheus");

    static {
        //overwrite aggressive environment logger settings
        Handler handler = new StandardOutputHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }

    @Override
    public void onPreLaunch() {
        List<Patch> availablePatches = getAvailablePatches();
        logger.log(Level.INFO, String.format("Found %d available patches in remote repository", availablePatches.size()));

        List<Patch> applicablePatches = availablePatches.stream().filter(patch -> isClassPresent(patch.classPath)).collect(Collectors.toList());
        logger.log(Level.INFO, String.format("Found %d applicable patches for %s", applicablePatches.size(), applicablePatches.stream().map(patch -> patch.name).collect(Collectors.joining(", "))));

        for (Patch patch : applicablePatches) {
            String url = getLatestReleaseDownloadUrl(patch);
            Path file = downloadCachedFile(url);

            addToClasspath(file);

            Mixins.addConfiguration(String.format("prometheus.%s.mixins.json", patch.id));
        }
    }

    private boolean isClassPresent(String classPath) {
        try {
            Class.forName(classPath, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void addMixinConfigs(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".mixins.json")).forEach(Mixins::addConfiguration);

            logger.log(Level.INFO, "Successfully added mixin configs from " + jar);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read mixin configs from " + jar, e);
            throw new RuntimeException(e);
        }
    }

    private List<Patch> getAvailablePatches() {
        try (InputStream inputStream = new URL("https://raw.githubusercontent.com/prometheusreengineering/prometheus-minecraft/main/patches.json").openConnection().getInputStream()) {
            return gson.fromJson(new InputStreamReader(inputStream), new TypeToken<List<Patch>>() {}.getType());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to fetch available patches from remote repository", e);
            throw new RuntimeException(e);
        }
    }

    private String getLatestReleaseDownloadUrl(Patch patch) {
        try (InputStream inputStream = new URL(String.format("https://api.github.com/repos/prometheusreengineering/%s/releases/latest", patch.repositoryName)).openConnection().getInputStream()) {
            JsonObject body = gson.fromJson(new InputStreamReader(inputStream), JsonObject.class);
            JsonArray assets = body.getAsJsonArray("assets");

            return assets.get(0).getAsJsonObject().get("browser_download_url").getAsString();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to fetch latest release download url for " + patch.repositoryName, e);
            throw new RuntimeException(e);
        }
    }

    private Path downloadCachedFile(String url) {
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