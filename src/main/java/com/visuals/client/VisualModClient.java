package com.visuals.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class VisualModClient implements ClientModInitializer {
    public static final String MOD_ID = "visuals_mod";
    public static VisualModClient INSTANCE;

    private static Method cachedDrawTextString = null;
    private static Method cachedDrawTextText = null;
    private static boolean textRendererInitialized = false;

    private KeyBinding clickGuiKey;
    private KeyBinding cosmeticsGuiKey;
    private final ModuleManager moduleManager = new ModuleManager();
    private boolean wasInsertKeyDown = false;
    private boolean wasRShiftKeyDown = false;
    private final boolean[] keyStates = new boolean[512];

    public static LivingEntity currentCombatTarget = null;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        clickGuiKey = registerKeyBindingSafely("key.visuals.clickgui", GLFW.GLFW_KEY_INSERT, "category.visuals");
        cosmeticsGuiKey = registerKeyBindingSafely("key.visuals.cosmetics", GLFW.GLFW_KEY_RIGHT_SHIFT, "category.visuals");
        moduleManager.init();

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || mc.options.hudHidden) return;

            CrosshairModule ch = moduleManager.getModule(CrosshairModule.class);
            if (ch != null && ch.isEnabled()) {
                ch.renderCrosshair(drawContext, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
            }

            TargetHudModule th = moduleManager.getModule(TargetHudModule.class);
            if (th != null && th.isEnabled()) {
                th.render(drawContext, mc);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean openClickGuiRequested = false;
            boolean openCosmeticsRequested = false;

            if (clickGuiKey != null && clickGuiKey.wasPressed()) openClickGuiRequested = true;
            if (cosmeticsGuiKey != null && cosmeticsGuiKey.wasPressed()) openCosmeticsRequested = true;

            if (client.getWindow() != null && client.getWindow().getHandle() != 0) {
                long handle = client.getWindow().getHandle();
                boolean isInsertDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS;
                boolean isRShiftDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

                if (isInsertDown && !wasInsertKeyDown) openClickGuiRequested = true;
                if (isRShiftDown && !wasRShiftKeyDown) openCosmeticsRequested = true;

                wasInsertKeyDown = isInsertDown;
                wasRShiftKeyDown = isRShiftDown;

                if (client.currentScreen == null) {
                    for (Module m : moduleManager.getAllModules()) {
                        int bind = m.getKeyBind();
                        if (bind > 0 && bind < 512) {
                            boolean pressed = GLFW.glfwGetKey(handle, bind) == GLFW.GLFW_PRESS;
                            if (pressed && !keyStates[bind]) {
                                m.toggle();
                                playSoundSafely(SoundEvents.UI_BUTTON_CLICK, 1.2f);
                            }
                            keyStates[bind] = pressed;
                        }
                    }
                }
            }

            if (openClickGuiRequested && client.currentScreen == null) {
                client.setScreen(new ModernRefinedClickGui(moduleManager));
            } else if (openCosmeticsRequested && client.currentScreen == null) {
                client.setScreen(new CosmeticsScreen(moduleManager));
            }

            if (client.world != null && client.player != null) {
                moduleManager.onTick(client);

                TargetHudModule th = moduleManager.getModule(TargetHudModule.class);
                double maxRange = th != null ? th.maxDistance.get() : 24.0;
                double maxRangeSq = maxRange * maxRange;

                if (currentCombatTarget != null) {
                    if (!currentCombatTarget.isAlive() || currentCombatTarget.isRemoved() || client.player.squaredDistanceTo(currentCombatTarget) > maxRangeSq) {
                        currentCombatTarget = null;
                    }
                }

                if (client.crosshairTarget instanceof EntityHitResult eHit && eHit.getEntity() instanceof LivingEntity living) {
                    if (living.isAlive() && living != client.player && client.player.squaredDistanceTo(living) <= maxRangeSq) {
                        currentCombatTarget = living;
                    }
                }
            }
        });
    }

    private KeyBinding registerKeyBindingSafely(String translationKey, int defaultKey, String category) {
        try {
            Constructor<KeyBinding> ctor = KeyBinding.class.getConstructor(String.class, int.class, String.class);
            KeyBinding binding = ctor.newInstance(translationKey, defaultKey, category);
            KeyBindingHelper.registerKeyBinding(binding);
            return binding;
        } catch (Throwable ignored) {
            try {
                for (Constructor<?> ctor : KeyBinding.class.getConstructors()) {
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 3 && params[0] == String.class && params[1] == int.class) {
                        Object catObj = resolveCategoryObject(params[2]);
                        KeyBinding binding = (KeyBinding) ctor.newInstance(translationKey, defaultKey, catObj);
                        KeyBindingHelper.registerKeyBinding(binding);
                        return binding;
                    }
                }
            } catch (Throwable fallback) {
                System.out.println("[VisualMod] Direct GLFW polling active.");
            }
        }
        return null;
    }

    private Object resolveCategoryObject(Class<?> catClass) {
        if (catClass == String.class) return "category.visuals";
        try {
            for (Field f : catClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && catClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public static void playSoundSafely(Object soundObj, float pitch) {
        if (soundObj == null) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getSoundManager() == null) return;

            Object soundInstance = null;
            for (Method m : PositionedSoundInstance.class.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("master") && m.getParameterCount() == 2) {
                    Class<?>[] pTypes = m.getParameterTypes();
                    if (pTypes[1] == float.class) {
                        if (pTypes[0].isAssignableFrom(soundObj.getClass())) {
                            soundInstance = m.invoke(null, soundObj, pitch);
                            break;
                        }
                        try {
                            Method valMethod = soundObj.getClass().getMethod("value");
                            Object inner = valMethod.invoke(soundObj);
                            if (pTypes[0].isAssignableFrom(inner.getClass())) {
                                soundInstance = m.invoke(null, inner, pitch);
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (soundInstance != null) {
                for (Method pm : mc.getSoundManager().getClass().getMethods()) {
                    if (pm.getName().equals("play") && pm.getParameterCount() == 1) {
                        pm.invoke(mc.getSoundManager(), soundInstance);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void drawTextSafe(DrawContext context, Object textRenderer, String text, int x, int y, int color, boolean shadow) {
        if (!textRendererInitialized) {
            initDrawTextMethods();
        }

        if (cachedDrawTextString != null) {
            try {
                cachedDrawTextString.invoke(context, textRenderer, text, x, y, color, shadow);
                return;
            } catch (Throwable ignored) {}
        }

        if (cachedDrawTextText != null) {
            try {
                cachedDrawTextText.invoke(context, textRenderer, Text.literal(text), x, y, color, shadow);
                return;
            } catch (Throwable ignored) {}
        }
    }

    public static void drawStyledText(DrawContext context, Object textRenderer, String text, int x, int y, int color) {
        drawTextSafe(context, textRenderer, text, x, y, color, true);
    }

    private static void initDrawTextMethods() {
        textRendererInitialized = true;
        try {
            for (Method m : DrawContext.class.getMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 6 && params[2] == int.class && params[3] == int.class && params[4] == int.class && params[5] == boolean.class) {
                    if (params[1] == String.class && cachedDrawTextString == null) {
                        m.setAccessible(true);
                        cachedDrawTextString = m;
                    } else if (params[1] == Text.class && cachedDrawTextText == null) {
                        m.setAccessible(true);
                        cachedDrawTextText = m;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public enum Category {
        COMBAT("Combat", "⚔"),
        MOVEMENT("Movement", "»"),
        RENDER("Render", "◉"),
        REMOVALS("Removals", "✂"),
        MISC("Misc", "✱");

        private final String displayName;
        private final String icon;

        Category(String displayName, String icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public String getIcon() { return icon; }
    }

    public static abstract class Setting<T> {
        protected final String name;
        protected T value;

        public Setting(String name, T defaultValue) {
            this.name = name;
            this.value = defaultValue;
        }

        public String getName() { return name; }
        public T get() { return value; }
        public void set(T value) { this.value = value; }
    }

    public static class BooleanSetting extends Setting<Boolean> {
        public BooleanSetting(String name, boolean defaultValue) { super(name, defaultValue); }
        public void toggle() { this.value = !this.value; }
    }

    public static class SliderSetting extends Setting<Double> {
        private final double min, max, increment;
        private final String suffix;

        public SliderSetting(String name, double defaultValue, double min, double max, double increment, String suffix) {
            super(name, defaultValue);
            this.min = min;
            this.max = max;
            this.increment = increment;
            this.suffix = suffix;
        }

        public double getMin() { return min; }
        public double getMax() { return max; }
        public String getSuffix() { return suffix; }

        public void setValueClamped(double val) {
            double precision = 1.0 / increment;
            double rounded = Math.round(val * precision) / precision;
            this.value = MathHelper.clamp(rounded, min, max);
        }
    }

    public static class ModeSetting extends Setting<String> {
        private final List<String> modes;
        private int index;

        public ModeSetting(String name, String defaultMode, List<String> modes) {
            super(name, defaultMode);
            this.modes = modes;
            this.index = Math.max(0, modes.indexOf(defaultMode));
        }

        public void cycle() {
            index = (index + 1) % modes.size();
            this.value = modes.get(index);
        }

        public List<String> getModes() { return modes; }
        public void setMode(String mode) {
            int idx = modes.indexOf(mode);
            if (idx != -1) {
                this.index = idx;
                this.value = mode;
            }
        }
    }

    public static abstract class Module {
        private final String name;
        private final String description;
        private final Category category;
        private boolean enabled;
        private int keyBind = GLFW.GLFW_KEY_UNKNOWN;
        private boolean listeningForBind = false;
        private final List<Setting<?>> settings = new ArrayList<>();

        public Module(String name, String description, Category category) {
            this.name = name;
            this.description = description;
            this.category = category;
        }

        public void toggle() { setEnabled(!this.enabled); }

        public void setEnabled(boolean state) {
            if (this.enabled != state) {
                this.enabled = state;
                if (state) onEnable(); else onDisable();
            }
        }

        public void onEnable() {}
        public void onDisable() {}
        public void onTick(MinecraftClient client) {}

        protected void registerSetting(Setting<?> setting) { this.settings.add(setting); }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Category getCategory() { return category; }
        public boolean isEnabled() { return enabled; }
        public List<Setting<?>> getSettings() { return settings; }
        public int getKeyBind() { return keyBind; }
        public void setKeyBind(int key) { this.keyBind = key; }
        public boolean isListeningForBind() { return listeningForBind; }
        public void setListeningForBind(boolean listening) { this.listeningForBind = listening; }

        public String getBindName() {
            if (listeningForBind) return "...";
            if (keyBind == GLFW.GLFW_KEY_UNKNOWN) return "";
            String kn = GLFW.glfwGetKeyName(keyBind, 0);
            if (kn != null && !kn.isEmpty()) return kn.toUpperCase();
            return switch (keyBind) {
                case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
                case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
                case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
                case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
                case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
                case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
                case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
                case GLFW.GLFW_KEY_TAB -> "TAB";
                case GLFW.GLFW_KEY_SPACE -> "SPC";
                default -> "K" + keyBind;
            };
        }
    }

    public static class TriggerBotModule extends Module {
        public final SliderSetting cooldown = new SliderSetting("Кулдаун", 0.95, 0.70, 1.0, 0.02, "%");
        public final BooleanSetting critOnly = new BooleanSetting("Только криты", false);

        public TriggerBotModule() {
            super("TriggerBot", "Автоматический удар при наведении на цель с проверкой критов", Category.COMBAT);
            registerSetting(cooldown);
            registerSetting(critOnly);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.interactionManager == null) return;
            if (client.player.getAttackCooldownProgress(0.0f) < cooldown.get().floatValue()) return;

            if (critOnly.get() && !isPlayerReadyForCrit(client)) return;

            Entity target = findTarget(client);
            if (target instanceof LivingEntity living && living.isAlive() && target != client.player) {
                currentCombatTarget = living;
                client.interactionManager.attackEntity(client.player, target);
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }

        private boolean isPlayerReadyForCrit(MinecraftClient client) {
            if (client.player == null) return false;
            return client.player.fallDistance > 0.0f
                    && !client.player.isOnGround()
                    && !client.player.isClimbing()
                    && !client.player.isTouchingWater()
                    && !client.player.hasStatusEffect(StatusEffects.BLINDNESS)
                    && !client.player.hasVehicle()
                    && !client.player.isSprinting();
        }

        public static Entity findTarget(MinecraftClient client) {
            if (client.player == null || client.world == null) return null;

            HitBoxesModule hb = VisualModClient.INSTANCE.getModuleManager().getModule(HitBoxesModule.class);
            float expand = (hb != null && hb.isEnabled()) ? hb.getExpansion() : 0.0f;

            Vec3d cameraPos = client.player.getCameraPosVec(1.0f);
            Vec3d rot = client.player.getRotationVec(1.0f);
            double reachDist = 3.6 + expand;
            Vec3d reach = cameraPos.add(rot.multiply(reachDist));

            Entity bestEntity = null;
            double closestDistance = Double.MAX_VALUE;

            for (Entity e : client.world.getEntities()) {
                if (e instanceof LivingEntity living && living != client.player && living.isAlive()) {
                    Box box = living.getBoundingBox().expand(expand);
                    var hitOpt = box.raycast(cameraPos, reach);
                    if (hitOpt.isPresent()) {
                        double d = cameraPos.squaredDistanceTo(hitOpt.get());
                        if (d < closestDistance) {
                            closestDistance = d;
                            bestEntity = living;
                        }
                    }
                }
            }

            if (bestEntity != null) return bestEntity;

            if (client.crosshairTarget instanceof EntityHitResult entityHit) {
                return entityHit.getEntity();
            }
            return null;
        }
    }

    public static class HitBoxesModule extends Module {
        public final SliderSetting expand = new SliderSetting("Расширение", 0.35, 0.05, 1.50, 0.05, "m");

        public HitBoxesModule() {
            super("HitBoxes", "Увеличивает объем хитбоксов целей для попадания", Category.COMBAT);
            registerSetting(expand);
        }

        public float getExpansion() {
            return isEnabled() ? expand.get().floatValue() : 0.0f;
        }
    }

    public static class TapeMouseModule extends Module {
        public final SliderSetting minCps = new SliderSetting("Мин. CPS", 10.0, 4.0, 20.0, 1.0, "");
        public final SliderSetting maxCps = new SliderSetting("Макс. CPS", 14.0, 6.0, 25.0, 1.0, "");

        private long lastClickTime = 0;
        private long currentDelayMs = 80;
        private final Random random = new Random();

        public TapeMouseModule() {
            super("TapeMouse", "Эмуляция зажатия мыши с реалистичным разбросом CPS", Category.COMBAT);
            registerSetting(minCps);
            registerSetting(maxCps);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.interactionManager == null) return;
            if (client.currentScreen != null) return;

            long window = client.getWindow().getHandle();
            boolean isAttackDown = client.options.attackKey.isPressed() || GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

            if (isAttackDown) {
                long now = System.currentTimeMillis();
                if (now - lastClickTime >= currentDelayMs) {
                    lastClickTime = now;

                    double min = Math.min(minCps.get(), maxCps.get());
                    double max = Math.max(minCps.get(), maxCps.get());
                    double targetCps = min + (max - min) * random.nextDouble();
                    currentDelayMs = (long) (1000.0 / Math.max(1.0, targetCps));

                    Entity target = TriggerBotModule.findTarget(client);
                    if (target != null) {
                        client.interactionManager.attackEntity(client.player, target);
                        if (target instanceof LivingEntity living) {
                            currentCombatTarget = living;
                        }
                    }
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        }
    }

    public static class VelocityModule extends Module {
        public final SliderSetting horizontal = new SliderSetting("По горизонтали", 0.0, 0.0, 1.0, 0.05, "%");
        public final SliderSetting vertical = new SliderSetting("По вертикали", 0.0, 0.0, 1.0, 0.05, "%");

        public VelocityModule() {
            super("Velocity", "Снижает или полностью убирает отдачу от ударов и стрел", Category.COMBAT);
            registerSetting(horizontal);
            registerSetting(vertical);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.hurtTime > 0) {
                double h = horizontal.get();
                double v = vertical.get();
                Vec3d vel = client.player.getVelocity();
                client.player.setVelocity(vel.x * h, vel.y * v, vel.z * h);
            }
        }
    }

    public static class TargetHudModule extends Module {
        public final SliderSetting xPos = new SliderSetting("Позиция X", 0.52, 0.05, 0.90, 0.01, "");
        public final SliderSetting yPos = new SliderSetting("Позиция Y", 0.60, 0.05, 0.90, 0.01, "");
        public final SliderSetting maxDistance = new SliderSetting("Макс. дистанция", 24.0, 6.0, 64.0, 2.0, "m");

        private float currentDisplayHp = 20.0f;
        private float secondaryHp = 20.0f;
        private float absorptionDisplay = 0.0f;
        private float alphaAnim = 0.0f;
        private float lastHurtTime = 0.0f;

        private final CopyOnWriteArrayList<Particle> particles = new CopyOnWriteArrayList<>();

        public TargetHudModule() {
            super("TargetHUD", "Информативная плашка цели с плавной полоской HP и ограничением дистанции", Category.RENDER);
            registerSetting(xPos);
            registerSetting(yPos);
            registerSetting(maxDistance);
        }

        public void render(DrawContext context, MinecraftClient mc) {
            LivingEntity target = currentCombatTarget;
            boolean preview = (target == null && (mc.currentScreen instanceof ModernRefinedClickGui || mc.currentScreen instanceof CosmeticsScreen) && mc.player != null);
            if (preview) target = mc.player;

            double maxDist = maxDistance.get();
            if (!preview && target != null && mc.player != null) {
                double dSq = mc.player.squaredDistanceTo(target);
                if (dSq > maxDist * maxDist) {
                    target = null;
                    currentCombatTarget = null;
                }
            }

            boolean visible = target != null && target.isAlive();
            alphaAnim = MathHelper.lerp(0.18f, alphaAnim, visible ? 1.0f : 0.0f);
            if (alphaAnim < 0.02f) return;
            if (target == null) return;

            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();
            int x = (int) (sw * xPos.get());
            int y = (int) (sh * yPos.get());

            int w = 132;
            int h = 42;

            float realHp = target.getHealth();
            float maxHp = Math.max(1.0f, target.getMaxHealth());
            float realAbs = target.getAbsorptionAmount();

            currentDisplayHp = MathHelper.lerp(0.16f, currentDisplayHp, realHp);
            secondaryHp = MathHelper.lerp(0.08f, secondaryHp, realHp);
            absorptionDisplay = MathHelper.lerp(0.16f, absorptionDisplay, realAbs);

            if (target.hurtTime > 0 && target.hurtTime > lastHurtTime) {
                for (int i = 0; i < 5; i++) {
                    particles.add(new Particle(x + 19, y + 20));
                }
            }
            lastHurtTime = target.hurtTime;

            int alpha = (int) (alphaAnim * 255);
            int bg = (Math.min(alpha, 0xD0) << 24) | 0x12111A;
            int border = (Math.min(alpha, 0x60) << 24) | 0x818CF8;

            ModernRefinedClickGui.drawSmoothRect(context, x, y, w, h, bg, border);

            int headSize = 30;
            int headX = x + 6;
            int headY = y + 6;

            boolean renderedSkin = false;
            if (target instanceof AbstractClientPlayerEntity player) {
                try {
                    Identifier skin = player.getSkinTextures().texture();
                    if (skin != null) {
                        context.drawTexture(skin, headX, headY, headSize, headSize, 8.0f, 8.0f, 8, 8, 64, 64);
                        context.drawTexture(skin, headX, headY, headSize, headSize, 40.0f, 8.0f, 8, 8, 64, 64);
                        renderedSkin = true;
                    }
                } catch (Throwable ignored) {}
            }

            if (!renderedSkin) {
                ModernRefinedClickGui.drawSmoothRect(context, headX, headY, headSize, headSize, 0x55252233, 0x33818CF8);
                String letter = target.getName().getString().isEmpty() ? "?" : target.getName().getString().substring(0, 1).toUpperCase();
                drawStyledText(context, mc.textRenderer, letter, headX + 11, headY + 10, 0xFFFFFFFF);
            }

            String name = target.getName().getString();
            if (mc.textRenderer.getWidth(name) > 65) {
                name = name.substring(0, Math.min(name.length(), 9)) + "..";
            }
            drawStyledText(context, mc.textRenderer, "§f" + name, x + 41, y + 6, 0xFFFFFFFF);

            double dist = mc.player != null ? Math.sqrt(mc.player.squaredDistanceTo(target)) : 0.0;
            String distStr = String.format("§8[§d%.1fm§8]", dist);
            int distW = mc.textRenderer.getWidth(distStr);
            drawStyledText(context, mc.textRenderer, distStr, x + w - distW - 6, y + 6, 0xFFE2E8F0);

            int hpInt = (int) Math.ceil(currentDisplayHp);
            int pctInt = (int) Math.round((currentDisplayHp / maxHp) * 100.0f);
            String hpText = "§c❤ " + hpInt + "§7/§f" + (int) maxHp + " §8(§a" + pctInt + "%§8)";
            drawStyledText(context, mc.textRenderer, hpText, x + 41, y + 17, 0xFFCBD5E1);

            int barX = x + 41;
            int barY = y + 29;
            int barW = 84;
            int barH = 5;

            context.fill(barX, barY, barX + barW, barY + barH, 0xFF1D1B28);

            float secPct = MathHelper.clamp(secondaryHp / maxHp, 0.0f, 1.0f);
            context.fill(barX, barY, barX + (int) (barW * secPct), barY + barH, 0xFFEF4444);

            float hpPct = MathHelper.clamp(currentDisplayHp / maxHp, 0.0f, 1.0f);
            context.fill(barX, barY, barX + (int) (barW * hpPct), barY + barH, 0xFF6366F1);

            if (absorptionDisplay > 0.1f) {
                float absPct = MathHelper.clamp(absorptionDisplay / maxHp, 0.0f, 1.0f);
                context.fill(barX, barY, barX + (int) (barW * absPct), barY + barH, 0xFFFACC15);
            }

            particles.removeIf(p -> System.currentTimeMillis() - p.startTime > p.lifetime);
            for (Particle p : particles) {
                p.update();
                float prog = 1.0f - (float) (System.currentTimeMillis() - p.startTime) / (float) p.lifetime;
                int pColor = (Math.max(10, (int) (prog * 255)) << 24) | 0x818CF8;
                context.fill((int) p.x - 1, (int) p.y - 1, (int) p.x + 2, (int) p.y + 2, pColor);
            }
        }

        public static class Particle {
            public float x, y, vx, vy;
            public final long startTime;
            public final long lifetime;

            public Particle(float originX, float originY) {
                this.x = originX;
                this.y = originY;
                this.vx = ThreadLocalRandom.current().nextFloat(-2.0f, 2.0f);
                this.vy = ThreadLocalRandom.current().nextFloat(-2.0f, 2.0f);
                this.startTime = System.currentTimeMillis();
                this.lifetime = 650L + ThreadLocalRandom.current().nextLong(350L);
            }

            public void update() {
                x += vx;
                y += vy;
            }
        }
    }

    public static class CosmeticsModule extends Module {
        public final ModeSetting wings = new ModeSetting("Крылья", "Angel", List.of("Off", "Angel", "Dragon", "Demon", "Cyber"));
        public final SliderSetting wingScale = new SliderSetting("Размах крыльев", 1.0, 0.6, 1.6, 0.05, "x");
        public final ModeSetting cape = new ModeSetting("Плащ", "Pulse", List.of("Off", "Pulse", "Cosmo", "Fire", "Wave"));
        public final ModeSetting headItem = new ModeSetting("Голова", "Halo", List.of("Off", "Halo", "ChinaHat", "Horns", "Crown"));
        public final BooleanSetting katana = new BooleanSetting("Катана на спине", true);

        public CosmeticsModule() {
            super("Cosmetics", "Клиентские 3D аксессуары: крылья, нимб, плащ, катана и рога", Category.RENDER);
            registerSetting(wings);
            registerSetting(wingScale);
            registerSetting(cape);
            registerSetting(headItem);
            registerSetting(katana);
        }
    }

    public static class CosmeticsRenderer {
        public static void renderCosmetics(AbstractClientPlayerEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
            if (VisualModClient.INSTANCE == null) return;
            CosmeticsModule module = VisualModClient.INSTANCE.getModuleManager().getModule(CosmeticsModule.class);
            if (module == null || !module.isEnabled()) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            if (entity == mc.player && mc.options.getPerspective().isFirstPerson() && !(mc.currentScreen instanceof CosmeticsScreen)) return;

            float age = entity.age + tickDelta;
            VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getLightning());
            MatrixStack.Entry entry = matrices.peek();

            String head = module.headItem.get();
            if (!head.equals("Off")) {
                matrices.push();
                matrices.translate(0.0, entity.getHeight() + 0.12, 0.0);

                if (head.equals("Halo")) {
                    float bob = (float) Math.sin(age * 0.08f) * 0.04f;
                    matrices.translate(0.0, 0.18 + bob, 0.0);
                    matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(age * 2.0f));

                    float rad = 0.32f;
                    int segs = 20;
                    for (int i = 0; i < segs; i++) {
                        double a1 = (i * Math.PI * 2) / segs;
                        double a2 = ((i + 1) * Math.PI * 2) / segs;
                        float x1 = (float) (Math.cos(a1) * rad);
                        float z1 = (float) (Math.sin(a1) * rad);
                        float x2 = (float) (Math.cos(a2) * rad);
                        float z2 = (float) (Math.sin(a2) * rad);

                        buffer.vertex(entry, x1, 0.0f, z1).color(255, 215, 0, 220);
                        buffer.vertex(entry, x2, 0.0f, z2).color(255, 215, 0, 220);
                        buffer.vertex(entry, x2 * 0.88f, 0.02f, z2 * 0.88f).color(255, 245, 140, 240);
                        buffer.vertex(entry, x1 * 0.88f, 0.02f, z1 * 0.88f).color(255, 245, 140, 240);
                    }
                } else if (head.equals("ChinaHat")) {
                    float r = 0.65f;
                    float h = 0.28f;
                    int segs = 24;
                    for (int i = 0; i < segs; i++) {
                        double a1 = (i * Math.PI * 2) / segs;
                        double a2 = ((i + 1) * Math.PI * 2) / segs;
                        float x1 = (float) (Math.cos(a1) * r);
                        float z1 = (float) (Math.sin(a1) * r);
                        float x2 = (float) (Math.cos(a2) * r);
                        float z2 = (float) (Math.sin(a2) * r);

                        buffer.vertex(entry, 0.0f, h, 0.0f).color(129, 140, 248, 255);
                        buffer.vertex(entry, x1, 0.0f, z1).color(99, 102, 241, 190);
                        buffer.vertex(entry, x2, 0.0f, z2).color(99, 102, 241, 190);
                        buffer.vertex(entry, 0.0f, h, 0.0f).color(129, 140, 248, 255);
                    }
                } else if (head.equals("Horns")) {
                    matrices.translate(0.0, -0.05, 0.0);
                    renderHorn(matrices, buffer, entry, 0.18f, 1);
                    renderHorn(matrices, buffer, entry, -0.18f, -1);
                } else if (head.equals("Crown")) {
                    float r = 0.28f;
                    float h = 0.12f;
                    int segs = 6;
                    for (int i = 0; i < segs; i++) {
                        double a1 = (i * Math.PI * 2) / segs;
                        double a2 = ((i + 1) * Math.PI * 2) / segs;
                        float x1 = (float) (Math.cos(a1) * r);
                        float z1 = (float) (Math.sin(a1) * r);
                        float x2 = (float) (Math.cos(a2) * r);
                        float z2 = (float) (Math.sin(a2) * r);
                        float midX = (float) (Math.cos((a1 + a2) / 2) * r * 1.05);
                        float midZ = (float) (Math.sin((a1 + a2) / 2) * r * 1.05);

                        buffer.vertex(entry, x1, 0.0f, z1).color(255, 215, 0, 255);
                        buffer.vertex(entry, x2, 0.0f, z2).color(255, 215, 0, 255);
                        buffer.vertex(entry, midX, h, midZ).color(255, 235, 59, 255);
                        buffer.vertex(entry, x1, 0.0f, z1).color(255, 215, 0, 255);
                    }
                }
                matrices.pop();
            }

            String wingMode = module.wings.get();
            if (!wingMode.equals("Off")) {
                float sc = module.wingScale.get().floatValue();
                matrices.push();
                matrices.translate(0.0, entity.getHeight() * 0.65, 0.12);

                float flap = (float) Math.sin(age * 0.22f) * 26.0f;
                if (entity.isSprinting() || !entity.isOnGround()) flap *= 1.4f;

                int[] col = switch (wingMode) {
                    case "Dragon" -> new int[]{220, 38, 38, 120};
                    case "Demon" -> new int[]{147, 51, 234, 180};
                    case "Cyber" -> new int[]{6, 182, 212, 240};
                    default -> new int[]{255, 255, 255, 210};
                };

                renderWing(matrices, buffer, sc, flap, 1, col);
                renderWing(matrices, buffer, sc, -flap, -1, col);

                matrices.pop();
            }

            if (module.katana.get()) {
                matrices.push();
                matrices.translate(0.08, entity.getHeight() * 0.58, 0.16);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45.0f));
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(15.0f));

                float kw = 0.03f;
                float kh = 0.95f;
                buffer.vertex(entry, -kw, 0.0f, 0.0f).color(30, 27, 46, 255);
                buffer.vertex(entry, kw, 0.0f, 0.0f).color(30, 27, 46, 255);
                buffer.vertex(entry, kw, kh, 0.0f).color(199, 210, 254, 255);
                buffer.vertex(entry, -kw, kh, 0.0f).color(199, 210, 254, 255);

                float gw = 0.08f;
                buffer.vertex(entry, -gw, kh * 0.68f, -0.02f).color(234, 179, 8, 255);
                buffer.vertex(entry, gw, kh * 0.68f, -0.02f).color(234, 179, 8, 255);
                buffer.vertex(entry, gw, kh * 0.72f, 0.02f).color(234, 179, 8, 255);
                buffer.vertex(entry, -gw, kh * 0.72f, 0.02f).color(234, 179, 8, 255);

                matrices.pop();
            }

            String capeMode = module.cape.get();
            if (!capeMode.equals("Off")) {
                matrices.push();
                matrices.translate(0.0, entity.getHeight() * 0.68, 0.13);

                float walkTilt = entity.isSprinting() ? 42.0f : (entity.forwardSpeed != 0.0f ? 20.0f : 8.0f);
                float wave = (float) Math.sin(age * 0.15f) * 4.0f;
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(walkTilt + wave));

                int cTop = switch (capeMode) {
                    case "Fire" -> 0xFFEF4444;
                    case "Cosmo" -> 0xFF06B6D4;
                    case "Wave" -> 0xFF3B82F6;
                    default -> 0xFF6366F1;
                };
                int cBottom = 0xFF110E1B;

                float cw = 0.28f;
                float cl = 0.85f;

                int r1 = (cTop >> 16) & 0xFF, g1 = (cTop >> 8) & 0xFF, b1 = cTop & 0xFF;
                int r2 = (cBottom >> 16) & 0xFF, g2 = (cBottom >> 8) & 0xFF, b2 = cBottom & 0xFF;

                buffer.vertex(entry, -cw, 0.0f, 0.0f).color(r1, g1, b1, 230);
                buffer.vertex(entry, cw, 0.0f, 0.0f).color(r1, g1, b1, 230);
                buffer.vertex(entry, cw, -cl, 0.04f).color(r2, g2, b2, 230);
                buffer.vertex(entry, -cw, -cl, 0.04f).color(r2, g2, b2, 230);

                matrices.pop();
            }
        }

        private static void renderWing(MatrixStack matrices, VertexConsumer buffer, float sc, float flap, int side, int[] c) {
            matrices.push();
            matrices.translate(side * 0.14f, 0.0, 0.0);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * (45.0f + flap)));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * 15.0f));
            MatrixStack.Entry entry = matrices.peek();

            float w = 0.95f * sc;
            float h = 0.70f * sc;

            buffer.vertex(entry, 0.0f, 0.0f, 0.0f).color(c[0], c[1], c[2], c[3]);
            buffer.vertex(entry, side * w * 0.5f, h * 0.8f, 0.04f).color(c[0], c[1], c[2], c[3]);
            buffer.vertex(entry, side * w, h * 0.4f, 0.0f).color(c[0], c[1], c[2], c[3] - 30);
            buffer.vertex(entry, side * w * 0.4f, -h * 0.3f, -0.02f).color(c[0], c[1], c[2], c[3] - 20);

            matrices.pop();
        }

        private static void renderHorn(MatrixStack matrices, VertexConsumer buffer, MatrixStack.Entry entry, float sideX, int sign) {
            float hx = sideX;
            float hy = 0.0f;
            float hz = -0.1f;
            buffer.vertex(entry, hx, hy, hz).color(220, 38, 38, 255);
            buffer.vertex(entry, hx + sign * 0.08f, hy + 0.18f, hz + 0.06f).color(185, 28, 28, 255);
            buffer.vertex(entry, hx + sign * 0.12f, hy + 0.28f, hz + 0.14f).color(239, 68, 68, 255);
            buffer.vertex(entry, hx, hy, hz).color(220, 38, 38, 255);
        }
    }

    public static class AspectRatioModule extends Module {
        public final ModeSetting presets = new ModeSetting("Соотношение", "4:3", List.of("16:9", "16:10", "4:3", "5:4", "1:1", "21:9", "Custom"));
        public final SliderSetting customRatio = new SliderSetting("Кастомный", 1.33, 0.40, 2.50, 0.05, "");

        public AspectRatioModule() {
            super("AspectRatio", "Реальное растяжение проекционной матрицы экрана камеры", Category.RENDER);
            registerSetting(presets);
            registerSetting(customRatio);
        }

        public float getRatio() {
            return switch (presets.get()) {
                case "16:9" -> 16.0f / 9.0f;
                case "16:10" -> 16.0f / 10.0f;
                case "4:3" -> 4.0f / 3.0f;
                case "5:4" -> 5.0f / 4.0f;
                case "1:1" -> 1.0f;
                case "21:9" -> 21.0f / 9.0f;
                default -> customRatio.get().floatValue();
            };
        }
    }

    public static class AmbienceModule extends Module {
        public final ModeSetting timeMode = new ModeSetting("Время", "Sunset", List.of("Day", "Noon", "Sunset", "Night", "Midnight", "Cycle", "Custom"));
        public final SliderSetting customTime = new SliderSetting("Кастомное время", 13000, 0, 24000, 500, "t");
        public final SliderSetting cycleSpeed = new SliderSetting("Скорость цикла", 30, 5, 200, 5, "x");
        public final BooleanSetting clearWeather = new BooleanSetting("Ясная погода", true);
        private long cycleTicks = 0;

        public AmbienceModule() {
            super("Ambience", "Кастомное визуальное время суток, чистое небо и атмосфера", Category.RENDER);
            registerSetting(timeMode);
            registerSetting(customTime);
            registerSetting(cycleSpeed);
            registerSetting(clearWeather);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.world == null) return;

            long targetTime = switch (timeMode.get()) {
                case "Day" -> 1000L;
                case "Noon" -> 6000L;
                case "Sunset" -> 12800L;
                case "Night" -> 18000L;
                case "Midnight" -> 22000L;
                case "Cycle" -> {
                    cycleTicks = (cycleTicks + cycleSpeed.get().longValue()) % 24000;
                    yield cycleTicks;
                }
                default -> customTime.get().longValue();
            };

            safeSetWorldTime(client.world, targetTime);

            if (clearWeather.get()) {
                try {
                    client.world.setRainGradient(0.0f);
                    client.world.setThunderGradient(0.0f);
                } catch (Throwable ignored) {}
            }
        }

        private void safeSetWorldTime(Object world, long time) {
            try {
                for (Method m : world.getClass().getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == long.class && m.getName().toLowerCase().contains("time")) {
                        m.setAccessible(true);
                        m.invoke(world, time);
                        return;
                    }
                }
                Method getProps = world.getClass().getMethod("getLevelProperties");
                Object props = getProps.invoke(world);
                if (props != null) {
                    for (Method pm : props.getClass().getMethods()) {
                        if (pm.getParameterCount() == 1 && pm.getParameterTypes()[0] == long.class && pm.getName().toLowerCase().contains("time")) {
                            pm.setAccessible(true);
                            pm.invoke(props, time);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class ChinaHatModule extends Module {
        public final ModeSetting colorMode = new ModeSetting("Цвет", "Indigo", List.of("Indigo", "Cyan", "Red", "Gold"));
        public final SliderSetting radius = new SliderSetting("Радиус", 0.65, 0.3, 1.2, 0.05, "m");
        public final SliderSetting height = new SliderSetting("Высота", 0.28, 0.1, 0.6, 0.02, "m");

        public ChinaHatModule() {
            super("ChinaHat", "Азиатская коническая шляпа над головой игрока", Category.RENDER);
            registerSetting(colorMode);
            registerSetting(radius);
            registerSetting(height);
        }
    }

    public static class CrosshairModule extends Module {
        public final ModeSetting style = new ModeSetting("Тип", "Classic", List.of("Classic", "Dot", "T-Cross"));
        public final SliderSetting size = new SliderSetting("Размер", 5.0, 2.0, 15.0, 1.0, "px");
        public final SliderSetting gap = new SliderSetting("Зазор", 3.0, 0.0, 10.0, 1.0, "px");
        public final SliderSetting thickness = new SliderSetting("Толщина", 1.5, 1.0, 4.0, 0.5, "px");
        public final BooleanSetting dotInCenter = new BooleanSetting("Точка в центре", false);

        public CrosshairModule() {
            super("Crosshair", "Кастомный статический прицел вместо ванильного", Category.RENDER);
            registerSetting(style);
            registerSetting(size);
            registerSetting(gap);
            registerSetting(thickness);
            registerSetting(dotInCenter);
        }

        public void renderCrosshair(DrawContext context, int screenWidth, int screenHeight) {
            int cx = screenWidth / 2;
            int cy = screenHeight / 2;
            int s = size.get().intValue();
            int g = gap.get().intValue();
            int t = (int) Math.max(1, thickness.get());
            int halfT = t / 2;

            int color = 0xFFFFFFFF;
            int border = 0xAA000000;

            String st = style.get();
            if (st.equals("Dot")) {
                context.fill(cx - t - 1, cy - t - 1, cx + t + 1, cy + t + 1, border);
                context.fill(cx - t, cy - t, cx + t, cy + t, color);
                return;
            }

            context.fill(cx - halfT, cy - g - s, cx - halfT + t, cy - g, color);
            context.fill(cx - halfT, cy + g, cx - halfT + t, cy + g + s, color);
            context.fill(cx - g - s, cy - halfT, cx - g, cy - halfT + t, color);
            if (!st.equals("T-Cross")) {
                context.fill(cx + g, cy - halfT, cx + g + s, cy - halfT + t, color);
            }

            if (dotInCenter.get()) {
                context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF00FFCC);
            }
        }
    }

    public static class ZoomModule extends Module {
        public final SliderSetting zoomFactor = new SliderSetting("Кратность", 3.5, 1.5, 8.0, 0.5, "x");
        private static double currentZoom = 1.0;

        public ZoomModule() {
            super("Zoom", "Кинематографическое приближение взгляда без искажений", Category.RENDER);
            registerSetting(zoomFactor);
        }

        public static double getZoomLevel() { return currentZoom; }

        @Override
        public void onTick(MinecraftClient client) {
            double target = isEnabled() ? zoomFactor.get() : 1.0;
            currentZoom = MathHelper.lerp(0.25, currentZoom, target);
        }

        @Override
        public void onDisable() { currentZoom = 1.0; }
    }

    public static class FullBrightModule extends Module {
        public FullBrightModule() {
            super("FullBright", "Максимальная видимость в пещерах и темных локациях", Category.RENDER);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            try {
                client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 220, 0, false, false, false));
            } catch (Throwable ignored) {}
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                try {
                    mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static class LowFireModule extends Module {
        public final SliderSetting height = new SliderSetting("Высота", 0.30, 0.0, 1.0, 0.05, "%");

        public LowFireModule() {
            super("LowFire", "Опускает или полностью отключает огонь от 1-го лица", Category.REMOVALS);
            registerSetting(height);
        }
    }

    public static class LowShieldModule extends Module {
        public final SliderSetting offsetY = new SliderSetting("Опустить Y", 0.35, 0.0, 0.90, 0.05, "");
        public final SliderSetting scale = new SliderSetting("Масштаб", 0.70, 0.20, 1.0, 0.05, "x");

        public LowShieldModule() {
            super("LowShield", "Уменьшает и опускает щит во второй руке", Category.REMOVALS);
            registerSetting(offsetY);
            registerSetting(scale);
        }
    }

    public static class NoHurtCamModule extends Module {
        public NoHurtCamModule() {
            super("NoHurtCam", "Отключает дезориентирующую тряску экрана при получении ударов", Category.REMOVALS);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            try {
                client.options.getDamageTiltStrength().setValue(0.0);
            } catch (Throwable ignored) {}
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) {
                try {
                    mc.options.getDamageTiltStrength().setValue(1.0);
                } catch (Throwable ignored) {}
            }
        }
    }

    public static class AntiBlindnessModule extends Module {
        public final BooleanSetting blindness = new BooleanSetting("Слепота зелья", true);
        public final BooleanSetting darkness = new BooleanSetting("Тьма Вардена", true);
        public final BooleanSetting nausea = new BooleanSetting("Искажение портала", true);

        public AntiBlindnessModule() {
            super("AntiBlindness", "Очищает экран от эффектов слепоты, тьмы и тошноты", Category.REMOVALS);
            registerSetting(blindness);
            registerSetting(darkness);
            registerSetting(nausea);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (blindness.get() && client.player.hasStatusEffect(StatusEffects.BLINDNESS)) client.player.removeStatusEffect(StatusEffects.BLINDNESS);
            if (darkness.get() && client.player.hasStatusEffect(StatusEffects.DARKNESS)) client.player.removeStatusEffect(StatusEffects.DARKNESS);
            if (nausea.get() && client.player.hasStatusEffect(StatusEffects.NAUSEA)) client.player.removeStatusEffect(StatusEffects.NAUSEA);
        }
    }

    public static class NoRenderModule extends Module {
        public final BooleanSetting explosions = new BooleanSetting("Частицы взрывов", true);
        public final BooleanSetting totemAnimation = new BooleanSetting("Анимация тотема", true);

        public NoRenderModule() {
            super("NoRender", "Блокирует спам лагающих частиц взрывов и анимацию тотема", Category.REMOVALS);
            registerSetting(explosions);
            registerSetting(totemAnimation);
        }
    }

    public static class AutoSprintModule extends Module {
        public AutoSprintModule() {
            super("AutoSprint", "Автоматический непрерывный бег без двойного W", Category.MOVEMENT);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.getHungerManager().getFoodLevel() <= 6) return;
            if (client.player.forwardSpeed > 0 && !client.player.isSneaking() && !client.player.horizontalCollision) {
                client.player.setSprinting(true);
            }
        }
    }

    public static class FastBreakModule extends Module {
        public final SliderSetting speedMultiplier = new SliderSetting("Скорость", 1.5, 1.0, 3.0, 0.1, "x");

        public FastBreakModule() {
            super("FastBreak", "Увеличивает скорость копания и сбрасывает кулдаун удара по блоку", Category.MOVEMENT);
            registerSetting(speedMultiplier);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.interactionManager == null) return;
            try {
                for (Field f : ClientPlayerInteractionManager.class.getDeclaredFields()) {
                    if (f.getType() == int.class && (f.getName().equals("blockBreakingCooldown") || f.getName().equals("field_3716"))) {
                        f.setAccessible(true);
                        f.setInt(client.interactionManager, 0);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class InventoryMoveModule extends Module {
        public InventoryMoveModule() {
            super("InventoryMove", "Свободное перемещение и прыжки при открытом инвентаре", Category.MOVEMENT);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.currentScreen == null || client.player == null) return;
            if (client.currentScreen instanceof ChatScreen) return;

            long window = client.getWindow().getHandle();
            client.options.forwardKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS);
            client.options.backKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS);
            client.options.leftKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS);
            client.options.rightKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS);
            client.options.jumpKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS);
            client.options.sprintKey.setPressed(GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS);
        }
    }

    public static class WaterSpeedModule extends Module {
        public final SliderSetting speed = new SliderSetting("Ускорение", 1.15, 1.05, 1.5, 0.05, "x");

        public WaterSpeedModule() {
            super("WaterSpeed", "Увеличивает скорость плавания в воде", Category.MOVEMENT);
            registerSetting(speed);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.isTouchingWater()) {
                double s = speed.get();
                client.player.setVelocity(client.player.getVelocity().multiply(s, 1.0, s));
            }
        }
    }

    public static class FastPlaceModule extends Module {
        public final SliderSetting delay = new SliderSetting("Задержка ПКМ", 0.0, 0.0, 3.0, 1.0, "t");

        public FastPlaceModule() {
            super("FastPlace", "Убирает задержку между установкой блоков", Category.MISC);
            registerSetting(delay);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled()) return;
            try {
                for (Field f : MinecraftClient.class.getDeclaredFields()) {
                    if (f.getType() == int.class && (f.getName().equals("itemUseCooldown") || f.getName().equals("field_1752"))) {
                        f.setAccessible(true);
                        f.setInt(client, delay.get().intValue());
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class AutoToolModule extends Module {
        public AutoToolModule() {
            super("AutoTool", "Автоматически выбирает эффективный инструмент из хотбара", Category.MISC);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.world == null) return;

            long window = client.getWindow().getHandle();
            boolean isLeftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;

            if (isLeftDown && client.crosshairTarget instanceof BlockHitResult bHit) {
                BlockPos pos = bHit.getBlockPos();
                BlockState state = client.world.getBlockState(pos);
                if (state.isAir()) return;

                int bestSlot = -1;
                float bestSpeed = 1.0f;

                for (int i = 0; i < 9; i++) {
                    ItemStack stack = client.player.getInventory().getStack(i);
                    if (stack.isEmpty()) continue;
                    float speed = stack.getMiningSpeedMultiplier(state);
                    if (speed > bestSpeed) {
                        bestSpeed = speed;
                        bestSlot = i;
                    }
                }

                if (bestSlot != -1 && client.player.getInventory().selectedSlot != bestSlot) {
                    client.player.getInventory().selectedSlot = bestSlot;
                }
            }
        }
    }

    public static class FreeCameraModule extends Module {
        public final SliderSetting speed = new SliderSetting("Скорость полета", 1.2, 0.5, 4.0, 0.1, "x");
        private double origX, origY, origZ;
        private float origYaw, origPitch;
        private boolean active = false;

        public FreeCameraModule() {
            super("FreeCamera", "Свободный полет камерой без перемещения хитбокса на сервере", Category.MISC);
            registerSetting(speed);
        }

        @Override
        public void onEnable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                origX = mc.player.getX();
                origY = mc.player.getY();
                origZ = mc.player.getZ();
                origYaw = mc.player.getYaw();
                origPitch = mc.player.getPitch();
                mc.player.noClip = true;
                active = true;
            }
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || !active) return;
            client.player.noClip = true;
            client.player.setVelocity(0, 0, 0);

            double sp = speed.get() * 0.4;
            double rad = Math.toRadians(client.player.getYaw());
            double dx = 0, dy = 0, dz = 0;

            if (client.options.forwardKey.isPressed()) {
                dx -= Math.sin(rad) * sp;
                dz += Math.cos(rad) * sp;
            }
            if (client.options.backKey.isPressed()) {
                dx += Math.sin(rad) * sp;
                dz -= Math.cos(rad) * sp;
            }
            if (client.options.leftKey.isPressed()) {
                dx += Math.cos(rad) * sp;
                dz += Math.sin(rad) * sp;
            }
            if (client.options.rightKey.isPressed()) {
                dx -= Math.cos(rad) * sp;
                dz -= Math.sin(rad) * sp;
            }
            if (client.options.jumpKey.isPressed()) dy += sp;
            if (client.options.sneakKey.isPressed()) dy -= sp;

            client.player.setPosition(client.player.getX() + dx, client.player.getY() + dy, client.player.getZ() + dz);
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && active) {
                mc.player.setPosition(origX, origY, origZ);
                mc.player.setYaw(origYaw);
                mc.player.setPitch(origPitch);
                mc.player.noClip = false;
                mc.player.setVelocity(0, 0, 0);
                active = false;
            }
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void init() {
            // Combat
            modules.add(new TriggerBotModule());
            modules.add(new HitBoxesModule());
            modules.add(new VelocityModule());
            modules.add(new TapeMouseModule());

            // Movement
            modules.add(new AutoSprintModule());
            modules.add(new FastBreakModule());
            modules.add(new InventoryMoveModule());
            modules.add(new WaterSpeedModule());

            // Render
            modules.add(new TargetHudModule());
            modules.add(new CosmeticsModule());
            modules.add(new AspectRatioModule());
            modules.add(new AmbienceModule());
            modules.add(new FullBrightModule());
            modules.add(new ChinaHatModule());
            modules.add(new ZoomModule());
            modules.add(new CrosshairModule());

            // Removals
            modules.add(new NoHurtCamModule());
            modules.add(new LowFireModule());
            modules.add(new AntiBlindnessModule());
            modules.add(new LowShieldModule());
            modules.add(new NoRenderModule());

            // Misc
            modules.add(new FastPlaceModule());
            modules.add(new AutoToolModule());
            modules.add(new FreeCameraModule());
        }

        public void onTick(MinecraftClient client) {
            for (Module m : modules) {
                if (m.isEnabled()) {
                    try {
                        m.onTick(client);
                    } catch (Throwable ignored) {}
                }
            }
        }

        public List<Module> getAllModules() { return modules; }

        public <T extends Module> T getModule(Class<T> clazz) {
            for (Module m : modules) {
                if (clazz.isInstance(m)) return clazz.cast(m);
            }
            return null;
        }

        public List<Module> getModulesByCategory(Category cat) {
            List<Module> list = new ArrayList<>();
            for (Module m : modules) {
                if (m.getCategory() == cat) list.add(m);
            }
            return list;
        }
    }

    public static class ModernRefinedClickGui extends Screen {
        private final ModuleManager moduleManager;
        private final List<GuiColumn> columns = new ArrayList<>();
        public static String hoveredDescription = "";

        private boolean wasLeftPressed = false;
        private boolean wasRightPressed = false;
        private boolean wasMiddlePressed = false;

        public ModernRefinedClickGui(ModuleManager moduleManager) {
            super(Text.literal("Refined ClickGUI"));
            this.moduleManager = moduleManager;
        }

        @Override
        protected void init() {
            columns.clear();
            Category[] categories = Category.values();

            int colWidth = 144;
            int gap = 12;
            int totalWidth = (categories.length * colWidth) + ((categories.length - 1) * gap);
            int startX = Math.max(8, (this.width - totalWidth) / 2);
            int startY = 38;
            int colHeight = this.height - startY - 18;

            for (int i = 0; i < categories.length; i++) {
                Category cat = categories[i];
                int x = startX + i * (colWidth + gap);
                columns.add(new GuiColumn(cat, moduleManager.getModulesByCategory(cat), x, startY, colWidth, colHeight));
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x66000000);

            hoveredDescription = "";
            handleMouseInput(mouseX, mouseY);

            for (GuiColumn col : columns) {
                col.render(context, mouseX, mouseY);
            }

            MinecraftClient mc = MinecraftClient.getInstance();

            // Top cosmetics button
            int cosBtnW = 100;
            int cosBtnH = 18;
            int cosBtnX = this.width - cosBtnW - 14;
            int cosBtnY = 10;
            boolean cosHover = mouseX >= cosBtnX && mouseX <= cosBtnX + cosBtnW && mouseY >= cosBtnY && mouseY <= cosBtnY + cosBtnH;
            drawSmoothRect(context, cosBtnX, cosBtnY, cosBtnW, cosBtnH, cosHover ? 0xDD4338CA : 0xDD312E81, 0xFF818CF8);
            drawStyledText(context, mc.textRenderer, "✦ Косметика", cosBtnX + 16, cosBtnY + 5, 0xFFFFFFFF);

            if (!hoveredDescription.isEmpty()) {
                int textW = mc.textRenderer.getWidth(hoveredDescription);
                int titleX = (this.width - textW) / 2;
                drawStyledText(context, mc.textRenderer, hoveredDescription, titleX, 15, 0xFFFFFFFF);
            }

            super.render(context, mouseX, mouseY, delta);
        }

        private void handleMouseInput(int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getWindow() == null || mc.getWindow().getHandle() == 0) return;
            long handle = mc.getWindow().getHandle();

            boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
            boolean rightDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_2) == GLFW.GLFW_PRESS;
            boolean middleDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_3) == GLFW.GLFW_PRESS;

            if (leftDown && !wasLeftPressed) dispatchClick(mouseX, mouseY, 0);
            if (rightDown && !wasRightPressed) dispatchClick(mouseX, mouseY, 1);
            if (middleDown && !wasMiddlePressed) dispatchClick(mouseX, mouseY, 2);

            if (leftDown) {
                for (GuiColumn col : columns) col.mouseDragged(mouseX, mouseY);
            } else {
                for (GuiColumn col : columns) col.mouseReleased(0);
            }

            if (!rightDown) {
                for (GuiColumn col : columns) col.mouseReleased(1);
            }

            wasLeftPressed = leftDown;
            wasRightPressed = rightDown;
            wasMiddlePressed = middleDown;
        }

        private void dispatchClick(int mouseX, int mouseY, int button) {
            int cosBtnW = 100;
            int cosBtnH = 18;
            int cosBtnX = this.width - cosBtnW - 14;
            int cosBtnY = 10;
            if (button == 0 && mouseX >= cosBtnX && mouseX <= cosBtnX + cosBtnW && mouseY >= cosBtnY && mouseY <= cosBtnY + cosBtnH) {
                playClickSound();
                MinecraftClient.getInstance().setScreen(new CosmeticsScreen(moduleManager));
                return;
            }

            for (GuiColumn col : columns) {
                if (col.isMouseOverColumn(mouseX, mouseY)) {
                    if (col.mouseClicked(mouseX, mouseY, button)) {
                        playClickSound();
                        return;
                    }
                }
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            for (Module m : moduleManager.getAllModules()) {
                if (m.isListeningForBind()) {
                    if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_DELETE) {
                        m.setKeyBind(GLFW.GLFW_KEY_UNKNOWN);
                    } else {
                        m.setKeyBind(keyCode);
                    }
                    m.setListeningForBind(false);
                    playClickSound();
                    return true;
                }
            }

            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_INSERT) {
                this.close();
                return true;
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            for (GuiColumn col : columns) {
                if (col.isMouseOverColumn((int) mouseX, (int) mouseY)) {
                    col.handleScroll(verticalAmount);
                    return true;
                }
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public boolean shouldPause() { return false; }

        public static void playClickSound() {
            playSoundSafely(SoundEvents.UI_BUTTON_CLICK, 1.0f);
        }

        public static void drawSmoothRect(DrawContext context, int x, int y, int w, int h, int bg, int border) {
            context.fill(x + 1, y, x + w - 1, y + h, bg);
            context.fill(x, y + 1, x + 1, y + h - 1, bg);
            context.fill(x + w - 1, y + 1, x + w, y + h - 1, bg);

            if (border != 0) {
                context.fill(x + 1, y, x + w - 1, y + 1, border);
                context.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
                context.fill(x, y + 1, x + 1, y + h - 1, border);
                context.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
            }
        }
    }

    public static class CosmeticsScreen extends Screen {
        private final ModuleManager moduleManager;
        private final CosmeticsModule cosmeticsModule;
        private float playerRotation = 0.0f;
        private boolean isDraggingPlayer = false;
        private double lastMouseX = 0;

        public CosmeticsScreen(ModuleManager moduleManager) {
            super(Text.literal("Cosmetics Studio"));
            this.moduleManager = moduleManager;
            this.cosmeticsModule = moduleManager.getModule(CosmeticsModule.class);
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x880A0A0F);

            MinecraftClient mc = MinecraftClient.getInstance();
            drawStyledText(context, mc.textRenderer, "✦ COSMETICS STUDIO", 26, 18, 0xFF818CF8);
            drawStyledText(context, mc.textRenderer, "§7Управляйте своими клиентскими 3D аксессуарами", 26, 30, 0xFF94A3B8);

            // Left Panel: 3D Player Viewport
            int viewX = 26;
            int viewY = 48;
            int viewW = this.width / 2 - 40;
            int viewH = this.height - 68;

            ModernRefinedClickGui.drawSmoothRect(context, viewX, viewY, viewW, viewH, 0xDD12111A, 0x44818CF8);

            if (mc.player != null) {
                int centerX = viewX + viewW / 2;
                int centerY = viewY + viewH - 24;
                int size = Math.min(viewH / 3, 75);

                float targetRotX = (float) centerX - playerRotation;
                float targetRotY = (float) (centerY - 55 - mouseY);
                drawEntityPreviewSafely(context, centerX, centerY, size, targetRotX, targetRotY, mc.player);
            }
            drawStyledText(context, mc.textRenderer, "§8⟳ Зажмите ЛКМ на модели для вращения", viewX + (viewW - 170) / 2, viewY + viewH - 12, 0xFF64748B);

            // Right Panel: Settings Cards
            int setX = this.width / 2;
            int setY = 48;
            int setW = this.width / 2 - 26;
            int setH = this.height - 68;

            ModernRefinedClickGui.drawSmoothRect(context, setX, setY, setW, setH, 0xDD12111A, 0x22FFFFFF);

            // Global Master Toggle
            boolean enabled = cosmeticsModule.isEnabled();
            int toggleBtnX = setX + 16;
            int toggleBtnY = setY + 14;
            int toggleBtnW = setW - 32;
            int toggleBtnH = 22;
            boolean hoverToggle = mouseX >= toggleBtnX && mouseX <= toggleBtnX + toggleBtnW && mouseY >= toggleBtnY && mouseY <= toggleBtnY + toggleBtnH;

            int toggleBg = enabled ? 0xDD4338CA : (hoverToggle ? 0xDD2A2A38 : 0xDD1E1E28);
            ModernRefinedClickGui.drawSmoothRect(context, toggleBtnX, toggleBtnY, toggleBtnW, toggleBtnH, toggleBg, enabled ? 0xFF818CF8 : 0x44FFFFFF);
            drawStyledText(context, mc.textRenderer, enabled ? "✔ Аксессуары: Включены" : "✖ Аксессуары: Отключены", toggleBtnX + 12, toggleBtnY + 7, 0xFFFFFFFF);

            // Setting items render
            int curY = toggleBtnY + 32;
            for (Setting<?> s : cosmeticsModule.getSettings()) {
                drawSettingControl(context, s, setX + 16, curY, setW - 32, mouseX, mouseY);
                curY += 28;
            }

            // Back to ClickGUI Button
            int backBtnW = 100;
            int backBtnH = 18;
            int backBtnX = this.width - backBtnW - 26;
            int backBtnY = 16;
            boolean backHover = mouseX >= backBtnX && mouseX <= backBtnX + backBtnW && mouseY >= backBtnY && mouseY <= backBtnY + backBtnH;
            ModernRefinedClickGui.drawSmoothRect(context, backBtnX, backBtnY, backBtnW, backBtnH, backHover ? 0xDD312E81 : 0xCC1A1A24, 0x44FFFFFF);
            drawStyledText(context, mc.textRenderer, "← В ClickGUI", backBtnX + 16, backBtnY + 5, 0xFFFFFFFF);

            super.render(context, mouseX, mouseY, delta);
        }

        private void drawEntityPreviewSafely(DrawContext context, int centerX, int centerY, int size, float mouseX, float mouseY, LivingEntity entity) {
            if (entity == null) return;
            try {
                for (Method m : InventoryScreen.class.getMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getName().equals("drawEntity")) {
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 10 && p[0] == DrawContext.class && LivingEntity.class.isAssignableFrom(p[9])) {
                            int x1 = centerX - size;
                            int y1 = centerY - size * 2;
                            int x2 = centerX + size;
                            int y2 = centerY;
                            m.invoke(null, context, x1, y1, x2, y2, size, 0.0625f, mouseX, mouseY, entity);
                            return;
                        } else if (p.length == 7 && p[0] == DrawContext.class && LivingEntity.class.isAssignableFrom(p[6])) {
                            m.invoke(null, context, centerX, centerY, size, -playerRotation, mouseY * 0.1f, entity);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            MinecraftClient mc = MinecraftClient.getInstance();
            drawStyledText(context, mc.textRenderer, "[3D Preview]", centerX - 30, centerY - size, 0xFF818CF8);
        }

        private void drawSettingControl(DrawContext context, Setting<?> s, int x, int y, int w, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawStyledText(context, mc.textRenderer, s.getName(), x, y + 2, 0xFFE2E8F0);

            if (s instanceof ModeSetting mode) {
                int btnW = 90;
                int btnH = 16;
                int btnX = x + w - btnW;
                int btnY = y;
                boolean hov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                ModernRefinedClickGui.drawSmoothRect(context, btnX, btnY, btnW, btnH, hov ? 0xEE2D2B3D : 0xEE1E1C2B, 0x44818CF8);
                String val = mode.get();
                int valW = mc.textRenderer.getWidth(val);
                drawStyledText(context, mc.textRenderer, val, btnX + (btnW - valW) / 2, btnY + 4, 0xFF818CF8);
            } else if (s instanceof BooleanSetting bool) {
                int btnW = 42;
                int btnH = 16;
                int btnX = x + w - btnW;
                int btnY = y;
                int bg = bool.get() ? 0xFF6366F1 : 0xFF2A2A3C;
                ModernRefinedClickGui.drawSmoothRect(context, btnX, btnY, btnW, btnH, bg, 0x44FFFFFF);
                String state = bool.get() ? "ВКЛ" : "ВЫКЛ";
                int sW = mc.textRenderer.getWidth(state);
                drawStyledText(context, mc.textRenderer, state, btnX + (btnW - sW) / 2, btnY + 4, 0xFFFFFFFF);
            } else if (s instanceof SliderSetting slider) {
                int barW = 100;
                int barH = 12;
                int barX = x + w - barW;
                int barY = y + 2;

                context.fill(barX, barY, barX + barW, barY + barH, 0xFF1D1B28);
                double pct = (slider.get() - slider.getMin()) / (slider.getMax() - slider.getMin());
                int fillW = (int) (barW * MathHelper.clamp(pct, 0.0, 1.0));
                context.fill(barX, barY, barX + fillW, barY + barH, 0xFF6366F1);

                String vStr = String.format("%.2f%s", slider.get(), slider.getSuffix());
                int vW = mc.textRenderer.getWidth(vStr);
                drawStyledText(context, mc.textRenderer, vStr, barX + (barW - vW) / 2, barY + 2, 0xFFFFFFFF);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int viewX = 26;
            int viewY = 48;
            int viewW = this.width / 2 - 40;
            int viewH = this.height - 68;

            if (mouseX >= viewX && mouseX <= viewX + viewW && mouseY >= viewY && mouseY <= viewY + viewH) {
                isDraggingPlayer = true;
                lastMouseX = mouseX;
                return true;
            }

            int backBtnW = 100;
            int backBtnH = 18;
            int backBtnX = this.width - backBtnW - 26;
            int backBtnY = 16;
            if (mouseX >= backBtnX && mouseX <= backBtnX + backBtnW && mouseY >= backBtnY && mouseY <= backBtnY + backBtnH) {
                ModernRefinedClickGui.playClickSound();
                MinecraftClient.getInstance().setScreen(new ModernRefinedClickGui(moduleManager));
                return true;
            }

            int setX = this.width / 2;
            int setY = 48;
            int setW = this.width / 2 - 26;

            int toggleBtnX = setX + 16;
            int toggleBtnY = setY + 14;
            int toggleBtnW = setW - 32;
            int toggleBtnH = 22;

            if (mouseX >= toggleBtnX && mouseX <= toggleBtnX + toggleBtnW && mouseY >= toggleBtnY && mouseY <= toggleBtnY + toggleBtnH) {
                cosmeticsModule.toggle();
                ModernRefinedClickGui.playClickSound();
                return true;
            }

            int curY = toggleBtnY + 32;
            for (Setting<?> s : cosmeticsModule.getSettings()) {
                int itemX = setX + 16;
                int itemW = setW - 32;
                if (s instanceof ModeSetting mode) {
                    int btnW = 90;
                    int btnH = 16;
                    int btnX = itemX + itemW - btnW;
                    if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        mode.cycle();
                        ModernRefinedClickGui.playClickSound();
                        return true;
                    }
                } else if (s instanceof BooleanSetting bool) {
                    int btnW = 42;
                    int btnH = 16;
                    int btnX = itemX + itemW - btnW;
                    if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        bool.toggle();
                        ModernRefinedClickGui.playClickSound();
                        return true;
                    }
                } else if (s instanceof SliderSetting slider) {
                    int barW = 100;
                    int barH = 12;
                    int barX = itemX + itemW - barW;
                    if (mouseX >= barX && mouseX <= barX + barW && mouseY >= curY && mouseY <= curY + barH) {
                        double pct = (mouseX - barX) / (double) barW;
                        double val = slider.getMin() + (slider.getMax() - slider.getMin()) * pct;
                        slider.setValueClamped(val);
                        return true;
                    }
                }
                curY += 28;
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            isDraggingPlayer = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (isDraggingPlayer) {
                playerRotation += (float) (mouseX - lastMouseX) * 1.4f;
                lastMouseX = mouseX;
                return true;
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                this.close();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    public static class GuiColumn {
        private final Category category;
        private final int x, y, width, height;
        private int scrollY = 0;
        private final List<GuiModuleCard> cards = new ArrayList<>();

        public GuiColumn(Category category, List<Module> modules, int x, int y, int width, int height) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;

            for (Module m : modules) {
                cards.add(new GuiModuleCard(m, x + 5, width - 10));
            }
        }

        public boolean isMouseOverColumn(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }

        public int getTotalContentHeight() {
            int h = 34;
            for (GuiModuleCard card : cards) {
                h += card.getTotalHeight() + 3;
            }
            return h + 10;
        }

        public void handleScroll(double amount) {
            int maxScroll = Math.max(0, getTotalContentHeight() - height);
            scrollY -= (int) (amount * 26);
            scrollY = MathHelper.clamp(scrollY, 0, maxScroll);
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();

            ModernRefinedClickGui.drawSmoothRect(context, x, y, width, height, 0xCC111116, 0x26FFFFFF);

            String headerText = category.getIcon() + "  " + category.getDisplayName();
            int headerW = mc.textRenderer.getWidth(headerText);
            int titleX = x + (width - headerW) / 2;
            drawStyledText(context, mc.textRenderer, headerText, titleX, y + 9, 0xFFFFFFFF);

            context.fill(x + 8, y + 25, x + width - 8, y + 26, 0x1AFFFFFF);

            int currentY = y + 30 - scrollY;
            int clipTop = y + 28;
            int clipBottom = y + height - 4;

            for (GuiModuleCard card : cards) {
                int cardH = card.getTotalHeight();
                if (currentY + cardH >= clipTop && currentY <= clipBottom) {
                    card.setY(currentY);
                    card.render(context, mouseX, mouseY);
                } else {
                    card.setY(-9999);
                }
                currentY += cardH + 4;
            }
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (mouseY < y + 28 || mouseY > y + height) return false;

            for (GuiModuleCard card : cards) {
                if (card.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return false;
        }

        public void mouseReleased(int button) { for (GuiModuleCard card : cards) card.mouseReleased(button); }
        public void mouseDragged(int mouseX, int mouseY) { for (GuiModuleCard card : cards) card.mouseDragged(mouseX, mouseY); }
    }

    public static class GuiModuleCard {
        private final Module module;
        private final int x;
        private int y = -9999;
        private final int width;
        private boolean expanded = false;
        private final List<GuiSettingWidget> widgets = new ArrayList<>();

        public GuiModuleCard(Module module, int x, int width) {
            this.module = module;
            this.x = x;
            this.width = width;

            for (Setting<?> s : module.getSettings()) {
                if (s instanceof BooleanSetting b) widgets.add(new GuiBooleanWidget(b));
                else if (s instanceof SliderSetting sl) widgets.add(new GuiSliderWidget(sl));
                else if (s instanceof ModeSetting m) widgets.add(new GuiModeWidget(m));
            }
        }

        public void setY(int y) { this.y = y; }

        public int getTotalHeight() {
            int h = 23;
            if (expanded) {
                for (GuiSettingWidget w : widgets) {
                    h += w.getHeight() + 3;
                }
                h += 4;
            }
            return h;
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            if (y < -500) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 23;

            if (hovered) {
                ModernRefinedClickGui.hoveredDescription = module.getDescription();
            }

            int bg = module.isEnabled() ? 0xDD3730A3 : (hovered ? 0xDD22222E : 0xB8171720);
            int border = module.isEnabled() ? 0xEE818CF8 : (hovered ? 0x44FFFFFF : 0x1AFFFFFF);

            ModernRefinedClickGui.drawSmoothRect(context, x, y, width, 23, bg, border);

            int textColor = module.isEnabled() ? 0xFFFFFFFF : (hovered ? 0xFFE2E8F0 : 0xFF94A3B8);
            drawStyledText(context, mc.textRenderer, module.getName(), x + 8, y + 7, textColor);

            String bindText = module.isListeningForBind() ? "§e[...]" : (module.getKeyBind() != GLFW.GLFW_KEY_UNKNOWN ? "§7[" + module.getBindName() + "]" : "");
            if (!bindText.isEmpty()) {
                int bindW = mc.textRenderer.getWidth(bindText);
                drawStyledText(context, mc.textRenderer, bindText, x + width - bindW - 20, y + 7, 0xFFFFFFFF);
            }

            if (!widgets.isEmpty()) {
                int iconColor = expanded ? 0xFF818CF8 : (hovered ? 0xFFD1D5DB : 0xFF64748B);
                drawStyledText(context, mc.textRenderer, "≡", x + width - 14, y + 7, iconColor);
            }

            if (expanded) {
                int containerH = getTotalHeight() - 25;
                ModernRefinedClickGui.drawSmoothRect(context, x, y + 24, width, containerH, 0xEE0E0E14, 0x22FFFFFF);

                int widgetY = y + 27;
                for (GuiSettingWidget w : widgets) {
                    w.render(context, x + 5, widgetY, width - 10, mouseX, mouseY);
                    widgetY += w.getHeight() + 3;
                }
            }
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (y < -500) return false;

            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 23) {
                if (button == 2) {
                    module.setListeningForBind(!module.isListeningForBind());
                    return true;
                }

                if (mouseX >= x + width - 18) {
                    if (!widgets.isEmpty()) expanded = !expanded;
                    return true;
                }

                if (button == 0) {
                    module.toggle();
                    return true;
                } else if (button == 1) {
                    if (!widgets.isEmpty()) expanded = !expanded;
                    return true;
                }
            }

            if (expanded) {
                int widgetY = y + 27;
                for (GuiSettingWidget w : widgets) {
                    if (w.mouseClicked(x + 5, widgetY, width - 10, mouseX, mouseY, button)) {
                        return true;
                    }
                    widgetY += w.getHeight() + 3;
                }
            }
            return false;
        }

        public void mouseReleased(int button) { for (GuiSettingWidget w : widgets) w.mouseReleased(button); }
        public void mouseDragged(int mouseX, int mouseY) { for (GuiSettingWidget w : widgets) w.mouseDragged(mouseX, mouseY); }
    }

    public interface GuiSettingWidget {
        int getHeight();
        void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY);
        boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button);
        default void mouseReleased(int button) {}
        default void mouseDragged(int mouseX, int mouseY) {}
    }

    public static class GuiSliderWidget implements GuiSettingWidget {
        private final SliderSetting setting;
        private boolean sliding = false;
        private int lastX, lastW;

        public GuiSliderWidget(SliderSetting setting) { this.setting = setting; }

        @Override public int getHeight() { return 21; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            this.lastX = x;
            this.lastW = width;
            MinecraftClient mc = MinecraftClient.getInstance();

            String text = String.format("%s: §7%.2f%s", setting.getName(), setting.get(), setting.getSuffix());
            drawStyledText(context, mc.textRenderer, text, x + 2, y + 2, 0xFFE2E8F0);

            int barY = y + 13;
            context.fill(x, barY, x + width, barY + 4, 0xFF222230);

            double pct = (setting.get() - setting.getMin()) / (setting.getMax() - setting.getMin());
            int fillW = (int) (width * MathHelper.clamp(pct, 0.0, 1.0));
            context.fill(x, barY, x + fillW, barY + 4, 0xFF6366F1);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y + 8 && mouseY <= y + 20) {
                this.sliding = true;
                update(mouseX);
                return true;
            }
            return false;
        }

        @Override public void mouseReleased(int button) { this.sliding = false; }
        @Override public void mouseDragged(int mouseX, int mouseY) { if (sliding) update(mouseX); }

        private void update(int mouseX) {
            double pct = (double) (mouseX - lastX) / (double) lastW;
            double val = setting.getMin() + (setting.getMax() - setting.getMin()) * pct;
            setting.setValueClamped(val);
        }
    }

    public static class GuiModeWidget implements GuiSettingWidget {
        private final ModeSetting setting;

        public GuiModeWidget(ModeSetting setting) { this.setting = setting; }

        @Override public int getHeight() { return 17; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawStyledText(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1);

            String modeStr = "§8[§f" + setting.get() + "§8]";
            int modeW = mc.textRenderer.getWidth(modeStr);
            drawStyledText(context, mc.textRenderer, modeStr, x + width - modeW - 2, y + 4, 0xFF818CF8);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y + 8 && mouseY <= y + 17) {
                setting.cycle();
                return true;
            }
            return false;
        }
    }

    public static class GuiBooleanWidget implements GuiSettingWidget {
        private final BooleanSetting setting;

        public GuiBooleanWidget(BooleanSetting setting) { this.setting = setting; }

        @Override public int getHeight() { return 17; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawStyledText(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1);

            int btnX = x + width - 13;
            int col = setting.get() ? 0xFF6366F1 : 0xFF2A2A3C;
            ModernRefinedClickGui.drawSmoothRect(context, btnX, y + 3, 11, 11, col, 0x44FFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y + 3 && mouseY <= y + 17) {
                setting.toggle();
                return true;
            }
            return false;
        }
    }
}
