package com.beast.godplace;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;

@Mod(modid = "godplace", name = "GodPlace", version = "1.0", clientSideOnly = true)
public class FastPlaceMod {

    public static boolean enabled = false;
    public static int placementDelay = 0;
    public static String activationMode = "toggle";
    public static boolean noJumpDelayEnabled = false;
    public static boolean alertsEnabled = true;
    public static boolean debugMode = false;
    public static boolean blockBottomEnabled = true; // true = bloqueia cliques na face de baixo

    private final Minecraft mc = Minecraft.getMinecraft();

    private static File configFile;
    private static final String CONFIG_FOLDER = "godplace";
    private static final String CONFIG_FILE_NAME = "settings.cfg";

    private Field rightClickDelayField;
    private Field jumpDelayField;

    private boolean locked = false;
    private EnumFacing lockedFace = null;

    private boolean wasKeyPressed = false;
    public static KeyBinding godPlaceKeyBind;

    private int internalDelayCounter = 0;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new GodPlaceCommand());

        godPlaceKeyBind = new KeyBinding("key.godplace.toggle", Keyboard.KEY_G, "key.categories.misc");
        ClientRegistry.registerKeyBinding(godPlaceKeyBind);

        // Delay field search until player exists
        // findDelayFields();  ← REMOVED FROM HERE
        initConfigFile();
        loadSettings();
    }

    // ==================== SAFE FIELD SEARCH (ON FIRST TICK) ====================
    private boolean fieldsInitialized = false;

    private void initFieldsSafely() {
        if (fieldsInitialized || mc.thePlayer == null) return;

        // RIGHT CLICK DELAY
        String[] rightClickNames = { "rightClickDelayTimer", "field_71467_ac" };
        for (String name : rightClickNames) {
            try {
                Field f = Minecraft.class.getDeclaredField(name);
                f.setAccessible(true);
                rightClickDelayField = f;
                System.out.println("[GodPlace] Found right-click delay: " + name);
                break;
            } catch (Exception ignored) {}
        }

        // JUMP DELAY
        try {
            Field movementField = EntityPlayerSP.class.getDeclaredField("movementInput");
            movementField.setAccessible(true);
            Object movementInput = movementField.get(mc.thePlayer);
            if (movementInput != null) {
                String[] jumpNames = { "jump", "field_78800_z" };
                for (String name : jumpNames) {
                    try {
                        jumpDelayField = movementInput.getClass().getDeclaredField(name);
                        jumpDelayField.setAccessible(true);
                        System.out.println("[GodPlace] Found jump field: " + name);
                        break;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Jump field failed: " + e.getMessage());
        }

        fieldsInitialized = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // Initialize fields only when player exists
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

        if (noJumpDelayEnabled && jumpDelayField != null) {
            handleNoJumpDelay();
        }

        // Reset lock on RMB release
        if (!mc.gameSettings.keyBindUseItem.isKeyDown() && locked) {
            locked = false;
            lockedFace = null;
        }
    }

    private void handleKeybind() {
        boolean isKeyDown = godPlaceKeyBind.isKeyDown();

        if ("toggle".equalsIgnoreCase(activationMode)) {
            if (godPlaceKeyBind.isPressed()) {
                enabled = !enabled;
                sendAlert(enabled ? "ON (delay: " + placementDelay + ")" : "OFF");
            }
        } else {
            if (isKeyDown && !wasKeyPressed) {
                sendAlert("ON (hold mode)");
                wasKeyPressed = true;
            }
            enabled = isKeyDown;
            if (!isKeyDown && wasKeyPressed) {
                sendAlert("OFF");
                wasKeyPressed = false;
            }
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

    private void handleNoJumpDelay() {
        if (mc.thePlayer == null || jumpDelayField == null) return;
        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            try {
                jumpDelayField.setBoolean(mc.thePlayer.movementInput, true);
            } catch (Exception e) {}
        }
    }

    // ==================== INSTANT FACE LOCK ====================
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (!enabled || event.entityPlayer != mc.thePlayer) return;
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        EnumFacing hitFace = event.face;

        if (blockBottomEnabled && hitFace == EnumFacing.DOWN) {
            event.setCanceled(true);
            return;
        }

        if (!locked) {
            locked = true;
            lockedFace = hitFace;
            sendDebug("LOCKED " + hitFace.name().toUpperCase() + " (INSTANT)");
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

    // ==================== CHAT MESSAGES ====================
    private void sendAlert(String msg) {
        if (!alertsEnabled || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText("\u00a75[GodPlace] \u00a7f" + msg));
    }

    private void sendDebug(String msg) {
        if (!debugMode || mc.thePlayer == null) return;
        mc.thePlayer.addChatMessage(new ChatComponentText("\u00a78[GP-DEBUG] \u00a77" + msg));
    }

    // ==================== CONFIG ====================
    private void initConfigFile() {
        File minecraftDir = mc.mcDataDir;
        File godplaceDir = new File(minecraftDir, CONFIG_FOLDER);
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
                    case "noJumpDelayEnabled": noJumpDelayEnabled = Boolean.parseBoolean(value); break;
                    case "alertsEnabled": alertsEnabled = Boolean.parseBoolean(value); break;
                    case "debugMode": debugMode = Boolean.parseBoolean(value); break;
                    case "blockBottomEnabled": blockBottomEnabled = Boolean.parseBoolean(value); break;
                }
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Load failed: " + e.getMessage());
        }
    }

    public static void saveSettings() {
        if (configFile == null) {
            try {
                File minecraftDir = Minecraft.getMinecraft().mcDataDir;
                File godplaceDir = new File(minecraftDir, CONFIG_FOLDER);
                if (!godplaceDir.exists()) godplaceDir.mkdirs();
                configFile = new File(godplaceDir, CONFIG_FILE_NAME);
            } catch (Exception e) { return; }
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
            writer.write("# GodPlace Config\n");
            writer.write("placementDelay=" + placementDelay + "\n");
            writer.write("activationMode=" + activationMode + "\n");
            writer.write("noJumpDelayEnabled=" + noJumpDelayEnabled + "\n");
            writer.write("alertsEnabled=" + alertsEnabled + "\n");
            writer.write("debugMode=" + debugMode + "\n");
            writer.write("blockBottomEnabled=" + blockBottomEnabled + "\n");
            writer.flush();
        } catch (Exception e) {}
    }

    // ==================== COMMAND ====================
    public static class GodPlaceCommand extends CommandBase {
        @Override public String getCommandName() { return "gp"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/gp [delay|mode|nojumpdelay|alert|debug|bottom]"; }
        @Override public java.util.List<String> getCommandAliases() { return Arrays.asList("godplace"); }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                enabled = !enabled;
                sender.addChatMessage(new ChatComponentText(
                    enabled ? "\u00a75[GodPlace] \u00a7aON \u00a77(delay: " + placementDelay + ")" : "\u00a75[GodPlace] \u00a7cOFF"
                ));
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("delay".equals(cmd) && args.length > 1) {
                try {
                    int d = Integer.parseInt(args[1]);
                    if (d >= 0 && d <= 5) {
                        placementDelay = d;
                        sender.addChatMessage(new ChatComponentText("\u00a75[GodPlace] \u00a7fDelay: \u00a7e" + d));
                        saveSettings();
                    } else {
                        sender.addChatMessage(new ChatComponentText("\u00a7c0-5 only"));
                    }
                } catch (Exception e) {
                    sender.addChatMessage(new ChatComponentText("\u00a7c/gp delay <0-5>"));
                }
            }

            else if ("mode".equals(cmd) && args.length > 1) {
                String m = args[1].toLowerCase();
                if ("hold".equals(m) || "toggle".equals(m)) {
                    activationMode = m;
                    sender.addChatMessage(new ChatComponentText("\u00a75[GodPlace] \u00a7fMode: \u00a7e" + m));
                    saveSettings();
                } else {
                    sender.addChatMessage(new ChatComponentText("\u00a7chold or toggle"));
                }
            }

            else if ("nojumpdelay".equals(cmd)) {
                noJumpDelayEnabled = !noJumpDelayEnabled;
                sender.addChatMessage(new ChatComponentText(
                    "\u00a75[GodPlace] \u00a7fNoJumpDelay: " + (noJumpDelayEnabled ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("alert".equals(cmd)) {
                alertsEnabled = !alertsEnabled;
                sender.addChatMessage(new ChatComponentText(
                    "\u00a75[GodPlace] \u00a7fAlerts: " + (alertsEnabled ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("debug".equals(cmd)) {
                debugMode = !debugMode;
                sender.addChatMessage(new ChatComponentText(
                    "\u00a78[GP-DEBUG] \u00a77Debug: " + (debugMode ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("bottom".equals(cmd)) {
                blockBottomEnabled = !blockBottomEnabled;
                sender.addChatMessage(new ChatComponentText(
                    "\u00a75[GodPlace] \u00a7fBloquear face de baixo: " + (blockBottomEnabled ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("about".equals(cmd)) {
                sender.addChatMessage(new ChatComponentText(
                    "\u00a75[GodPlace] \u00a7fA mod designed to help with block placements made by sheep :3"
                ));
            }

            else {
                sender.addChatMessage(new ChatComponentText(
                    "\u00a75[GodPlace] \u00a7f/gp, delay, mode, nojumpdelay, alert, debug, bottom, about"
                ));
            }
        }
    }
}
