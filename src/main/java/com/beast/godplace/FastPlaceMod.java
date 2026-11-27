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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;

@Mod(modid = "godplacefast", name = "GodPlace", version = "1.1", clientSideOnly = true)
public class FastPlaceMod {

    public static boolean enabled = false;
    public static int delayHalfTicks = 0; // 0..6  =  0, 0.5, 1, 1.5, 2, 2.5, 3
    public static String activationMode = "toggle";
    public static boolean debugMode = false;

    private final Minecraft mc = Minecraft.getMinecraft();

    private static File configFile;
    private static final String CONFIG_FOLDER = "godplace";
    private static final String CONFIG_FILE_NAME = "settings.cfg";

    // Minimo entre duas tentativas feitas pelo retry por frame (evita spam de pacotes)
    private static final long FRAME_RETRY_MIN_NANOS = 15000000L; // 15 ms

    private Field rightClickDelayField;
    private Method rightClickMethod;

    private boolean locked = false;
    private EnumFacing lockedFace = null;

    public static KeyBinding godPlaceKeyBind;

    // static: o comando /gp delay atualiza isso na hora
    private static volatile int currentGapTicks = 1;
    private boolean gapFlip = false;

    // true se uma colocacao valida (evento nao cancelado) aconteceu neste tick
    private boolean placedThisTick = false;
    private long lastFrameAttempt = 0L;

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new GodPlaceCommand());

        godPlaceKeyBind = new KeyBinding("key.godplace.toggle", Keyboard.KEY_G, "key.categories.misc");
        ClientRegistry.registerKeyBinding(godPlaceKeyBind);

        initConfigFile();
        loadSettings();
        currentGapTicks = baseGap();
    }

    // ==================== REFLECTION (NO PRIMEIRO TICK) ====================
    private boolean fieldsInitialized = false;

    private void initFieldsSafely() {
        if (fieldsInitialized || mc.thePlayer == null) return;

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

        String[] methodNames = { "rightClickMouse", "func_147121_ag" };
        for (String name : methodNames) {
            try {
                Method m = Minecraft.class.getDeclaredMethod(name);
                m.setAccessible(true);
                rightClickMethod = m;
                System.out.println("[GodPlace] Found rightClickMouse: " + name);
                break;
            } catch (Exception ignored) {}
        }

        if (rightClickDelayField == null) {
            System.err.println("[GodPlace] Delay field NOT found - delay control disabled");
        }
        if (rightClickMethod == null) {
            System.err.println("[GodPlace] rightClickMouse NOT found - per-frame retry disabled");
        }

        fieldsInitialized = true;
    }

    private int getTimer() {
        try {
            return rightClickDelayField.getInt(mc);
        } catch (Exception e) {
            return 0;
        }
    }

    private void setTimer(int value) {
        try {
            rightClickDelayField.setInt(mc, value);
        } catch (Exception e) {
            System.err.println("[GodPlace] Delay field error: " + e.getMessage());
        }
    }

    // ==================== TICK ====================
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (event.phase == TickEvent.Phase.START) {
            placedThisTick = false;
            return;
        }

        if (!fieldsInitialized) {
            initFieldsSafely();
        }

        handleKeybind();
        if (!enabled) {
            locked = false;
            lockedFace = null;
            return;
        }

        if (rightClickDelayField != null) {
            manageDelay();
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

    // Ticks de espera ate a proxima colocacao: delay 0 = 1 tick, delay 3 = 4 ticks (igual ao vanilla).
    private static int baseGap() {
        return (delayHalfTicks + 2) / 2;
    }

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

    private void manageDelay() {
        int current = getTimer();
        if (current <= currentGapTicks) return;

        // O vanilla acabou de por o timer em 4 (ou 10). Se com bloco na mao a tentativa
        // NAO colocou nada (mira no ar / face errada), nao cobra o delay: libera na hora
        // para o retry (proximo tick ou proximo frame).
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean block = held != null && held.getItem() instanceof ItemBlock;
        boolean holding = mc.gameSettings.keyBindUseItem.isKeyDown();

        if (block && holding && !placedThisTick) {
            setTimer(0);
        } else {
            setTimer(currentGapTicks);
        }
    }

    // ==================== RETRY POR FRAME ====================
    // O jogo so tenta colocar no inicio do tick, com a mira do ultimo frame. Se falhou,
    // aqui a gente tenta de novo no primeiro frame em que a mira estiver valida.
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!enabled || rightClickMethod == null || rightClickDelayField == null) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null || !mc.inGameHasFocus) return;
        if (!mc.gameSettings.keyBindUseItem.isKeyDown()) return;
        if (mc.thePlayer.isUsingItem()) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (getTimer() != 0) return;

        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        if (locked && mop.sideHit != lockedFace) return;

        long now = System.nanoTime();
        if (now - lastFrameAttempt < FRAME_RETRY_MIN_NANOS) return;
        lastFrameAttempt = now;

        try {
            rightClickMethod.invoke(mc);
        } catch (Exception e) {
            System.err.println("[GodPlace] Frame retry failed: " + e);
            rightClickMethod = null; // desliga o retry se der erro
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

        if (!locked) {
            locked = true;
            lockedFace = hitFace;
            sendDebug("LOCKED " + hitFace.name().toUpperCase() + " (INSTANT)");
        } else if (hitFace != lockedFace) {
            // Face errada: cancela, mas NAO cobra delay - tenta de novo logo.
            event.setCanceled(true);
            if (rightClickDelayField != null) setTimer(0);
            return;
        }

        placedThisTick = true;
        currentGapTicks = nextGapTicks();
        if (rightClickDelayField != null) {
            setTimer(currentGapTicks);
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
            writer.flush();
        } catch (Exception e) {}
    }

    // ==================== COMMAND ====================
    public static class GodPlaceCommand extends CommandBase {
        @Override public String getCommandName() { return "gp"; }
        @Override public String getCommandUsage(ICommandSender s) { return "/gp [delay|mode|debug]"; }
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
                        currentGapTicks = baseGap(); // vale ja na proxima colocacao
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

            else if ("about".equals(cmd)) {
                sender.addChatMessage(new ChatComponentText(
                    "[GodPlace] \u00a7fA mod designed to help with block placements made by sheep :3"
                ));
            }

            else {
                sender.addChatMessage(new ChatComponentText(
                    "[GodPlace] \u00a7f/gp, delay, mode, debug, about"
                ));
            }
        }
    }
}
