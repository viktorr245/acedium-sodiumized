# Acedium Sodiumized

Acedium Sodiumized is an unofficial NeoForge 1.21.1 fork of Acedium, updated for Sodium 0.6.x.

It swaps Sodium's normal terrain renderer for a Nvidium-style NVIDIA renderer. The goal is to make high render distances and terrain-heavy scenes easier for your system to handle, not to boost every kind of workload.

## How It Works

Minecraft terrain is split into lots of chunks. Normally, the CPU still has to do a lot of work to get those chunks ready for rendering, even when Sodium makes that path much faster.

Acedium Sodiumized keeps terrain geometry in large GPU buffers and uses NVIDIA features such as mesh shaders to let the GPU do more of the rendering work. If terrain rendering is your bottleneck, this can reduce CPU overhead and improve FPS.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Sodium 0.6.x
- **NVIDIA GTX 1600 series or newer**

Unsupported GPUs, including AMD, Intel, and older NVIDIA cards, cannot use the custom renderer. In that case, the mod should stay inactive and Sodium's normal renderer is used.

## Limitations

- Iris shader packs disable the custom renderer. This is expected.
- Some mod and driver combinations may be less compatible than standard Sodium because this renderer uses NVIDIA-specific OpenGL extensions.
- Only NeoForge 1.21.1 is currently targeted.

## Known Current Incompatibilities

- Shine

## Support

If something is broken, feel free to open an issue on GitHub. I will try to reproduce it and fix it.

## Credits

This project is unofficial and is not affiliated with the original Acedium or Nvidium authors.

- This fork: https://github.com/viktorr245/acedium-sodiumized
- Original Acedium fork: https://github.com/ferriarnus/acedium
- Original Nvidium project: https://github.com/MCRcortex/nvidium
