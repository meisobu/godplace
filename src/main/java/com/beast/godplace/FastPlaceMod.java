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

@Mod(modid = "godplacefast", name = "GodPlace", version = "1.0", clientSideOnly = true)
public class FastPlaceMod {

    public static final int MAX_DELAY = 3;

    public static boolean enabled = false;
    public static int placementDelay = 0;
    public static String activationMode = "toggle";

    private final Minecraft mc = Minecraft.getMinecraft();

    private static File configFile;
    private static final String CONFIG_FOLDER = "godplace";
    private static final String CONFIG_FILE_NAME = "settings.cfg";

    private Field rightClickDelayField;
    private boolean fieldsInitialized = false;

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
    private void initFieldsSafely() {
        if (fieldsInitialized || mc.thePlayer == null) return;

        String[] rightClickNames = { "rightClickDelayTimer", "field_71467_ac" };
        for (String name : rightClickNames) {
            try {
                Field f = Minecraft.class.getDeclaredField(name);
                f.setAccessible(true);
                rightClickDelayField = f;
                break;
            } catch (Exception ignored) {
            }
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

    private void handleKeybind() {
        if ("toggle".equalsIgnoreCase(activationMode)) {
            if (godPlaceKeyBind.isPressed()) {
                enabled = !enabled;
            }
        } else {
            enabled = godPlaceKeyBind.isKeyDown();
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
            return;
        }

        if (hitFace != lockedFace) {
            event.setCanceled(true);
            return;
        }

        if (rightClickDelayField == null && internalDelayCounter > 0) {
            event.setCanceled(true);
        }
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
                    case "placementDelay":
                        placementDelay = Math.max(0, Math.min(MAX_DELAY, Integer.parseInt(value)));
                        break;
                    case "activationMode":
                        activationMode = value;
                        break;
                    default:
                        break;
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
            } catch (Exception e) {
                return;
            }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write("# GodPlace Config\n");
            writer.write("placementDelay=" + placementDelay + "\n");
            writer.write("activationMode=" + activationMode + "\n");
            writer.flush();
        } catch (Exception e) {
            System.err.println("[GodPlace] Save failed: " + e.getMessage());
        }
    }

    // ==================== COMMAND ====================
    public static class GodPlaceCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "godplace";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/godplace [toggle] [mode] [delay]";
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender) {
            return true;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "toggle", "mode", "delay");
            }
            if (args.length == 2) {
                if ("mode".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "hold", "toggle");
                }
                if ("delay".equalsIgnoreCase(args[0])) {
                    return getListOfStringsMatchingLastWord(args, "0", "1", "2", "3");
                }
            }
            return null;
        }

        // [ godplace ] : [ toggle : on ] [ mode : hold ] [ delay : 1 ]
        private void sendStatus(ICommandSender sender) {
            ChatComponentText msg = new ChatComponentText("[ godplace ] : ");
            ChatComponentText info = new ChatComponentText(
                    "[ toggle : " + (enabled ? "on" : "off") + " ] "
                            + "[ mode : " + activationMode + " ] "
                            + "[ delay : " + placementDelay + " ]");
            info.getChatStyle().setColor(EnumChatFormatting.GRAY);
            msg.appendSibling(info);
            sender.addChatMessage(msg);
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                sendStatus(sender);
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("toggle".equals(cmd)) {
                enabled = !enabled;
                sendStatus(sender);
            } else if ("mode".equals(cmd) && args.length > 1) {
                String m = args[1].toLowerCase();
                if ("hold".equals(m) || "toggle".equals(m)) {
                    activationMode = m;
                    saveSettings();
                    sendStatus(sender);
                } else {
                    sender.addChatMessage(new ChatComponentText("/godplace mode <hold|toggle>"));
                }
            } else if ("delay".equals(cmd) && args.length > 1) {
                try {
                    int d = Integer.parseInt(args[1]);
                    if (d >= 0 && d <= MAX_DELAY) {
                        placementDelay = d;
                        saveSettings();
                        sendStatus(sender);
                    } else {
                        sender.addChatMessage(new ChatComponentText("/godplace delay <0-" + MAX_DELAY + ">"));
                    }
                } catch (NumberFormatException e) {
                    sender.addChatMessage(new ChatComponentText("/godplace delay <0-" + MAX_DELAY + ">"));
                }
            } else {
                sendStatus(sender);
            }
        }
    }
}
