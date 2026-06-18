# prometheus-minecraft
The Prometheus' loader for Minecraft.

![Compatibility: Infinite](https://img.shields.io/badge/COMPATIBILITY-<=1.12_>=1.18-0?style=for-the-badge)
[![Download Count](https://img.shields.io/github/downloads/prometheusreengineering/prometheus-minecraft/total?style=for-the-badge)](https://github.com/prometheusreengineering/prometheus-minecraft/releases/)
[![Discord](https://img.shields.io/discord/1197794960985043034?style=for-the-badge&label=Discord&color=rgb(88%2C%20101%2C%20242)%20)](https://discord.gg/BFDWmPfmXg)

## Features
- Analyzes classpath and environment to always load the right patches.
- Lightweight and slightly invasive due to the overcomplicated inner workings of modern Forge (1.18+).
- Serves as a patch autoupdater, downloading patches on demand (with cache) from GitHub releases.
- Compatible with all versions of Minecraft except 1.13-1.17.

## Compiling
Make sure you set your JDK to 16+ in IntelliJ's Project Structure. This is needed as we want to keep the target of Java 8 for maximum compatibility, but we also want to support modern Forge, whose libraries are compiled in Java 16. Be wary to not use Java 8+ features in the code, unless you know what you are doing.

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
