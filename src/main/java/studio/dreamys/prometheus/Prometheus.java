package studio.dreamys.prometheus;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.spongepowered.asm.mixin.Mixins;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class Prometheus implements PreLaunchEntrypoint {
    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger("Prometheus");

    static {
        //overwrite environment logger settings
        Handler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
    }

    @Override
    public void onPreLaunch() {
        Set<String> modIds = FabricLoader.getInstance().getAllMods().stream().map(modContainer -> modContainer.getMetadata().getId()).collect(Collectors.toSet());

        List<Patch> availablePatches = getAvailablePatches();
        List<Patch> applicablePatches = availablePatches.stream().filter(patch -> modIds.contains(patch.modId)).collect(Collectors.toList());

        for (Patch patch : applicablePatches) {
            String url = getLatestReleaseDownloadUrl(patch);
            Path file = downloadCachedFile(url);

            addToClasspath(file);

            Mixins.addConfiguration(String.format("prometheus.%s.mixins.json", patch.modId));
        }
    }

    private void addToClasspath(Path jar) {
        try {
            ClassLoader classLoader = FabricLoader.getInstance().getClass().getClassLoader();

            if (classLoader instanceof URLClassLoader) {
                Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                method.setAccessible(true);
                method.invoke(classLoader, jar.toUri().toURL());
                logger.log(Level.INFO, String.format("Successfully added %s classpath", jar));
            } else {
                logger.log(Level.SEVERE, "Unsupported class loader " + classLoader.getClass().getName());
                throw new RuntimeException("Unsupported class loader " + classLoader.getClass().getName());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, String.format("Failed to add %s to classpath", jar), e);
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

            Path cacheDir = FabricLoader.getInstance().getGameDir().resolve("prometheus-cache");
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