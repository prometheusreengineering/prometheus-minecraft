# prometheus-minecraft
The Prometheus' loader for Minecraft.

## Features
//TODO

## Compiling
Make sure you set your JDK to 16+ in IntelliJ's Project Structure. This is needed as we want to keep the target of Java 8 for maximum compatibility, but we also want to support modern Forge, whose libraries are compiled in Java 16.

## Adding patches
- Add your entry to `patches.json`
```json
{
    "name": "", // name of the patch (used for logging)
    "classPath": "", // classpath of the target (used to identify if the target of the patch is present)
    "repositoryName": "" // name of the github repository (used to download the patch from release)
}
```

## Disclaimer
This project is intended for educational purposes only. We are not responsible for any damage caused by this project.

## License
GPLv3 © Prometheus Reengineering
