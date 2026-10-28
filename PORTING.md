# Porting Radiance to Minecraft 26.3 (Fabric)

This branch ports Radiance 0.1.6 from Minecraft 1.21.4 to 26.3, with
[Voxy](https://modrinth.com/mod/voxy) support added on top. The Java mod lives
here; the native renderer is [MCVR](https://github.com/Minecraft-Radiance/MCVR)
and has to be ported alongside it.

## Where things stand

| Milestone | Status |
| --- | --- |
| M1 Build setup and Mojang names | Done: compiles on 1.21.4 with Mojang names, build targets 26.3 |
| M2 Render integration rewrite (Java) | Not started: ~1,000 compile errors in 97 files |
| M3 MCVR native port | Not started |
| M4 Vanilla world rendering in game | Not started |
| M5 Voxy integration | Not started |
| M6 Distant Horizons integration | Not started |
| M7 Release workflow | Not started |

## What changed in Minecraft between 1.21.4 and 26.3

These are the changes that drive the plan, checked against the 26.3 client jar.

- **No obfuscation.** 26.x ships with Mojang names, so there is no Yarn and no
  remapping. Loom is `net.fabricmc.fabric-loom` (1.19), dependencies use
  `implementation`, the access widener namespace is `official`, and Java 25 is
  required.
- **New rendering layer, "renderpearl".** `com.mojang.renderpearl` replaces the
  GL-shaped Blaze3D API that Radiance hooks today: `GpuDevice`, `RenderPipeline`,
  `RenderPass`, `GpuBuffer`, `VertexFormat` (moved to `renderpearl.api.vertex`).
  `GlStateManager`, `CompiledShaderProgram`, `Uniform`, `BufferUploader`,
  `VertexBuffer` and `ShaderProgram` are gone.
- **Vanilla has a Vulkan backend.** `renderpearl.backend.vulkan.VulkanDevice`
  exposes `vkDevice()`, `instance()`, graphics/compute/transfer queues and the
  `vma()` allocator handle. The backend is chosen with `PreferredGraphicsApi`.
- **SDL3 replaces GLFW for windowing.** `com.mojang.blaze3d.platform.Window` now
  calls `org.lwjgl.sdl`. MCVR creates its window surface and instance extensions
  through GLFW (`window.cpp`, `window_surface.cpp`, `instance.cpp`, `glfw_bind.cpp`).
- **World and entity rendering were restructured.** `RenderType` moved to
  `client.renderer.rendertype` (with `RenderTypes`), `MultiBufferSource` gave way
  to `SubmitNodeCollector` and the `feature` render dispatchers, `CompiledSection`
  became `SectionMesh`, `LightTexture` became `Lightmap`, and `GuiGraphics`,
  `ItemInHandRenderer`, `LiquidBlockRenderer`, `BlockRenderDispatcher` and
  `RenderChunkRegion` no longer exist under those names.
- **Smaller renames.** `ResourceLocation` is now `Identifier`.

The largest compile error clusters are `ChunkProxy` (87), `EntityProxy` (77),
`WorldRendererMixins` (60), the PBR vertex format classes (~90) and `Constants`
(42). javac reports name resolution first, so expect more errors as these clear.

## Key design decision: share vanilla's Vulkan device

On 1.21.4, Radiance runs a Vulkan renderer beside the game's OpenGL context.
It intercepts GL calls (`vulkan_render_integration`) or copies the GL UI into
Vulkan (`opengl_ui`), and MCVR owns its own instance, device and GLFW surface.

On 26.3 the recommended approach is:

1. Force vanilla onto its Vulkan backend.
2. Mixin into Vulkan device creation (`VulkanFeatureSets` / `VulkanPhysicalDevice`)
   to enable the extensions MCVR needs, such as acceleration structures, ray
   queries or ray tracing pipelines, buffer device address, and the DLSS/FSR/XeSS
   requirements.
3. Hand MCVR the vanilla `VkInstance`, `VkDevice`, queues and VMA allocator
   instead of creating its own. Drop MCVR's GLFW code paths and present through
   vanilla's surface.
4. Render the world in MCVR into an image that vanilla composites, and let vanilla
   draw the UI natively in Vulkan.

This removes the whole OpenGL/Vulkan interop layer, the `opengl_ui` backend, the
GLFW dependency in MCVR, and most of the GL-call interception mixins. That's
roughly 30 of the 52 render integration mixins. The remaining mixins focus on
capturing world geometry (chunk sections, entities, block entities, particles,
fluids, sky/clouds) and feeding it to MCVR.

The alternative is to keep MCVR's own device and copy across APIs. That needs
cross-device image sharing plus an SDL-based surface path, and keeps the
interop overhead. Only fall back to it if vanilla's device creation can't
be extended cleanly.

The `radianceUiBackend` build switch (`opengl`/`vulkan`) goes away once this
lands.

## Milestones

### M1 Build setup and Mojang names (done)

- Gradle 9.8.2, Loom 1.19, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3.
- Sources and access widener migrated from Yarn to Mojang names on 1.21.4 with
  Loom's `migrateMappings` / `migrateClassTweakerMappings`. Both UI backends
  compiled, and no Yarn names were left in mixin targets.
- MixinExtras dependency dropped (bundled in Fabric Loader).

### M2 Render integration rewrite (Java)

- Mechanical renames first: `Identifier`, the moved `RenderType`/`VertexFormat`
  packages, and the option and GUI screen APIs.
- Device bootstrap: force the Vulkan backend, extend its feature set, and pass
  the handles to MCVR over JNI (new `RendererProxy` entry points).
- Rewrite geometry capture against the 26.3 pipeline: `SectionMesh` and
  `SectionRenderDispatcher` for terrain, `SubmitNodeCollector` and the feature
  dispatchers for entities, block entities, items and particles, plus the new
  `Lightmap` and fog.
- Port the font and texture tracker mixins (`vanilla_resource_tracker`) to the
  renderpearl texture API.
- Port the settings screens to the 26.3 GUI API.
- Done when `./gradlew compileJava` passes and the mixin config applies
  without errors at game launch.

### M3 MCVR native port

- Regenerate JNI headers from the M2 Java sources.
- Replace instance, device and surface creation with handles adopted from
  vanilla. Remove GLFW.
- Build MCVR on Windows (MSVC 2022, Vulkan SDK, CMake) and Linux (GCC/Clang).
- Done when `core.dll`/`libcore.so` passes the build's native checks
  (`verifyNativeUiBackendVariant`, `verifyNativeProfilerVariant`) and loads at
  startup.

### M4 Vanilla world rendering in game

- Terrain, entities, block entities, particles, sky, water and the UI render
  correctly, along with DLSS/FSR/XeSS and the NRD denoiser.
- Needs in-game testing on ray tracing hardware. The development machine has an
  RTX 5090, so NVIDIA paths can be checked locally. AMD and Intel need outside
  testers.

### M5 Voxy integration

- Voxy 0.2.20-beta supports 26.3 on Fabric.
- The Distant Horizons integration
  (`client/mod_support/distant_horizons`, `mixins/mod_support/distant_horizons`,
  ~1,000 lines) intercepts DH's CPU-side LOD buffers, converts them to
  Radiance's 40-byte vertex format, and uploads them as MCVR geometry.
- Voxy builds and draws its LODs on the GPU, so there are probably no CPU
  buffers to intercept. Confirm against Voxy's 26.3 source before choosing one
  of these:
  1. Re-mesh Voxy's LOD data on the CPU into Radiance's vertex format. This is
     simplest and matches the DH path, but costs CPU time.
  2. Share Voxy's GPU buffers with MCVR. Since vanilla and MCVR share one Vulkan
     device after M2, this is a buffer handoff rather than cross-API interop,
     provided Voxy runs on the renderpearl API rather than raw OpenGL.
  3. Read back Voxy's GPU geometry. This is a fallback only.
- Voxy becomes an optional dependency (`compileOnly`, a `suggests` entry in
  `fabric.mod.json`) behind a mixin plugin that only applies when Voxy is loaded.
- Done when LODs show up in ray-traced lighting and reflections with no seams
  at the vanilla render distance boundary.

### M6 Distant Horizons integration

- Compiles against DH 3.3.4-26.3 (previously 3.3.1). Update the `breaks` range
  in `fabric.mod.json` and re-verify the DH mixin targets.

### M7 Release workflow

- A GitHub Actions workflow on tag push that:
  1. checks out this repo and MCVR at a pinned commit,
  2. installs the Vulkan SDK and builds MCVR on `windows-latest` and `ubuntu-latest`,
  3. runs `./gradlew build` per OS (the build packages the native library and
     verifies it),
  4. attaches `Radiance-<version>-fabric-26.3-{windows,linux}.jar` to a GitHub
     release.

## Building (current state)

```sh
./gradlew compileJava
```

`./gradlew build` still requires a native library at
`src/main/resources/core.dll` (or `libcore.so`), which won't exist until M3.

## Licence

Radiance and MCVR are GPL-3.0. This port keeps that licence and the original
authors' credits.
