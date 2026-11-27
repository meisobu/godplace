package com.beast.godplace;

import net.minecraft.client.Minecraft;
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

@Mod(modid = "godplacefast", name = "GodPlace", version = "1.0", clientSideOnly = true)
public class FastPlaceMod {

    public static boolean enabled = false;
    public static int delayHalfTicks = 0; // 0..6  =  0, 0.5, 1, 1.5, 2, 2.5, 3
    public static String activationMode = "toggle";
    public static boolean debugMode = false;
    public static boolean voidUnlock = true; // desativa a trava de face quando ha void abaixo

    private final Minecraft mc = Minecraft.getMinecraft();

    private static File configFile;
    private static final String CONFIG_FOLDER = "godplace";
    private static final String CONFIG_FILE_NAME = "settings.cfg";

    private Field rightClickDelayField;

    private boolean locked = false;
    private EnumFacing lockedFace = null;
    private boolean voidBelow = false;

    public static KeyBinding godPlaceKeyBind;

    private int internalDelayCounter = 0;
    private int currentGapTicks = 1;
    private boolean gapFlip = false;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new GodPlaceCommand());

        godPlaceKeyBind = new KeyBinding("key.godplace.toggle", Keyboard.KEY_G, "key.categories.misc");
        ClientRegistry.registerKeyBinding(godPlaceKeyBind);

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
            voidBelow = false;
            internalDelayCounter = 0;
            return;
        }

        // Detecta void abaixo do jogador; se houver, solta a trava de face
        voidBelow = voidUnlock && isVoidBelow();
        if (voidBelow && locked) {
            locked = false;
            lockedFace = null;
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

    // ==================== VOID DETECTION ====================
    // Retorna true se NAO existe nenhum bloco abaixo do jogador (em nenhuma das colunas
    // que a hitbox ocupa), da altura dos pes ate y=0. Em cima de bloco/chao retorna false.
    private boolean isVoidBelow() {
        if (mc.thePlayer == null || mc.theWorld == null) return false;

        AxisAlignedBB bb = mc.thePlayer.getEntityBoundingBox();
        int minX = MathHelper.floor_double(bb.minX);
        int maxX = MathHelper.floor_double(bb.maxX);
        int minZ = MathHelper.floor_double(bb.minZ);
        int maxZ = MathHelper.floor_double(bb.maxZ);
        int startY = MathHelper.floor_double(bb.minY);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = startY; y >= 0; y--) {
                    if (!mc.theWorld.isAirBlock(new BlockPos(x, y, z))) {
                        return false; // achou bloco/chao, nao e void
                    }
                }
            }
        }
        return true;
    }

    // Ticks de espera ate a proxima colocacao: delay 0 = 1 tick, delay 3 = 4 ticks (igual ao vanilla).
    // Delays com .5 alternam entre dois valores, entao a media fica no meio.
    private int nextGapTicks() {
        int gapHalf = delayHalfTicks + 2;
        int gap = gapHalf / 2;
        if (gapHalf % 2 == 1) {
            if (gapFlip) gap++;
            gapFlip = !gapFlip;
        }
        return gap;
    }

    private void manageDelayWithField() {
        try {
            int current = rightClickDelayField.getInt(mc);
            if (current > currentGapTicks) {
                rightClickDelayField.setInt(mc, currentGapTicks);
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Delay field error: " + e.getMessage());
        }
    }

    private void manageDelayWithCounter() {
        if (internalDelayCounter > 0) internalDelayCounter--;
    }

    // ==================== INSTANT FACE LOCK ====================
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (!enabled || event.entityPlayer != mc.thePlayer) return;
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        EnumFacing hitFace = event.face;

        if (voidBelow) {
            // Void embaixo: sem trava, qualquer face liberada
            locked = false;
            lockedFace = null;
            sendDebug("VOID abaixo: trava desativada");
        } else if (!locked) {
            locked = true;
            lockedFace = hitFace;
            sendDebug("LOCKED " + hitFace.name().toUpperCase() + " (INSTANT)");
        } else {
            if (hitFace != lockedFace) {
                event.setCanceled(true);
                return;
            }
        }

        if (rightClickDelayField == null && internalDelayCounter > 0) {
            event.setCanceled(true);
            return;
        }

        currentGapTicks = nextGapTicks();
        if (rightClickDelayField == null) {
            internalDelayCounter = currentGapTicks;
        }
    }

    // ==================== CHAT MESSAGES ====================
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
                    case "placementDelay": delayHalfTicks = parseDelayHalf(value); break;
                    case "activationMode": activationMode = value; break;
                    case "debugMode": debugMode = Boolean.parseBoolean(value); break;
                    case "voidUnlock": voidUnlock = Boolean.parseBoolean(value); break;
                }
            }
        } catch (Exception e) {
            System.err.println("[GodPlace] Load failed: " + e.getMessage());
        }
    }

    private static int parseDelayHalf(String value) {
        try {
            double d = Double.parseDouble(value.replace(',', '.'));
            int half = (int) Math.round(d * 2);
            return Math.max(0, Math.min(6, half));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String delayText(int half) {
        return (half % 2 == 0) ? String.valueOf(half / 2) : (half / 2) + ".5";
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
            writer.write("placementDelay=" + delayText(delayHalfTicks) + "\n");
            writer.write("activationMode=" + activationMode + "\n");
            writer.write("debugMode=" + debugMode + "\n");
            writer.write("voidUnlock=" + voidUnlock + "\n");
            writer.flush();
        } catch (Exception e) {}
    }

    // ==================== COMMAND ====================
    public static class GodPlaceCommand extends CommandBase {
        @Override public String getCommandName() { return "gp"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/gp [delay|mode|debug|void]"; }
        @Override public java.util.List<String> getCommandAliases() { return Arrays.asList("godplace"); }
        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                enabled = !enabled;
                sender.addChatMessage(new ChatComponentText(
                    enabled ? "[GodPlace] \u00a7aON \u00a77(delay: " + delayText(delayHalfTicks) + ")" : "[GodPlace] \u00a7cOFF"
                ));
                return;
            }

            String cmd = args[0].toLowerCase();

            if ("delay".equals(cmd) && args.length > 1) {
                try {
                    double d = Double.parseDouble(args[1].replace(',', '.'));
                    int half = (int) Math.round(d * 2);
                    if (Math.abs(d * 2 - half) < 1e-9 && half >= 0 && half <= 6) {
                        delayHalfTicks = half;
                        sender.addChatMessage(new ChatComponentText("[GodPlace] \u00a7fDelay: \u00a7e" + delayText(half)));
                        saveSettings();
                    } else {
                        sender.addChatMessage(new ChatComponentText("\u00a7c0, 0.5, 1, 1.5, 2, 2.5 ou 3"));
                    }
                } catch (Exception e) {
                    sender.addChatMessage(new ChatComponentText("\u00a7c/gp delay <0-3>  (de 0.5 em 0.5)"));
                }
            }

            else if ("mode".equals(cmd) && args.length > 1) {
                String m = args[1].toLowerCase();
                if ("hold".equals(m) || "toggle".equals(m)) {
                    activationMode = m;
                    sender.addChatMessage(new ChatComponentText("[GodPlace] \u00a7fMode: \u00a7e" + m));
                    saveSettings();
                } else {
                    sender.addChatMessage(new ChatComponentText("\u00a7chold or toggle"));
                }
            }

            else if ("debug".equals(cmd)) {
                debugMode = !debugMode;
                sender.addChatMessage(new ChatComponentText(
                    "\u00a78[GP-DEBUG] \u00a77Debug: " + (debugMode ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("void".equals(cmd)) {
                voidUnlock = !voidUnlock;
                sender.addChatMessage(new ChatComponentText(
                    "[GodPlace] \u00a7fVoid unlock: " + (voidUnlock ? "\u00a7aON" : "\u00a7cOFF")
                ));
                saveSettings();
            }

            else if ("about".equals(cmd)) {
                sender.addChatMessage(new ChatComponentText(
                    "[GodPlace] \u00a7fA mod designed to help with block placements made by sheep :3"
                ));
            }

            else {
                sender.addChatMessage(new ChatComponentText(
                    "[GodPlace] \u00a7f/gp, delay, mode, debug, void, about"
                ));
            }
        }
    }
}
