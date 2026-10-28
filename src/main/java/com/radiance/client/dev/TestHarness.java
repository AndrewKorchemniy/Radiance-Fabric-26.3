package com.radiance.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.RadianceClient;
import com.radiance.client.proxy.vulkan.RendererProxy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;

/**
 * A development harness for testing the renderer without touching the desktop: it is driven by a
 * command file instead of keyboard and mouse, so the game can run on a hidden desktop. Enabled only
 * with {@code -Dradiance.harness=<directory>}.
 *
 * <p>Write commands, one per line, to {@code <directory>/commands.txt}; the harness takes the file,
 * runs the lines in order and appends progress to {@code <directory>/log.txt}:
 * <ul>
 *   <li>{@code run <command>}: a server command with owner permission, e.g.
 *       {@code run time set 13000} or {@code run tp @p 0 100 0 90 20}. Needs a singleplayer world.</li>
 *   <li>{@code wait <ticks>}: pauses the queue.</li>
 *   <li>{@code screenshot <name>}: saves the next presented frame as {@code <directory>/<name>.png}.</li>
 *   <li>{@code quit}: closes the game.</li>
 * </ul>
 * {@code <directory>/status.txt} holds {@code menu} or {@code world} so a script can wait for the
 * world to load. Game sound is muted while the harness is enabled.
 */
public final class TestHarness {

    private static Path directory;
    private static final Deque<String> queue = new ArrayDeque<>();
    private static int waitTicks;
    private static String lastStatus = "";
    private static boolean muted;

    private TestHarness() {
    }

    public static void initIfEnabled() {
        String property = System.getProperty("radiance.harness");
        if (property == null || property.isBlank()) {
            return;
        }
        directory = Path.of(property).toAbsolutePath();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            RadianceClient.LOGGER.error("Test harness: cannot create {}", directory, e);
            return;
        }
        RadianceClient.LOGGER.info("Test harness enabled in {}", directory);
        ClientTickEvents.END_CLIENT_TICK.register(TestHarness::tick);
    }

    private static void tick(Minecraft minecraft) {
        if (!muted) {
            minecraft.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            muted = true;
        }
        writeStatus(minecraft.level != null && minecraft.player != null ? "world" : "menu");
        takeCommandFile();
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (!queue.isEmpty() && waitTicks == 0) {
            String line = queue.poll().strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            log("> " + line);
            try {
                execute(minecraft, line);
            } catch (RuntimeException e) {
                log("! " + e);
            }
        }
    }

    private static void execute(Minecraft minecraft, String line) {
        int space = line.indexOf(' ');
        String verb = space < 0 ? line : line.substring(0, space);
        String argument = space < 0 ? "" : line.substring(space + 1).strip();
        switch (verb) {
            case "run" -> {
                IntegratedServer server = minecraft.getSingleplayerServer();
                if (server == null) {
                    log("! no singleplayer server");
                    return;
                }
                server.execute(() -> server.getCommands()
                    .performPrefixedCommand(server.createCommandSourceStack(), argument));
            }
            case "wait" -> waitTicks = Integer.parseInt(argument);
            case "screenshot" -> screenshot(argument);
            case "quit" -> minecraft.stop();
            default -> log("! unknown command: " + verb);
        }
    }

    private static void screenshot(String name) {
        Path file = directory.resolve(name.endsWith(".png") ? name : name + ".png");
        RendererProxy.captureScreenshot(image -> Util.ioPool().execute(() -> {
            try (NativeImage owned = image) {
                owned.writeToFile(file);
                log("saved " + file.getFileName());
            } catch (IOException e) {
                log("! screenshot failed: " + e);
            }
        }));
    }

    private static void takeCommandFile() {
        Path commands = directory.resolve("commands.txt");
        if (!Files.exists(commands)) {
            return;
        }
        Path taken = directory.resolve("commands.taken");
        try {
            Files.move(commands, taken, StandardCopyOption.REPLACE_EXISTING);
            List<String> lines = Files.readAllLines(taken, StandardCharsets.UTF_8);
            queue.addAll(lines);
        } catch (IOException e) {
            // The writer may still hold the file; try again next tick.
        }
    }

    private static void writeStatus(String status) {
        if (status.equals(lastStatus)) {
            return;
        }
        lastStatus = status;
        try {
            Files.writeString(directory.resolve("status.txt"), status, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            lastStatus = "";
        }
    }

    private static synchronized void log(String message) {
        try {
            Files.writeString(directory.resolve("log.txt"), message + System.lineSeparator(),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging must never break the game.
        }
    }
}
