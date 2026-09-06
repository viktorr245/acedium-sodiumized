# Acedium Sodiumized

Acedium Sodiumized is an unofficial fork of Acedium for Minecraft 1.21.1 on NeoForge, maintained for Sodium 0.8.

It replaces Sodium's terrain renderer with an NVIDIA renderer based on Nvidium. It can improve performance when terrain rendering is the bottleneck, especially in scenes with lots of visible terrain. Results depend on your hardware, settings and other mods.

## Supported Versions

**Sodium 0.6 support is discontinued.** Existing releases remain available, but will receive no further updates or fixes. Future development targets Sodium 0.8 on NeoForge for Minecraft 1.21.1.

## How It Works

Acedium Sodiumized keeps terrain geometry in GPU buffers and uses NVIDIA features such as mesh shaders to let the GPU do more of the rendering work. Minecraft and Sodium still need to load and build chunks first.

Region Keep Distance can retain previously loaded terrain beyond the normal render distance. It does not generate unexplored terrain or request additional chunks from a server. Retaining more terrain uses more GPU memory.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Sodium for NeoForge, version 0.8.12 beta 1 or later **within the 0.8 series**
- **NVIDIA GTX 1600 series or newer**

Unsupported GPUs, including AMD, Intel, and older NVIDIA cards, cannot use the custom renderer. In that case, the mod should stay inactive and Sodium's normal renderer is used.

## Limitations

- Iris shader packs disable the custom renderer. This is expected.
- Some mod and driver combinations may be less compatible than standard Sodium because this renderer uses NVIDIA-specific OpenGL extensions.
- Only NeoForge 1.21.1 is currently targeted.

## Support

Before reporting a bug, reproduce it on the latest Acedium Sodiumized release for Sodium 0.8. Reports affecting only the discontinued Sodium 0.6 line will not receive fixes.

[Open an issue](https://github.com/viktorr245/acedium-sodiumized/issues) with your Minecraft, NeoForge, Sodium and Acedium versions, GPU and driver version, relevant mods, reproduction steps, and `latest.log` or a crash report. For rendering problems, include a screenshot or video and whether disabling Acedium changes the behavior.

## Credits

This project is unofficial and is not affiliated with the original Acedium or Nvidium authors.

- [Acedium Sodiumized](https://github.com/viktorr245/acedium-sodiumized)
- [Original Acedium fork](https://github.com/ferriarnus/acedium)
- [Original Nvidium project](https://github.com/MCRcortex/nvidium)
