package com.radiance.mixins.vulkan_options;

import static net.minecraft.client.Options.genericValueLabel;
import static net.minecraft.client.InactivityFpsLimit.AFK;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import com.mojang.serialization.Codec;
import com.radiance.client.gui.PotentialValuesBasedCallbacksNoValue;
import com.radiance.client.gui.RenderPipelineScreen;
import com.radiance.client.option.Options;
import com.radiance.client.util.CategoryVideoOptionEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
public class VideoOptionsScreenMixins extends GameOptionsScreenMixins {

    @Shadow
    private @Nullable Monitor fullscreenModeMonitor;

    @Unique
    private static final Component INACTIVITY_FPS_LIMIT_MINIMIZED_TOOLTIP = Component.translatable(
        "options.inactivityFpsLimit.minimized.tooltip");
    @Unique
    private static final Component INACTIVITY_FPS_LIMIT_AFK_TOOLTIP = Component.translatable(
        "options.inactivityFpsLimit.afk.tooltip");

    @Unique
    private static final Component EXCLUSIVE_FULLSCREEN_MODE_TOOLTIP = Component.translatable(
        "options.fullscreen.exclusive.mode.tooltip");

    @Unique
    private static final PotentialValuesBasedCallbacksNoValue<Boolean> BOOLEAN_NO_KEY = new PotentialValuesBasedCallbacksNoValue<>(
        ImmutableList.of(Boolean.TRUE, Boolean.FALSE), Codec.BOOL
    );
    @Unique
    private static PotentialValuesBasedCallbacksNoValue<Integer> valuesOf(List<Integer> values) {
        return new PotentialValuesBasedCallbacksNoValue<>(ImmutableList.copyOf(values), Codec.INT);
    }

    @Inject(method = "addOptions()V", at = @At(value = "HEAD"), cancellable = true)
    public void redirectAddOptions(CallbackInfo ci) {
        Options.sanitizeFrameGenerationOptionsForAvailableHardware(true);
        List<Integer> frameGenerationModes = Options.getAvailableFrameGenerationModes();
        int multiplierReferenceMode = Options.getFrameGenerationMultiplierReferenceMode();
        List<Integer> frameGenerationMultipliers = Options.getAvailableFrameGenerationMultipliers(
            multiplierReferenceMode);

        OptionInstance<Integer>
            maxFps =
            new OptionInstance<>("options.framerateLimit",
                OptionInstance.noTooltip(),
                (optionText, value) -> value == 260 ?
                    genericValueLabel(optionText, Component.translatable("options.framerateLimit.max"))
                    :
                        genericValueLabel(optionText,
                            Component.translatable("options.framerate", value)),
                new OptionInstance.IntRange(1, 26).xmap(
                    value -> value * 10, value -> value / 10, true),
                Codec.intRange(10, 260),
                Options.maxFps,
                value -> {
                    Minecraft.getInstance()
                        .getFramerateLimitTracker()
                        .setFramerateLimit(value);
                    Options.setMaxFps(value, true);
                });

        int i = -1;
        Window
            window =
            Minecraft.getInstance()
                .getWindow();
        Monitor monitor = window.findBestMonitor();
        // Vanilla's addOptions records this monitor, and repositionElements rebuilds the screen
        // whenever the current monitor differs from it. Left unset, every rebuild triggered
        // another until the stack overflowed.
        this.fullscreenModeMonitor = monitor;
        int j;
        if (monitor == null) {
            j = -1;
        } else {
            Optional<VideoMode> optional = window.getPreferredFullscreenVideoMode();
            j =
                optional.map(monitor::indexOfMode)
                    .orElse(-1);
        }

        // 26.x only uses the fullscreen video mode in exclusive fullscreen, so this follows
        // vanilla's "Exclusive Fullscreen Mode" option
        OptionInstance<Integer>
            fullScreenResolutionOption =
            new OptionInstance<>("options.fullscreen.exclusive.mode",
                OptionInstance.cachedConstantTooltip(EXCLUSIVE_FULLSCREEN_MODE_TOOLTIP),
                (optionText, value) -> {
                    if (monitor == null) {
                        return Component.translatable("options.fullscreen.unavailable");
                    } else if (value == -1) {
                        return genericValueLabel(optionText,
                            Component.translatable("options.fullscreen.current"));
                    } else {
                        VideoMode videoMode = monitor.mode(value);
                        return genericValueLabel(optionText,
                            Component.translatable("options.fullscreen.entry",
                                videoMode.getWidth(),
                                videoMode.getHeight(),
                                videoMode.refreshRateLabel(),
                                videoMode.getRedBits() + videoMode.getGreenBits() +
                                    videoMode.getBlueBits()));
                    }
                }, new OptionInstance.IntRange(-1,
                monitor != null ? monitor.modeCount() - 1 : -1), j, value -> {
                if (monitor != null) {
                    window.setPreferredFullscreenVideoMode(
                        value == -1 ? Optional.empty() : Optional.of(monitor.mode(value)));
                }
            });

        OptionInstance<InactivityFpsLimit> inactivityFpsLimit = new OptionInstance<>(
            "options.inactivityFpsLimit",
            option -> {
                return switch (option) {
                    case MINIMIZED -> Tooltip.create(
                        INACTIVITY_FPS_LIMIT_MINIMIZED_TOOLTIP);
                    case AFK -> Tooltip.create(INACTIVITY_FPS_LIMIT_AFK_TOOLTIP);
                };
            },
            (Component optionText, InactivityFpsLimit value) -> value.caption(),
            new OptionInstance.Enum<>(Arrays.asList(
                InactivityFpsLimit.values()),
                InactivityFpsLimit.CODEC),
            AFK,
            inactivityLimit -> {
                Options.setInactivityFpsLimit(
                    inactivityLimit == AFK ? 30 : 9, true);
            });

        OptionInstance<Boolean> enableVsync = OptionInstance.createBoolean("options.vsync", Options.vsync,
            value -> {
                if (Minecraft.getInstance()
                    .getWindow() != null) {
                    Options.setVsync(value, true);
                }
            });

        OptionInstance<Integer> frameGenerationMethod = new OptionInstance<>(
            Options.FRAME_GENERATION_METHOD_KEY,
            OptionInstance.noTooltip(),
            (optionText, value) -> genericValueLabel(optionText,
                Component.translatable(Options.frameGenerationModeTranslationKey(value))),
            valuesOf(frameGenerationModes),
            Options.frameGenerationMode,
            value -> {
                Options.setFrameGenerationMode(value, true);
                int maxMultiplier = Options.getMaxFrameGenerationMultiplier(value);
                if (maxMultiplier >= 2 && Options.frameGenerationMultiplier > maxMultiplier) {
                    Options.setFrameGenerationMultiplier(maxMultiplier, true);
                }
            });

        OptionInstance<Integer> frameGenerationMultiplier = frameGenerationMultipliers.isEmpty() ? null :
            new OptionInstance<>(
                Options.FRAME_GENERATION_MULTIPLIER_KEY,
                OptionInstance.noTooltip(),
                (optionText, value) -> genericValueLabel(optionText,
                    Component.literal(value + "x")),
                valuesOf(frameGenerationMultipliers),
                Options.frameGenerationMultiplier,
                value -> Options.setFrameGenerationMultiplier(value, true));

        OptionInstance<Integer>
            chunkBuildingBatchSize =
            new OptionInstance<>(Options.CHUNK_BUILDING_BATCH_SIZE_KEY,
                OptionInstance.noTooltip(),
                (optionText, value) -> genericValueLabel(optionText,
                    Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(Options.CHUNK_BUILDING_BATCH_SIZE_MIN,
                    Options.CHUNK_BUILDING_BATCH_SIZE_MAX),
                Codec.intRange(Options.CHUNK_BUILDING_BATCH_SIZE_MIN,
                    Options.CHUNK_BUILDING_BATCH_SIZE_MAX),
                Options.chunkBuildingBatchSize,
                value -> {
                    Options.setChunkBuildingBatchSize(value, true);
                });

        OptionInstance<Integer>
            chunkBuildingTotalBatches =
            new OptionInstance<>(Options.CHUNK_BUILDING_TOTAL_BATCHES_KEY,
                OptionInstance.noTooltip(),
                (optionText, value) -> genericValueLabel(optionText,
                    Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(Options.CHUNK_BUILDING_TOTAL_BATCHES_MIN,
                    Options.CHUNK_BUILDING_TOTAL_BATCHES_MAX),
                Codec.intRange(Options.CHUNK_BUILDING_TOTAL_BATCHES_MIN,
                    Options.CHUNK_BUILDING_TOTAL_BATCHES_MAX),
                Options.chunkBuildingTotalBatches,
                value -> {
                    Options.setChunkBuildingTotalBatches(value, true);
                });

        OptionInstance<Integer>
            chunkBuildingThreads =
            new OptionInstance<>(Options.CHUNK_BUILDING_THREADS_KEY, OptionInstance.noTooltip(),
                (optionText, value) -> genericValueLabel(optionText,
                    Component.literal(Integer.toString(value))),
                new OptionInstance.IntRange(Options.CHUNK_BUILDING_THREADS_MIN,
                    Options.getMaxChunkBuildingThreads()),
                Codec.intRange(Options.CHUNK_BUILDING_THREADS_MIN,
                    Options.getMaxChunkBuildingThreads()),
                Options.chunkBuildingThreads,
                value -> Options.setChunkBuildingThreads(value, true));

        OptionInstance<Boolean> collectChunkEmission = OptionInstance.createBoolean(
            Options.COLLECT_CHUNK_EMISSION_KEY,
            Options.collectChunkEmission,
            value -> Options.setCollectChunkEmission(value, true));

        OptionInstance<Boolean> pipelineSettings = new OptionInstance<>(Options.PIPELINE_SETUP_KEY,
            OptionInstance.noTooltip(),
            (optionText, value) -> optionText,
            BOOLEAN_NO_KEY,
            false,
            value -> {
                Minecraft.getInstance().gui
                    .setScreen(new RenderPipelineScreen((VideoSettingsScreen) (Object) this));
            });


        this.list.addEntry(
            new CategoryVideoOptionEntry(Component.translatable(Options.CATEGORY_GAMEPLAY), list));
        OptionInstance[] optionsGameplay = new OptionInstance[]{
            options.graphicsPreset(),
            options.renderDistance(),
            options.simulationDistance(),
            options.guiScale(),
            options.attackIndicator(),
            options.gamma(),
            options.cloudStatus(),
            options.particles(),
            options.screenEffectScale(),
            options.entityDistanceScaling(),
            options.fovEffectScale(),
            options.showAutosaveIndicator(),
            options.glintSpeed(),
            options.glintStrength(),
            options.menuBackgroundBlurriness(),
            options.bobView(),
        };
        this.list.addBig(options.biomeBlendRadius());
        this.list.addBig(options.mipmapLevels());
        this.list.addSmall(optionsGameplay);

        this.list.addEntry(
            new CategoryVideoOptionEntry(Component.translatable(Options.CATEGORY_WINDOW), list));
        OptionInstance[] optionsWindow = new OptionInstance[]{
            maxFps,
            inactivityFpsLimit,
            enableVsync,
            options.fullscreen(),
            options.exclusiveFullscreen(),
        };
        this.list.addSmall(optionsWindow);
        this.list.addBig(fullScreenResolutionOption);

        this.list.addEntry(
            new CategoryVideoOptionEntry(Component.translatable(Options.CATEGORY_FRAME_GENERATION), list));
        this.list.addBig(frameGenerationMethod);
        if (frameGenerationMultiplier != null) {
            this.list.addBig(frameGenerationMultiplier);
        }

        this.list.addEntry(
            new CategoryVideoOptionEntry(Component.translatable(Options.CATEGORY_TERRAIN), list));
        this.list.addBig(chunkBuildingBatchSize);
        this.list.addBig(chunkBuildingTotalBatches);
        this.list.addBig(chunkBuildingThreads);
        this.list.addBig(collectChunkEmission);

        this.list.addEntry(
            new CategoryVideoOptionEntry(Component.translatable(Options.CATEGORY_PIPELINE), list));
        this.list.addBig(pipelineSettings);

        ci.cancel();
    }
}
