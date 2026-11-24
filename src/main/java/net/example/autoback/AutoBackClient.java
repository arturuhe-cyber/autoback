package net.example.autoback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class AutoBackClient implements ClientModInitializer {
    /** Настройки лежат в config/autoback.json */
    public static class Config {
        public boolean enabled = true;       // включён ли мод
        public String command = "/back";     // что отправить после возрождения
        public int delayTicks = 20;          // задержка после возрождения (20 тиков = 1 секунда)
        public boolean autoRespawn = false;  // сам нажимать "Возродиться"
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config config = new Config();

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("autoback", "main"));
    private static KeyBinding toggleKey;

    private static boolean died = false;
    private static boolean respawnRequested = false;
    private static int deadTicks = 0;
    private static int aliveTicks = 0;

    @Override
    public void onInitializeClient() {
        loadConfig();

        // Клавиша-переключатель (B по умолчанию, меняется в настройках управления)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoback.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CATEGORY));

        // Команда /autoback тоже переключает мод
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autoback").executes(context -> {
                    toggle(context.getSource().getClient());
                    return 1;
                })));

        ClientTickEvents.END_CLIENT_TICK.register(AutoBackClient::onTick);
    }

    private static void toggle(MinecraftClient client) {
        config.enabled = !config.enabled;
        died = false;
        respawnRequested = false;
        deadTicks = 0;
        aliveTicks = 0;
        saveConfig();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[AutoBack] " + (config.enabled ? "ON" : "OFF")), true);
        }
    }

    private static void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            toggle(client);
        }

        if (!config.enabled) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            // Вышли из мира: сбрасываем состояние
            died = false;
            respawnRequested = false;
            deadTicks = 0;
            aliveTicks = 0;
            return;
        }

        boolean dead = player.isDead() || client.currentScreen instanceof DeathScreen;
        if (dead) {
            died = true;
            aliveTicks = 0;
            deadTicks++;
            if (config.autoRespawn && !respawnRequested && deadTicks >= 10 && player.isDead()) {
                respawnRequested = true;
                player.requestRespawn();
            }
            return;
        }

        // Игрок жив
        deadTicks = 0;
        respawnRequested = false;
        if (died) {
            aliveTicks++;
            if (aliveTicks >= Math.max(0, config.delayTicks)) {
                died = false;
                aliveTicks = 0;
                sendBack(player);
            }
        }
    }

    private static void sendBack(ClientPlayerEntity player) {
        String cmd = config.command == null ? "" : config.command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty() || player.networkHandler == null) return;
        player.networkHandler.sendChatCommand(cmd);
        player.sendMessage(Text.literal("[AutoBack] /" + cmd), true);
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("autoback.json");
    }

    private static void loadConfig() {
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) config = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        saveConfig(); // чтобы в файле всегда были все настройки
    }

    private static void saveConfig() {
        try (Writer writer = Files.newBufferedWriter(configFile())) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
