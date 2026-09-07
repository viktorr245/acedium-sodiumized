# Acedium Sodiumized

Acedium Sodiumized is an unofficial fork of Acedium for Minecraft 1.21.1 on NeoForge, maintained for Sodium 0.8.

It replaces Sodium’s terrain renderer with an NVIDIA renderer based on Nvidium. It can improve performance when terrain rendering is the bottleneck, especially in scenes with lots of visible terrain. Results depend on your hardware, settings and other mods.

## Supported Versions

I aim to update Acedium Sodiumized when a new stable Sodium version becomes available for Minecraft 1.21.1 on NeoForge.

**The current supported Sodium version is [0.8.13 for NeoForge 1.21.1](https://modrinth.com/mod/sodium/version/uMOpc5uV).** Acedium will allow newer Sodium 0.8 versions to load, but they may not work until I update Acedium for them. Allowing a version to load does not mean it is supported.

**Sodium 0.6 support is discontinued.** Existing releases remain available, but will receive no further updates or fixes.

## How It Works

Acedium Sodiumized keeps terrain geometry in GPU buffers and uses NVIDIA features such as mesh shaders to let the GPU do more of the rendering work. Minecraft and Sodium still need to load and build chunks first.

Region Keep Distance can retain previously loaded terrain beyond the normal render distance. It does not generate unexplored terrain or request additional chunks from a server.

Keeping more terrain loaded uses more VRAM. If it runs out of space, Acedium unloads some terrain, even with the Keep All setting enabled.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Sodium **0.8.13 for NeoForge 1.21.1** is the supported version; newer 0.8 versions may load but are not yet supported
- **NVIDIA GTX 1600 series or newer**, with a driver supporting the required NVIDIA OpenGL extensions

Acedium checks the required OpenGL capabilities at startup. If they are unavailable, its custom renderer stays disabled and Sodium’s normal renderer is used. AMD, Intel and older NVIDIA GPUs are not supported by the custom renderer.

## Limitations

- Enabling an Iris shader pack disables the custom renderer.
- Compatibility with other rendering mods depends on how they interact with the terrain renderer.
- Only Minecraft 1.21.1 on NeoForge is currently targeted.

## Support

Before reporting a bug, reproduce it on the latest Acedium Sodiumized release using the supported Sodium version listed above.

[Open an issue](https://github.com/viktorr245/acedium-sodiumized/issues) with your Minecraft, NeoForge, Sodium and Acedium versions, GPU and driver version, relevant mods, reproduction steps, and `latest.log` or a crash report. For rendering problems, include a screenshot or video and whether disabling Acedium changes the behavior.

## Credits

This project is unofficial and is not affiliated with the original Acedium or Nvidium authors.

- [Acedium Sodiumized](https://github.com/viktorr245/acedium-sodiumized)
- [Original Acedium fork](https://github.com/ferriarnus/acedium)
- [Original Nvidium project](https://github.com/MCRcortex/nvidium)
