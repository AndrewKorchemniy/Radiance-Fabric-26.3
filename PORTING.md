# Porting Radiance to Minecraft 26.3 (Fabric)

This branch ports Radiance 0.1.6 from Minecraft 1.21.4 to 26.3, with
[Voxy](https://modrinth.com/mod/voxy) support added on top. The Java mod lives
here; the native renderer is [MCVR](https://github.com/Minecraft-Radiance/MCVR)
and has to be ported alongside it.

## Where things stand

| Milestone | Status |
| --- | --- |
| M1 Build setup and Mojang names | Done: compiles on 1.21.4 with Mojang names, build targets 26.3 |
| M2 Render integration rewrite (Java) | Done: `compileJava` passes and every mixin applies (`./gradlew runAuditMixins`). Not run in game: that needs M3 |
| M3 MCVR native port | Not started. Native-side requirements collected below under "What M3 has to change" |
| M4 Vanilla world rendering in game | Not started |
| M5 Voxy integration | Java side written and audited with and without DH; in-game untested. Needs native work (tile alpha) and a Sodium terrain decision, see M5 |
| M6 Distant Horizons integration | Mixins and vertex layout verified; in-game untested |
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

On 26.3, Radiance runs on vanilla's Vulkan device instead. Vanilla keeps drawing
the UI, hand and overlays, MCVR draws the world, and MCVR owns presentation so
frame generation, vsync and the FPS limiter keep working.

### Device bootstrap

All references are to `com.mojang.renderpearl.backend.vulkan` (VK) in the
decompiled 26.3 sources.

- **Force Vulkan.** Cancel `PreferredGraphicsApi.getBackendsToTry()` and return
  only `VulkanBackend`. This also removes the silent fallback to OpenGL, so an
  unsupported GPU gets vanilla's clear "no supported graphics backend" error.
  The `Minecraft` constructor resets the option after an unclean start, which
  this bypasses.
- **Instance.** `VulkanInstance` requests API 1.2. Raise it to 1.3 with a
  `@ModifyArg` on `VkApplicationInfo.apiVersion`, since MCVR and XeSS need 1.3.
  Add any extra instance extensions to the mutable `enabledExtensions` set
  before it is turned into the create info. `checkBackendAvailable()` builds a
  throwaway instance, so these mixins must be idempotent.
- **Device features.** Add Radiance `FeatureSet`s in
  `VulkanFeatureSets.optionalFeatureSets()` at RETURN. Optional sets don't
  affect device suitability. Split them so one missing piece doesn't disable the
  rest:
  - "RT core": `VK_KHR_acceleration_structure`,
    `VK_KHR_deferred_host_operations`, `VK_KHR_ray_query`, the
    `accelerationStructure` and `rayQuery` features, and `bufferDeviceAddress`,
    `descriptorIndexing`, `runtimeDescriptorArray`, `shaderSampledImageArrayNonUniformIndexing`,
    `descriptorBindingPartiallyBound`, `scalarBlockLayout` and the other bits
    MCVR's `device.cpp` enables. These must go on vanilla's existing
    `VkPhysicalDeviceVulkan12Features` struct (`VK12_FEATURES_STRUCT`), not
    the per-feature structs, or device creation fails validation.
  - "RT pipeline": `VK_KHR_ray_tracing_pipeline`, if MCVR uses it.
  - One set per upscaler and frame generator (DLSS, FSR, XeSS), from what each
    SDK reports it needs.
  - Don't add `VkPhysicalDeviceVulkan13Features`: vanilla already chains the
    separate synchronization2 and dynamic rendering structs. Use individual
    structs for any other 1.3 features.
  - After creation, check the device's extension list. If "RT core" is missing,
    show a clear "ray tracing GPU required" screen instead of crashing.
- **VMA.** MCVR keeps its own VMA allocator on the shared `VkDevice`. LWJGL's
  bundled VMA is a different build, so its allocator handle isn't safe to use
  from MCVR. Vanilla's allocator stays as it is.
- **Handles to native.** Add an `@Accessor` for `FrontendGpuDevice.backend`
  and cast it to `VulkanDevice`. Pass `instance().vkInstance()`, `vkDevice()`,
  the physical device, the graphics, compute and transfer queues with their
  family indices, and the enabled extension list to a new
  `RendererProxy.initRendererWithVulkanDevice`. The 1.21.4 OpenGL-UI path
  (`initRendererWithOpenGLDevice`, where native attached to someone else's
  device) is the closest template.

### Frame flow and presentation

- **Queues.** Vanilla submits only to the graphics queue, from the render thread,
  with no locking. MCVR records into its own command pools. It either hands
  command buffers to vanilla's `VulkanCommandEncoder.execute(cb)` on the render
  thread, or runs long work (BLAS/TLAS builds, denoising) on the compute queue,
  which vanilla never uses, and syncs with timeline semaphores through
  `encoder.waitSemaphore` / `signalSemaphore`. Vanilla images and buffers use
  exclusive sharing, so compute-queue access needs queue-family ownership
  transfers.
- **Stall timeout.** Vanilla throws if a submit takes over 5 seconds. Big
  acceleration structure builds must not sit on the graphics queue.
- **Where the world goes.** Keep `LevelRenderer.render` running so it still
  compiles sections, updates occlusion and fires the loading-screen callback.
  Skip only vanilla's world drawing with a `@WrapOperation` on
  `FrameGraphBuilder.execute`. The world never enters vanilla's main target:
  vanilla clears it to transparent black and draws the hand overlay, screen
  effects and GUI there with straight-alpha blending. At present time MCVR
  renders the world in its own submission and composites the main target over
  it, exactly as it composited the OpenGL UI surface on 1.21.4. That keeps
  MCVR's frame structure, async compute and frame generation's separate UI
  handling, and nothing is spliced into vanilla's command buffers. (An earlier
  plan wrote the world into the main target mid-frame; the UI-surface approach
  replaced it at the start of M3.) Vanilla images are always in
  `VK_IMAGE_LAYOUT_GENERAL`, are stored bottom-up (the swapchain blit flips Y),
  and use reversed-Z depth.
- **Presentation stays with MCVR.** Frame generation (DLSS, FSR, XeSS), vsync,
  the FPS limiter and the presented-FPS counter all need to own present.
  Replace vanilla's `VulkanGpuSurface` swapchain work: create MCVR's swapchain on
  the `VkSurfaceKHR` vanilla gets from SDL, and route
  `Minecraft.renderFrame`'s `blitFromTexture` / `present` to MCVR. Vanilla
  still produces the composited main target, and MCVR takes it from there.
- **Resizes.** SDL drives resizes through `Window`. Forward them to
  `WindowProxy.onFramebufferSizeChanged`, and re-fetch main-target handles after
  `RenderTarget.resize`, since vanilla frees the old images two submits later.

### Textures

On 1.21.4, every texture native samples is mirrored into a native-side ID space
through `TextureUtil.generateTextureId`, `AbstractTexture.getId()` and
`NativeImage._upload`. 26.3 has no integer texture IDs; textures are
`GpuTexture` objects.

On a shared device, MCVR can sample vanilla's images directly. The plan:

- A `TextureTracker` map from `GpuTexture` to a native texture slot, filled when
  textures are created and removed when they are closed. Native gets the
  `VkImage` / `VkImageView` handles from `VulkanGpuTexture` /
  `VulkanGpuTextureView` and adds them to its bindless descriptor array.
- This removes the CPU-side upload mirroring (`NativeImageMixins`,
  `TextureUtilMixins`, the OpenGL-UI twins). Uploads are vanilla's job.
- PBR side textures (`_n` and `_s` sprites, emission) are still built by
  `AuxiliaryTextures`, but allocated as vanilla `GpuTexture`s and tracked the
  same way.
- Native needs `VK_IMAGE_USAGE_SAMPLED` (already set for textures). Anything it
  writes to needs `STORAGE`, which `VulkanConst.textureUsageToVk` never sets.
  Add a mixin for Radiance-owned textures only.

### Geometry capture

| What | 1.21.4 hook | 26.3 hook |
| --- | --- | --- |
| Per-frame camera | `GameRenderer.renderLevel` matrices | `CameraRenderState` at `GameRenderer.renderLevel()` HEAD. Wrap `ProjectionMatrixBuffer.getBuffer` for the final bobbed projection. |
| Terrain | `SectionCompiler.compile` rewrite, `ModelBlockRenderer.putQuadData`, `LiquidBlockRenderer.tesselate` | Wrap the `BlockQuadOutput` lambdas in `SectionCompiler.compile`. They give each `BakedQuad` (record with sprite, layer, tint, light emission and direction) and its `QuadInstance` color and light. Fluids go through `FluidRenderer.tesselate` / `vertex(...)`. Write Radiance's 40-byte PBR vertices into a side buffer per section. |
| Section lifecycle | `RenderSection` `setCompiled`, `setDirty`, `releaseBuffers`; `ViewArea` | `CompileTask.doTask`, `RenderSection.setSectionMesh` / `releaseSectionMesh`, `SectionUpdateTracker`, `ViewArea` |
| Off-screen sections | Rebuilt eagerly | 26.3 only recompiles visible dirty sections (`LevelExtractor.extract`). Ray-traced reflections and shadows need off-screen geometry too, so hook `SectionUpdateTracker` to queue off-screen rebuilds. |
| Entities, block entities, items | `RenderType.draw(MeshData)` and a wrapped `MultiBufferSource` | Wrap `EntityRenderDispatcher.submit` and `BlockEntityRenderDispatcher.submit` in `LevelRenderer.submitEntities` / `submitBlockEntities`, passing Radiance's own `SubmitNodeCollector`, which tessellates straight into PBR vertex buffers. This keeps per-entity attribution. `StagedVertexBuffer.upload()` is the fallback, but it's shared with the hand and GUI passes. |
| Hand and held items | `ItemInHandRenderer` | `FirstPersonHandsAndItemsRenderer.submitHandsWithItems`, via `GameRenderer.renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)` |
| Item glint | `ItemRenderer.get*FoilBuffer` | `ItemFeatureRenderer.prepareMainSubmit`, keyed on `FoilType` |
| Banners | `BannerRenderer.renderPatterns` | `BannerRenderer.submitPatternLayer`. Each layer is a separate `order(n)` submit. |
| Particles | `ParticleEngine`, `SingleQuadParticle.renderRotatedQuad` | `ParticleEngine.add` / `tick`, `SingleQuadParticle.extractRotatedQuad`, `QuadParticleRenderState` |
| Sky | `SkyRenderer` constructor redirect | `SkyRenderer.extractRenderState` → `SkyRenderState`. `SkyRenderer` is now created lazily in `addSkyPass`. |
| Clouds | `CloudRenderer.render` rewrite | `CloudRenderer.prepare(...)` |
| Weather | `LevelRenderer` weather | `WeatherEffectRenderer.extractRenderState` → `WeatherRenderState` (CPU rain and snow columns) |
| Fog | `FogRenderer` | `FogRenderer.setupFog` → `FogData` |
| Lightmap | CPU lightmap rewrite in `LightTexture` | `Lightmap.render(LightmapRenderState)` HEAD: capture the inputs (sky and block factors, night vision, darkness, tints) for the world uniform. The lightmap itself is a GPU texture, sampled through the texture tracker. |
| Dimension effects | `DimensionSpecialEffects` | `DimensionType.skybox()` and `EnvironmentAttributes` through `camera.attributeProbe()` |

26.3 vertex formats have no normal (`BLOCK` is 28 bytes: position, color,
UV0, packed light). Radiance's PBR format is built by hand: `BufferBuilder`
only writes the eight vanilla attribute names, so custom attributes are written
straight into a `ByteBufferBuilder`. Normals come from `BakedQuad.direction()`
or the quad's positions. `PBRVertexFormatElements` is rewritten as plain offset
constants plus a `VertexFormat` built with `VertexFormat.builder()`.

### What gets deleted

Everything that existed to mirror OpenGL into Vulkan:

- `mixins/opengl_ui/**`, `client/opengl_ui/**`
- `GlStateManagerMixins`, `GlUniformMixins`, `GLXMixins`, `BufferRendererMixins`,
  `CompiledShaderMixins`, `ShaderProgramMixins`, `ShaderLoaderMixins`,
  `RenderPhaseTargetMixins`, `ScreenMixins`, `ReloadableResourceManagerImplMixins`,
  `VideoWarningManagerWarningPatternLoaderMixins`, `WindowMixins` (its resize
  forwarding moves to a new SDL hook)
- `client/shader/**`, `ShaderProxy`, `PipelineStateProxy`, `DrawCommandProxy`,
  `VulkanUiProxy` (its world composite moves to the frame flow above), the
  `ofGL` half of `VulkanConstants`, the shader and uniform extension interfaces
- `TextureUtilMixins`, `AbstractTextureMixins` (vulkan_render_integration) and
  `NativeImageMixins` upload mirroring, replaced by the texture tracker
- The `radianceUiBackend` build switch and the generated `UiBackendBuildConfig`
  / `UiBackendRendererHooks` classes

Jobs inside deleted code that must survive somewhere else:

- `MinecraftClientMixins`: native init (now on the shared device), pipeline
  load and build, the reload listener, per-frame submit, ambient occlusion off,
  shutdown and saving options.
- `GameRendererMixins`: the view matrix, per-frame entity build, hand rebuild
  and FOV scale, the "should render world" gate, world-only screenshots.
- `RenderSystemMixins`: the max texture size reported to atlases. No longer
  needed: vanilla and MCVR share a device, so vanilla's own limit is correct.
- `ScreenshotRecorderMixins`: screenshots. No longer needed: 26.3 copies the
  main target on the GPU, and the world is composited into it before the UI,
  so F2 screenshots (world and UI) and the world-only auto-screenshot both work
  unchanged.

The JNI surface shrinks to match: `ShaderProxy`, `PipelineStateProxy`,
`DrawCommandProxy`, `VulkanUiProxy`, `OpenGLUiProxy` and the GL-presentation
`RendererProxy` methods are removed, and MCVR's matching JNI files go with them
in M3.

## Milestones

### M1 Build setup and Mojang names (done)

- Gradle 9.8.2, Loom 1.19, Java 25, Fabric Loader 0.20.1, Fabric API 0.165.0+26.3.
- Sources and access widener migrated from Yarn to Mojang names on 1.21.4 with
  Loom's `migrateMappings` / `migrateClassTweakerMappings`. Both UI backends
  compiled, and no Yarn names were left in mixin targets.
- MixinExtras dependency dropped (bundled in Fabric Loader).

### M2 Render integration rewrite (Java)

Order of work, following the design above:

1. Delete the OpenGL mirroring code and the `radianceUiBackend` switch.
2. Mechanical renames and package moves (`Identifier`, `RenderType`,
   `VertexFormat`, `BlockAndTintGetter`, `BakedQuad`, `FogRenderer`, `Util`,
   `OptionEnum`).
3. Device bootstrap: force Vulkan, the instance and feature-set mixins, the
   backend accessor and `initRendererWithVulkanDevice`.
4. Frame flow: skip vanilla world drawing, composite MCVR output before the UI,
   and route present to MCVR.
5. Texture tracker on `GpuTexture`, plus the font, atlas and PBR side textures.
6. Geometry capture: terrain, fluids, entities, block entities, items, hand,
   particles, sky, clouds, weather, fog and lightmap.
7. Settings screens on `GuiGraphicsExtractor` and the new `OptionInstance` API.

Done when `./gradlew compileJava` passes and the mixin config applies without
errors at game launch. In-game rendering can't be checked until MCVR is ported
in M3.

### M3 MCVR native port

- Regenerate JNI headers from the M2 Java sources (`./gradlew compileJava`
  writes them to `src/main/native/include`).
- Build MCVR on Windows (MSVC 2022, Vulkan SDK, CMake) and Linux (GCC/Clang).
- Done when `core.dll`/`libcore.so` passes the build's native check
  (`verifyNativeLibrary`, which looks for `initRendererWithVulkanDevice`) and
  the game reaches the title screen with Radiance loaded.

#### What M3 has to change

What the Java side now expects of native, collected during M2:

- **Device.** Replace instance, physical device and device creation with
  `RendererProxy.initRendererWithVulkanDevice`, which passes vanilla's handles,
  queues, surface and the enabled extension lists. Keep a separate VMA
  allocator and separate command pools. Remove GLFW and the OpenGL interop.
  Query the DLSS, FSR and XeSS device extension requirements before vanilla
  creates the device (`RadianceVulkanFeatures.featureSets()` has a TODO), so
  they can be added as feature sets.
- **Swapchain.** `configureSwapchain`, `isSwapchainSuboptimal`,
  `acquireContext` (now returns success), `setPresentSource` and
  `submitCommandAndPresent` replace native's GLFW swapchain. The present source
  is vanilla's main target: `VK_IMAGE_LAYOUT_GENERAL`, RGBA8, stored bottom-up.
- **UI surface.** The present source is the UI surface: vanilla's main target
  with the UI over a transparent background. Native renders the world as before
  and fuses this image over it (flipping Y) in place of the OpenGL UI surface.
  Vanilla submits its frame before calling present, on the same queue, so
  native's submission runs after it; use full memory barriers around vanilla's
  images and leave them in `VK_IMAGE_LAYOUT_GENERAL`.
- **Textures.** `importVulkanImageNative` registers vanilla's `VkImage`s
  (native makes its own views and never frees them). Released through
  `releaseTexture` when vanilla frees the image. Imports can arrive from any
  thread that creates textures.
- **Vertex layouts.** `BLOCK` is now 28 bytes with no normal; `ENTITY` and
  `PARTICLE` are unchanged. The PBR layouts are unchanged (64 and 40 bytes).
  Draw mode 1 (`LINE_STRIP`) is gone and 8 (`POINTS`) is new.
- **Sky.** The moon is now one texture per phase instead of a 4×2 grid, and the
  sun is `textures/environment/celestial/sun.png`.
- **Fog and lightmap.** `updateWorldUniform` sends the nearer of 26.3's
  environmental and render-distance fogs; the lightmap inputs follow
  `LightmapRenderState`.
- **Removed JNI.** `initRenderer(String[], long)`, the OpenGL presentation
  methods, `maxSupportedTextureSize`, `takeScreenshot`, `WindowProxy`,
  `ShaderProxy`, `PipelineStateProxy`, `DrawCommandProxy`, `VulkanUiProxy`,
  `OpenGLUiProxy`, and BufferProxy's UI upload methods.

### M4 Vanilla world rendering in game

- Terrain, entities, block entities, particles, sky, water and the UI render
  correctly, along with DLSS/FSR/XeSS and the NRD denoiser.
- Needs in-game testing on ray tracing hardware. The development machine has an
  RTX 5090, so NVIDIA paths can be checked locally. AMD and Intel need outside
  testers.

### M5 Voxy integration

Targets Voxy 0.2.20-beta (commit 5bf3335), checked against its source and jar.

#### How Voxy works on 26.3

- **Storage.** A `WorldEngine` per dimension holds a pyramid of 32x32x32 sections
  (`long` per voxel: block id, biome id, light), level 0 (1 block per voxel) to
  level 4 (16 blocks). Chunk ingest, saving, and `dirtyCallback` section events
  are all CPU and GL-free.
- **Meshing is on the CPU.** `RenderGenerationService` / `RenderDataFactory`
  greedy-mesh a section into 64-bit quads (`BuiltSection`): face, size, voxel
  position, model id, biome id, light. Block models are baked on the CPU too
  (`ModelFactory` with a software rasteriser) into 6 16x16 face images per model
  plus a metadata word; only the *upload* of those is GL.
- **Rendering is raw OpenGL 4.6** (MDIC, compute culling, Hi-Z, `GlBuffer`...),
  and LOD *selection* happens on the GPU (hierarchical occlusion traversal). It
  hooks Sodium (hard `depends` on Sodium 0.9.2/0.9.3) and its `MixinMinecraft`
  refuses a Vulkan device outright.

#### Design chosen: take Voxy's CPU mesh, convert it, select LODs on the CPU

Closest to the DH path and the least fragile: nothing is read back from the GPU
and no render hook is replicated. Voxy keeps doing ingest, storage, model baking
and meshing; Radiance replaces its OpenGL renderer.

- `VoxyMesh` turns each `BuiltSection` quad into 4 chunk PBR vertices by
  repeating the arithmetic of Voxy's `quads3.vert` (face rectangle and depth from
  the model's face word, size, 1 voxel = 2^lod blocks). Vertices are relative to
  the section corner; light, normal and winding follow the DH conversion.
- `VoxyModels` mirrors what Voxy would upload to the GPU (model words, face
  images, biome tint table) by draining `ModelFactory`'s upload queue, and turns
  each (model, face, tint) into a Vista tile: pixel / average colour, with the
  average (times tint) as the vertex colour. Tiles go in `VistaTileAtlas`
  (the same 4096-wide, 16x16, 5-mip layout as DH's texture, tile 0 = none).
- `VoxyWorld` picks sections on a background thread every 100 ms: level 4
  sections within `sectionRenderDistance` of the camera, split into their
  children while the camera is within `4 * section width` (`-Dradiance.voxy.detail`).
  A parent stays until all its children are built. It remeshes dirty sections
  after 0.5 s (level 0) or 2 s, and keeps the render list to ids that native
  holds. `VoxyColumns` supplies the `VistaTerrain` border columns.
- Mixins (`mixins/mod_support/voxy`):
  `MinecraftBackendMixins` (priority 5000, outermost wrap of `createDevice`, so
  Voxy's Vulkan refusal is bypassed; confirmed in exported bytecode),
  `VoxyClientMixins` (replaces `VoxyClient.initVoxyClient`: registers the
  instance factory without any GL), `VoxyConfigMixins` (`isRenderingEnabled`
  false, so Voxy never builds its renderer). Under `.../voxy/source`, applied only
  when Voxy is the Vista source: `ModelFactoryMixins`, `ModelBakeResultUploadMixins`,
  `BiomeUploadResultMixins` (read bakes off the upload queue),
  `ModelBakerySubsystemMixins` (skip the GL `ModelStore`),
  `SoftwareModelTextureBakeryMixins` (block atlas from sprite CPU images instead
  of a GL readback), `ModelManagerMixins` (resource reload), `TextureAtlasSpritesAccessor`.
- **DH and Voxy together:** Distant Horizons wins (`ModSupport.VOXY_SOURCE =
  VOXY && !DISTANT_HORIZONS`). Voxy is then left without a factory (no instance,
  no ingest) and a warning is logged; the Vulkan-device and init mixins still
  apply so Voxy cannot break startup.
- Gating: `ModSupport.VOXY` needs exactly 0.2.20-beta (anything else throws at
  startup like DH); `fabric.mod.json` has `suggests` and `breaks` for it.

#### Dev setup

`build.gradle` has Voxy as `compileOnly` and `localRuntime`, plus Sodium 0.9.3-beta.1
in `localRuntime` because Voxy cannot load without it. DH stays in `localRuntime`
by default; pass `-PradianceWithoutDistantHorizons` to drop it. The Voxy source
mixins are only applied (and audited) in that configuration:

```sh
./gradlew runAuditMixins                                  # DH + Voxy + Sodium: DH is the source
./gradlew runAuditMixins -PradianceWithoutDistantHorizons # Voxy + Sodium: Voxy is the source
```

Both print "Radiance mixin audit passed". All targets were also checked with
`javap` against the 0.2.20-beta jar.

#### Blocker to decide: Sodium

Voxy 26.3 hard-depends on Sodium and Sodium replaces vanilla's section manager
(it nulls `ViewArea`, uses its own chunk builder). Radiance captures near terrain
from vanilla's `SectionCompiler`/`ViewArea`, so with Sodium installed near
terrain will not reach MCVR. Voxy can't be run without Sodium. Radiance needs
either a Sodium terrain capture (a new milestone) or to accept that Voxy
support only works once that exists. Sodium itself supports the Vulkan backend.

#### Native requirements for Voxy data

All through the existing `VistaProxy` JNI (same contract as DH), plus:

1. **Tile alpha as cut-out.** Tile alpha is texture coverage (0 for cut-out
   texels). `vistaAlbedo` should also return the tile alpha and multiply it into
   the surface alpha for cut-out and transparent alpha modes (packedData bits 8-11
   are 1 for cut-out). Until then sparse cut-out faces (plants, fences, less than
   50% coverage) are skipped; `-Dradiance.voxy.sparseCutouts=true` emits them and
   they render as flat average-colour quads.
2. Section sizes are 32 << lod blocks (32 to 512), x/z multiples of 32, y a
   multiple of 32; at most 262,144 quads per call. Expect thousands of ids in the
   render list (DH has far fewer); check Vista instance limits in `world_prepare`.
3. Voxy emits one quad per face, not two-sided; plants and double-sided models are
   not duplicated, so Vista geometry should not be back-face culled.
4. Water is textureID `0x800c0000 | tile` as for DH, but the water surface is
   already indented by the model's depth word, so the DH `-(1/9 + 0.001)` offset
   is not applied.
5. Neighbouring sections of different levels meet without skirts, so T-junction
   cracks at LOD borders are possible.

#### Not verified

Everything in game: no GL calls were removed from the Voxy paths by test, only by
reading. Specifically unverified: `UnsafeManager.allocateInstance(ModelStore)`,
the sprite-based atlas readback (assumes sprite images are not released), that no
other Voxy code touches GL at runtime, Sodium running beside Radiance, tile
quality and tinting, LOD selection cost and thresholds, seams against near chunks,
and the shutdown order (`VoxyWorld.close` runs before Voxy's session shutdown).

### M6 Distant Horizons integration

Targets DH 3.3.4-26.3 (previously 3.3.1). Verified statically and by the mixin
audit, which now runs with DH on the dev classpath (`localRuntime`):

- `ModSupport` and the `breaks` range in `fabric.mod.json` are pinned to 3.3.4.
- All DH mixin targets were checked with `javap` against the 3.3.4 jar and apply
  in `runAuditMixins`: `ClientApi` (`renderLodLayer`, `renderFadeOpaque`,
  `renderFadeTransparent`), `DependencySetup.setRenderingApiBindings`,
  `FullDataToRenderDataTransformer.setRenderColumnView` (`getBlockStateWrapper`
  redirect), `LodBufferContainer`, `LodRenderSection` (`makeLodRenderData` and
  `tryMakeAndUploadBuffersAsync` redirects), `MinecraftRenderWrapper.getMcRenderingApi`.
- `FabricClientProxyMixins` was removed. DH 3.3.4 no longer renders from
  `FabricClientProxy` lambdas; it calls `ClientApi.renderLods`,
  `renderDeferredLodsForShaders` and `renderFade*` from its own `MixinLevelRenderer` and
  `MixinChunkSectionsToRender`, and `ClientApi` is already cancelled.
- The 16-byte LOD vertex layout is unchanged (`LodQuadBuilder.putVertex`: xyz
  shorts, light nibbles, micro offsets, RGBA, normal and material bytes, tile id
  short), so `DistantHorizonsVertexBuffer` needed no conversion changes.

Not verified: anything in game. Rendering, uploads and the water/kelp
redirect need the native renderer (M3/M4) before they can be exercised.

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
