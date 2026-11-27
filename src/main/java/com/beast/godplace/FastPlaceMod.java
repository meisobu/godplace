package com.beast.godplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.util.List;

@Mod(modid = "godplace", name = "GodPlace", version = "1.0", clientSideOnly = true)
public class FastPlaceMod {

    public static boolean enabled = false;
    public static int placementDelay = 0;
    public static String activationMode = "toggle";
    public static boolean debugMode = false;

    private final Minecraft mc = Minecraft.getMinecraft();

    private static File configFile;
    private static final String CONFIG_FOLDER = "godplace";
    private static final String CONFIG_FILE_NAME = "settings.cfg";

    private Field rightClickDelayField;

    private boolean locked = false;
    private EnumFacing lockedFace = null;

    public static KeyBinding godPlaceKeyBind;

    private int internalDelayCounter = 0;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new GodPlaceCommand());

        godPlaceKeyBind = new KeyBinding("key.godplace.toggle", Keyboard.KEY_G, "key.categories.misc");
        ClientRegistry.registerKeyBinding(godPlaceKeyBind);

        initConfigFile();
        loadSettings();
    }

    // ==================== FIELD SEARCH (ON FIRST TICK) ====================
    private boolean fieldsInitialized = false;

    private void initFieldsSafely() {
        if (fieldsInitialized || mc.thePlayer == null) return;

        String[] rightClickNames = { "rightClickDelayTimer", "field_71467_ac" };
        for (String name : rightClickNames) {
            try {
                Field f = Minecraft.class.getDeclaredField(name);
                f.setAccessible(true);
                rightClickDelayField = f;
                break;
            } catch (Exception ignored) {}
        }

        fieldsInitialized = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (!fieldsInitialized) {
            initFieldsSafely();
        }

        handleKeybind();
        if (!enabled) {
            locked = false;
            lockedFace = null;
            internalDelayCounter = 0;
            return;
        }

        if (rightClickDelayField != null) {
            manageDelayWithField();
        } else {
            manageDelayWithCounter();
        }

        // Reset lock on RMB release
        if (!mc.gameSettings.keyBindUseItem.isKeyDown() && locked) {
            locked = false;
            lockedFace = null;
        }
    }

    private boolean wasKeyPressed = false;

    private void handleKeybind() {
        boolean isKeyDown = godPlaceKeyBind.isKeyDown();

        if ("toggle".equalsIgnoreCase(activationMode)) {
            if (godPlaceKeyBind.isPressed()) {
                enabled = !enabled;
            }
        } else {
            enabled = isKeyDown;
            wasKeyPressed = isKeyDown;
        }
    }

    private void manageDelayWithField() {
        try {
            if (placementDelay == 0) {
                rightClickDelayField.setInt(mc, 0);
            } else {
                int current = rightClickDelayField.getInt(mc);
                if (current > placementDelay) {
                    rightClickDelayField.setInt(mc, placementDelay);
                }
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Delay field error: " + e.getMessage());
        }
    }

    private void manageDelayWithCounter() {
        if (placementDelay == 0) {
            internalDelayCounter = 0;
            return;
        }
        if (internalDelayCounter <= 0) {
            internalDelayCounter = placementDelay;
        } else {
            internalDelayCounter--;
        }
    }

    // ==================== FACE LOCK ====================
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (!enabled || event.entityPlayer != mc.thePlayer) return;
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        EnumFacing hitFace = event.face;

        if (!locked) {
            locked = true;
            lockedFace = hitFace;
            sendDebug("LOCKED " + hitFace.name());
            return;
        } else {
            if (hitFace != lockedFace) {
                event.setCanceled(true);
                return;
            }
        }

        if (rightClickDelayField == null && internalDelayCounter > 0) {
            event.setCanceled(true);
        }
    }

    // ==================== DEBUG ====================
    private void sendDebug(String msg) {
        if (!debugMode || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText("[GP-DEBUG] " + msg));
    }

    // ==================== CONFIG ====================
    private void initConfigFile() {
        File godplaceDir = new File(mc.mcDataDir, CONFIG_FOLDER);
        if (!godplaceDir.exists()) godplaceDir.mkdirs();
        configFile = new File(godplaceDir, CONFIG_FILE_NAME);
    }

    private void loadSettings() {
        if (configFile == null || !configFile.exists()) {
            saveSettings();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim();
                String value = parts[1].trim();
                switch (key) {
                    case "placementDelay": placementDelay = Integer.parseInt(value); break;
                    case "activationMode": activationMode = value; break;
                    case "debugMode": debugMode = Boolean.parseBoolean(value); break;
                }
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Load failed: " + e.getMessage());
        }
    }

    public static void saveSettings() {
        if (configFile == null) {
            try {
                File godplaceDir = new File(Minecraft.getMinecraft().mcDataDir, CONFIG_FOLDER);
                if (!godplaceDir.exists()) godplaceDir.mkdirs();
                configFile = new File(godplaceDir, CONFIG_FILE_NAME);
            } catch (Exception e) { return; }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write("# GodPlace Config\n");
            writer.write("placementDelay=" + placementDelay + "\n");
            writer.write("activationMode=" + activationMode + "\n");
            writer.write("debugMode=" + debugMode + "\n");
            writer.flush();
        } catch (Exception e) {}
    }

    // ==================== COMMAND ====================
    public static class GodPlaceCommand extends CommandBase {
        @Override public String getCommandName() { return "godplace"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/godplace [delay] [mode]"; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "delay", "mode", "debug");
            }
            if (args.length == 2) {
                if ("delay".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "0", "1", "2", "3", "4", "5");
                }
                if ("mode".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "hold", "toggle");
                }
            }
            return null;
        }

        private void sendUsage(ICommandSender sender) {
            ChatComponentText msg = new ChatComponentText("godplace ");
            ChatComponentText options = new ChatComponentText("[delay] [mode]");
            options.getChatStyle().setColor(EnumChatFormatting.GRAY);
            msg.appendSibling(options);
            sender.addChatMessage(msg);
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                enabled = !enabled;
                sender.addChatMessage(new ChatComponentText(
                    enabled ? "GodPlace ON (delay: " + placementDelay + ")" : "GodPlace OFF"
                ));
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("delay".equals(cmd) && args.length > 1) {
                try {
                    int d = Integer.parseInt(args[1]);
                    if (d >= 0 && d <= 5) {
                        placementDelay = d;
                        sender.addChatMessage(new ChatComponentText("GodPlace delay: " + d));
                        saveSettings();
                    } else {
                        sender.addChatMessage(new ChatComponentText("0-5 only"));
                    }
                } catch (Exception e) {
                    sender.addChatMessage(new ChatComponentText("/godplace delay <0-5>"));
                }
            }

            else if ("mode".equals(cmd) && args.length > 1) {
                String m = args[1].toLowerCase();
                if ("hold".equals(m) || "toggle".equals(m)) {
                    activationMode = m;
                    sender.addChatMessage(new ChatComponentText("GodPlace mode: " + m));
                    saveSettings();
                } else {
                    sender.addChatMessage(new ChatComponentText("hold or toggle"));
                }
            }

            else if ("debug".equals(cmd)) {
                debugMode = !debugMode;
                sender.addChatMessage(new ChatComponentText("GodPlace debug: " + (debugMode ? "ON" : "OFF")));
                saveSettings();
            }

            else {
                sendUsage(sender);
            }
        }
    }
}
