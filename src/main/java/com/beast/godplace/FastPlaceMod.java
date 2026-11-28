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
                int current =
