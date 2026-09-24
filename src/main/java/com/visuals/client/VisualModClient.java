package com.visuals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.GameRenderer;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

public class VisualModClient implements ClientModInitializer {
    public static final String MOD_ID = "visuals_mod";
    public static VisualModClient INSTANCE;

    private static Method cachedDrawTextString = null;
    private static Method cachedDrawTextText = null;
    private static boolean textRendererInitialized = false;

    private KeyBinding clickGuiKey;
    private KeyBinding cosmeticsKey;
    private final ModuleManager moduleManager = new ModuleManager();
    private boolean wasInsertKeyDown = false;
    private boolean wasRShiftKeyDown = false;

    private final boolean[] keyStates = new boolean[512];

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        clickGuiKey = registerKeyBindingSafely("key.visuals.clickgui", GLFW.GLFW_KEY_INSERT, "category.visuals");
        cosmeticsKey = registerKeyBindingSafely("key.visuals.cosmetics", GLFW.GLFW_KEY_RIGHT_SHIFT, "category.visuals");
        moduleManager.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean openRequested = false;
            boolean openCosmeticsRequested = false;

            if (clickGuiKey != null && clickGuiKey.wasPressed()) openRequested = true;
            if (cosmeticsKey != null && cosmeticsKey.wasPressed()) openCosmeticsRequested = true;

            if (client.getWindow() != null && client.getWindow().getHandle() != 0) {
                long handle = client.getWindow().getHandle();
                boolean isInsertDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS;
                if (isInsertDown && !wasInsertKeyDown) openRequested = true;
                wasInsertKeyDown = isInsertDown;

                boolean isRShiftDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                if (isRShiftDown && !wasRShiftKeyDown) openCosmeticsRequested = true;
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

            if (openRequested && client.currentScreen == null) {
                client.setScreen(new ModernRefinedClickGui(moduleManager));
            } else if (openCosmeticsRequested && client.currentScreen == null) {
                client.setScreen(new CosmeticsScreen(moduleManager));
            }

            if (client.world != null && client.player != null) {
                moduleManager.onTick(client);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;

            CrosshairModule cross = (CrosshairModule) moduleManager.getModule(CrosshairModule.class);
            if (cross != null && cross.isEnabled()) {
                cross.renderCustomCrosshair(drawContext, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
            }

            TargetHudModule thud = (TargetHudModule) moduleManager.getModule(TargetHudModule.class);
            if (thud != null && thud.isEnabled()) {
                thud.render(drawContext, mc);
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
            } catch (Throwable fallbackErr) {
                System.out.println("[VisualMod] Polling fallback active for key: " + translationKey);
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
        if (!textRendererInitialized) initDrawTextMethods();

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
        private final double min;
        private final double max;
        private final double increment;
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

    public static class AspectRatioModule extends Module {
        public final ModeSetting presets = new ModeSetting("Соотношение", "4:3", List.of("16:9", "16:10", "4:3", "5:4", "1:1", "21:9", "3:2", "Custom"));
        public final SliderSetting customRatio = new SliderSetting("Кастомный Aspect", 1.33, 0.50, 2.40, 0.05, "");

        public AspectRatioModule() {
            super("AspectRatio", "Изменяет соотношение сторон камеры и геометрию мира", Category.RENDER);
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
                case "3:2" -> 3.0f / 2.0f;
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

            client.world.setTimeOfDay(targetTime);

            if (clearWeather.get()) {
                client.world.setRainGradient(0.0f);
                client.world.setThunderGradient(0.0f);
            }
        }
    }

    public static class FullBrightModule extends Module {
        public FullBrightModule() {
            super("FullBright", "Максимальная видимость в пещерах и темных локациях", Category.RENDER);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 200, 0, false, false, false));
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
            }
        }
    }

    public static class ZoomModule extends Module {
        public final SliderSetting zoomLevel = new SliderSetting("Кратность", 3.0, 1.5, 6.0, 0.5, "x");
        private Integer baseFov = null;

        public ZoomModule() {
            super("Zoom", "Кинематографическое плавное приближение камеры", Category.RENDER);
            registerSetting(zoomLevel);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            if (baseFov == null) baseFov = client.options.getFov().getValue();
            int targetFov = (int) (baseFov / zoomLevel.get());
            client.options.getFov().setValue(MathHelper.clamp(targetFov, 30, 110));
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null && baseFov != null) {
                mc.options.getFov().setValue(MathHelper.clamp(baseFov, 30, 110));
            }
        }
    }

    public static class CrosshairModule extends Module {
        public final ModeSetting style = new ModeSetting("Форма", "Classic", List.of("Classic", "Dot", "Circle", "Cross", "T-Cross"));
        public final SliderSetting gap = new SliderSetting("Зазор", 3.0, 0.0, 10.0, 1.0, "px");
        public final SliderSetting length = new SliderSetting("Длина", 5.0, 1.0, 12.0, 1.0, "px");
        public final SliderSetting thickness = new SliderSetting("Толщина", 1.0, 1.0, 3.0, 0.5, "px");

        public CrosshairModule() {
            super("Crosshair", "Кастомный статический прицел с настройкой размера", Category.RENDER);
            registerSetting(style);
            registerSetting(gap);
            registerSetting(length);
            registerSetting(thickness);
        }

        public void renderCustomCrosshair(DrawContext context, int screenW, int screenH) {
            int cx = screenW / 2;
            int cy = screenH / 2;
            int g = gap.get().intValue();
            int l = length.get().intValue();
            int t = (int) Math.max(1, thickness.get());
            int col = 0xFFFFFFFF;
            int border = 0xAA000000;

            String s = style.get();
            if (s.equals("Dot")) {
                context.fill(cx - 1, cy - 1, cx + 2, cy + 2, border);
                context.fill(cx, cy, cx + 1, cy + 1, col);
                return;
            }

            if (!s.equals("Circle")) {
                context.fill(cx - g - l - 1, cy - t / 2 - 1, cx - g + 1, cy + t / 2 + 2, border);
                context.fill(cx - g - l, cy - t / 2, cx - g, cy + t / 2 + 1, col);

                context.fill(cx + g - 1, cy - t / 2 - 1, cx + g + l + 1, cy + t / 2 + 2, border);
                context.fill(cx + g, cy - t / 2, cx + g + l, cy + t / 2 + 1, col);

                context.fill(cx - t / 2 - 1, cy + g - 1, cx + t / 2 + 2, cy + g + l + 1, border);
                context.fill(cx - t / 2, cy + g, cx + t / 2 + 1, cy + g + l, col);

                if (!s.equals("T-Cross")) {
                    context.fill(cx - t / 2 - 1, cy - g - l - 1, cx + t / 2 + 2, cy - g + 1, border);
                    context.fill(cx - t / 2, cy - g - l, cx + t / 2 + 1, cy - g, col);
                }
            } else {
                for (int i = 0; i < 360; i += 30) {
                    double rad = Math.toRadians(i);
                    int px = (int) (cx + Math.cos(rad) * (g + 3));
                    int py = (int) (cy + Math.sin(rad) * (g + 3));
                    context.fill(px, py, px + 2, py + 2, col);
                }
            }
        }
    }

    public static class CosmeticsModule extends Module {
        public final BooleanSetting enableWings = new BooleanSetting("Крылья", true);
        public final ModeSetting wingStyle = new ModeSetting("Стиль крыльев", "Angel", List.of("Angel", "Dragon", "Demon", "Cyber"));
        public final SliderSetting wingScale = new SliderSetting("Размах", 1.0, 0.5, 2.0, 0.1, "x");
        public final BooleanSetting enableCape = new BooleanSetting("Плащ", true);
        public final ModeSetting capeStyle = new ModeSetting("Стиль плаща", "Pulse", List.of("Pulse", "Cosmo", "Fire", "Wave"));
        public final ModeSetting headAccessory = new ModeSetting("Голова", "Halo", List.of("None", "Halo", "ChinaHat", "Horns", "Crown"));
        public final BooleanSetting backKatana = new BooleanSetting("Катана", true);

        public CosmeticsModule() {
            super("Cosmetics", "Кастомные 3D-крылья, физический плащ, нимб и катана", Category.RENDER);
            registerSetting(enableWings);
            registerSetting(wingStyle);
            registerSetting(wingScale);
            registerSetting(enableCape);
            registerSetting(capeStyle);
            registerSetting(headAccessory);
            registerSetting(backKatana);
            setEnabled(true);
        }

        private void drawQuad3D(MatrixStack matrices, float x0, float y0, float x1, float y1, int color) {
            Matrix4f mat = matrices.peek().getPositionMatrix();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();

            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            net.minecraft.client.render.Tessellator tess = net.minecraft.client.render.Tessellator.getInstance();
            net.minecraft.client.render.BufferBuilder buf = tess.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, net.minecraft.client.render.VertexFormats.POSITION_COLOR);

            int a = (color >> 24) & 0xFF;
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;
            if (a == 0) a = 255;

            buf.vertex(mat, x0, y0, 0.0f).color(r, g, b, a);
            buf.vertex(mat, x1, y0, 0.0f).color(r, g, b, a);
            buf.vertex(mat, x1, y1, 0.0f).color(r, g, b, a);
            buf.vertex(mat, x0, y1, 0.0f).color(r, g, b, a);

            net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buf.end());
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    public static class TargetHudModule extends Module {
        public final SliderSetting posX = new SliderSetting("Позиция X", 160.0, 10.0, 1200.0, 10.0, "px");
        public final SliderSetting posY = new SliderSetting("Позиция Y", 140.0, 10.0, 800.0, 10.0, "px");
        public final SliderSetting maxDistance = new SliderSetting("Макс. Дистанция", 24.0, 6.0, 64.0, 2.0, "m");

        private LivingEntity currentTarget = null;
        private float animAlpha = 0.0f;
        private float hpProgress = 20.0f;
        private float secondaryHp = 20.0f;
        private float absorptionProgress = 0.0f;
        private float lastHurtTime = 0.0f;
        private final List<HudParticle> particles = new CopyOnWriteArrayList<>();

        public TargetHudModule() {
            super("TargetHUD", "Интерактивная плашка цели с частицами и скином", Category.RENDER);
            registerSetting(posX);
            registerSetting(posY);
            registerSetting(maxDistance);
            setEnabled(true);
        }

        public void setTarget(LivingEntity target) {
            this.currentTarget = target;
        }

        public void render(DrawContext context, MinecraftClient mc) {
            LivingEntity target = resolveTarget(mc);
            boolean isPreview = (target == null && mc.currentScreen instanceof ModernRefinedClickGui && mc.player != null);
            if (isPreview) target = mc.player;

            boolean visible = (target != null && target.isAlive());
            if (visible && !isPreview && mc.player != null) {
                double dist = mc.player.distanceTo(target);
                if (dist > maxDistance.get()) visible = false;
            }

            animAlpha = MathHelper.lerp(0.18f, animAlpha, visible ? 1.0f : 0.0f);
            if (animAlpha < 0.02f) {
                particles.clear();
                return;
            }

            if (target == null) return;

            float x = posX.get().floatValue();
            float y = posY.get().floatValue();
            float maxHp = Math.max(1.0f, target.getMaxHealth());
            float curHp = MathHelper.clamp(target.getHealth(), 0.0f, maxHp);
            float abs = Math.max(0.0f, target.getAbsorptionAmount());

            hpProgress = MathHelper.lerp(0.25f, hpProgress, curHp);
            secondaryHp = MathHelper.lerp(0.08f, secondaryHp, curHp);
            absorptionProgress = MathHelper.lerp(0.25f, absorptionProgress, abs);

            if (target.hurtTime > 0 && target.hurtTime > lastHurtTime) {
                for (int i = 0; i < 5; i++) {
                    particles.add(new HudParticle(x + 16.0f, y + 20.0f));
                }
            }
            lastHurtTime = target.hurtTime;

            int alphaInt = (int) (animAlpha * 255.0f);
            int bgCol = (MathHelper.clamp(alphaInt, 0, 220) << 24) | 0x111019;
            int borderCol = ((int) (alphaInt * 0.4f) << 24) | 0x6366F1;

            ModernRefinedClickGui.drawSmoothRect(context, (int) x, (int) y, 126, 42, bgCol, borderCol);

            int headAlpha = (int) (animAlpha * 255.0f);
            int headCol = (headAlpha << 24) | 0xFFFFFF;

            if (target instanceof AbstractClientPlayerEntity player) {
                Identifier skin = player.getSkinTextures().texture();
                context.drawTexture(skin, (int) x + 5, (int) y + 5, 32, 32, 8.0f, 8.0f, 8, 8, 64, 64);
                context.drawTexture(skin, (int) x + 5, (int) y + 5, 32, 32, 40.0f, 8.0f, 8, 8, 64, 64);
            } else {
                ModernRefinedClickGui.drawSmoothRect(context, (int) x + 5, (int) y + 5, 32, 32, 0x55333344, 0x44FFFFFF);
                String letter = target.getName().getString().substring(0, 1).toUpperCase();
                drawTextSafe(context, mc.textRenderer, letter, (int) x + 17, (int) y + 17, headCol, false);
            }

            String name = target.getName().getString();
            if (name.length() > 11) name = name.substring(0, 10) + "..";
            drawTextSafe(context, mc.textRenderer, name, (int) x + 42, (int) y + 7, ((int) (animAlpha * 255.0f) << 24) | 0xFFFFFF, false);

            if (mc.player != null && !isPreview) {
                double dist = mc.player.distanceTo(target);
                String distStr = String.format("%.1fm", dist);
                int dw = mc.textRenderer.getWidth(distStr);
                drawTextSafe(context, mc.textRenderer, distStr, (int) (x + 120 - dw), (int) y + 7, ((int) (animAlpha * 180.0f) << 24) | 0x94A3B8, false);
            }

            float barX = x + 42.0f;
            float barY = y + 23.0f;
            float barW = 76.0f;
            float barH = 5.0f;

            context.fill((int) barX, (int) barY, (int) (barX + barW), (int) (barY + barH), 0x55222230);

            float secPct = MathHelper.clamp(secondaryHp / maxHp, 0.0f, 1.0f);
            context.fill((int) barX, (int) barY, (int) (barX + barW * secPct), (int) (barY + barH), ((int) (animAlpha * 220.0f) << 24) | 0xEF4444);

            float hpPct = MathHelper.clamp(hpProgress / maxHp, 0.0f, 1.0f);
            context.fill((int) barX, (int) barY, (int) (barX + barW * hpPct), (int) (barY + barH), ((int) (animAlpha * 255.0f) << 24) | 0x6366F1);

            if (absorptionProgress > 0.1f) {
                float absPct = MathHelper.clamp(absorptionProgress / maxHp, 0.0f, 1.0f);
                context.fill((int) barX, (int) (barY + 3), (int) (barX + barW * absPct), (int) (barY + barH), ((int) (animAlpha * 255.0f) << 24) | 0xFACC15);
            }

            String hpText = String.format("%.1f HP", curHp);
            drawTextSafe(context, mc.textRenderer, hpText, (int) barX, (int) barY + 7, ((int) (animAlpha * 200.0f) << 24) | 0xCBD5E1, false);

            for (HudParticle p : particles) {
                p.update();
                if (p.isDead()) {
                    particles.remove(p);
                } else {
                    float pAlpha = (1.0f - p.age / (float) p.maxAge) * animAlpha;
                    int col = ((int) (pAlpha * 255.0f) << 24) | 0x6366F1;
                    context.fill((int) p.x, (int) p.y, (int) p.x + 2, (int) p.y + 2, col);
                }
            }
        }

        private LivingEntity resolveTarget(MinecraftClient mc) {
            if (currentTarget != null && currentTarget.isAlive() && mc.player != null && mc.player.distanceTo(currentTarget) <= maxDistance.get()) {
                return currentTarget;
            }
            if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity living && living.isAlive()) {
                if (mc.player != null && mc.player.distanceTo(living) <= maxDistance.get()) {
                    currentTarget = living;
                    return living;
                }
            }
            return null;
        }

        private static class HudParticle {
            float x, y, vx, vy;
            int age = 0;
            int maxAge = 25;

            public HudParticle(float x, float y) {
                this.x = x;
                this.y = y;
                this.vx = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 2.5f;
                this.vy = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 2.5f;
            }

            public void update() {
                x += vx;
                y += vy;
                age++;
            }

            public boolean isDead() { return age >= maxAge; }
        }
    }

    public static class NoHurtCamModule extends Module {
        public NoHurtCamModule() {
            super("NoHurtCam", "Отключает дезориентирующую тряску камеры при ударах", Category.REMOVALS);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            client.options.getDamageTiltStrength().setValue(0.0);
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) mc.options.getDamageTiltStrength().setValue(1.0);
        }
    }

    public static class LowFireModule extends Module {
        public final SliderSetting height = new SliderSetting("Высота", 0.15, 0.0, 1.0, 0.05, "");
        public LowFireModule() {
            super("LowFire", "Опускает текстуру огня от первого лица", Category.REMOVALS);
            registerSetting(height);
        }
    }

    public static class LowShieldModule extends Module {
        public final SliderSetting offsetY = new SliderSetting("Опустить Y", 0.35, 0.0, 0.8, 0.05, "");
        public final SliderSetting scale = new SliderSetting("Масштаб", 0.70, 0.3, 1.0, 0.05, "x");

        public LowShieldModule() {
            super("LowShield", "Уменьшает и опускает щит во второй руке", Category.REMOVALS);
            registerSetting(offsetY);
            registerSetting(scale);
        }
    }

    public static class AntiBlindnessModule extends Module {
        public AntiBlindnessModule() {
            super("AntiBlindness", "Удаляет эффекты слепоты, тьмы и искажения портала", Category.REMOVALS);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.hasStatusEffect(StatusEffects.BLINDNESS)) client.player.removeStatusEffect(StatusEffects.BLINDNESS);
            if (client.player.hasStatusEffect(StatusEffects.DARKNESS)) client.player.removeStatusEffect(StatusEffects.DARKNESS);
            if (client.player.hasStatusEffect(StatusEffects.NAUSEA)) client.player.removeStatusEffect(StatusEffects.NAUSEA);
        }
    }

    public static class NoRenderModule extends Module {
        public NoRenderModule() {
            super("NoRender", "Блокирует частицы взрывов и анимацию тотема", Category.REMOVALS);
        }
    }

    public static class TriggerBotModule extends Module {
        public final BooleanSetting critOnly = new BooleanSetting("Только криты", true);

        public TriggerBotModule() {
            super("TriggerBot", "Автоматический удар при наведении на цель", Category.COMBAT);
            registerSetting(critOnly);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.interactionManager == null) return;
            if (client.player.getAttackCooldownProgress(0.5f) < 0.95f) return;

            if (critOnly.get()) {
                boolean isCrit = client.player.fallDistance > 0.0f
                        && !client.player.isOnGround()
                        && !client.player.isClimbing()
                        && !client.player.isTouchingWater()
                        && !client.player.hasStatusEffect(StatusEffects.BLINDNESS)
                        && !client.player.isSprinting();
                if (!isCrit) return;
            }

            Entity target = null;
            if (client.crosshairTarget instanceof EntityHitResult ehr) {
                target = ehr.getEntity();
            } else {
                HitBoxesModule hb = (HitBoxesModule) VisualModClient.INSTANCE.getModuleManager().getModule(HitBoxesModule.class);
                if (hb != null && hb.isEnabled()) {
                    target = hb.getExpandedTarget(client, 3.8);
                }
            }

            if (target instanceof LivingEntity living && living.isAlive()) {
                client.interactionManager.attackEntity(client.player, living);
                client.player.swingHand(Hand.MAIN_HAND);

                TargetHudModule thud = (TargetHudModule) VisualModClient.INSTANCE.getModuleManager().getModule(TargetHudModule.class);
                if (thud != null) thud.setTarget(living);
            }
        }
    }

    public static class HitBoxesModule extends Module {
        public final SliderSetting expand = new SliderSetting("Расширение", 0.35, 0.05, 1.2, 0.05, "m");

        public HitBoxesModule() {
            super("HitBoxes", "Увеличивает объем хитбоксов целей для попаданий", Category.COMBAT);
            registerSetting(expand);
        }

        public Entity getExpandedTarget(MinecraftClient mc, double reach) {
            if (mc.player == null || mc.world == null) return null;
            Vec3d cam = mc.player.getCameraPosVec(1.0f);
            Vec3d rot = mc.player.getRotationVec(1.0f);
            Vec3d end = cam.add(rot.multiply(reach));
            double exp = expand.get();

            Entity best = null;
            double bestDist = reach;

            for (Entity e : mc.world.getEntities()) {
                if (e != mc.player && e instanceof LivingEntity living && living.isAlive()) {
                    Box box = living.getBoundingBox().expand(exp);
                    Optional<Vec3d> hit = box.raycast(cam, end);
                    if (hit.isPresent()) {
                        double d = cam.distanceTo(hit.get());
                        if (d < bestDist) {
                            bestDist = d;
                            best = living;
                        }
                    }
                }
            }
            return best;
        }
    }

    public static class VelocityModule extends Module {
        public final SliderSetting horizontal = new SliderSetting("Горизонталь", 0.0, 0.0, 1.0, 0.05, "%");
        public final SliderSetting vertical = new SliderSetting("Вертикаль", 0.0, 0.0, 1.0, 0.05, "%");

        public VelocityModule() {
            super("Velocity", "Снижает или полностью убирает отдачу от ударов", Category.COMBAT);
            registerSetting(horizontal);
            registerSetting(vertical);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.hurtTime == 9) {
                client.player.setVelocity(
                        client.player.getVelocity().x * horizontal.get(),
                        client.player.getVelocity().y * vertical.get(),
                        client.player.getVelocity().z * horizontal.get()
                );
            }
        }
    }

    public static class TapeMouseModule extends Module {
        public final SliderSetting minCps = new SliderSetting("Мин. CPS", 9.0, 4.0, 20.0, 1.0, "");
        public final SliderSetting maxCps = new SliderSetting("Макс. CPS", 13.0, 6.0, 25.0, 1.0, "");
        private long lastClickTime = 0;

        public TapeMouseModule() {
            super("TapeMouse", "Эмуляция зажатия мыши с реалистичным разбросом CPS", Category.COMBAT);
            registerSetting(minCps);
            registerSetting(maxCps);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.currentScreen != null) return;

            long handle = client.getWindow().getHandle();
            boolean lmbDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
            if (!lmbDown) return;

            long now = System.currentTimeMillis();
            double cps = ThreadLocalRandom.current().nextDouble(minCps.get(), maxCps.get());
            long delay = (long) (1000.0 / Math.max(1.0, cps));

            if (now - lastClickTime >= delay) {
                lastClickTime = now;
                if (client.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity living) {
                    client.interactionManager.attackEntity(client.player, living);
                    client.player.swingHand(Hand.MAIN_HAND);
                } else if (client.crosshairTarget instanceof BlockHitResult bhr) {
                    client.interactionManager.updateBlockBreakingProgress(bhr.getBlockPos(), bhr.getSide());
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        }
    }

    public static class AutoSprintModule extends Module {
        public AutoSprintModule() {
            super("AutoSprint", "Автоматический непрерывный спринт при ходьбе", Category.MOVEMENT);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.forwardSpeed > 0 && !client.player.isSneaking() && !client.player.horizontalCollision) {
                client.player.setSprinting(true);
            }
        }
    }

    public static class FastBreakModule extends Module {
        public FastBreakModule() {
            super("FastBreak", "Увеличивает скорость копания и сбрасывает задержки", Category.MOVEMENT);
        }
    }

    public static class InventoryMoveModule extends Module {
        public InventoryMoveModule() {
            super("InventoryMove", "Свободное движение при открытом инвентаре", Category.MOVEMENT);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.currentScreen == null) return;
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) return;

            long h = client.getWindow().getHandle();
            client.options.forwardKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS);
            client.options.backKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS);
            client.options.leftKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS);
            client.options.rightKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS);
            client.options.jumpKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS);
            client.options.sprintKey.setPressed(GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS);
        }
    }

    public static class WaterSpeedModule extends Module {
        public WaterSpeedModule() {
            super("WaterSpeed", "Увеличивает скорость плавания под водой", Category.MOVEMENT);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (client.player.isTouchingWater()) {
                client.player.setVelocity(client.player.getVelocity().multiply(1.15, 1.05, 1.15));
            }
        }
    }

    public static class FastPlaceModule extends Module {
        public FastPlaceModule() {
            super("FastPlace", "Убирает задержку использования блоков ПКМ", Category.MISC);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled()) return;
            try {
                for (Field f : MinecraftClient.class.getDeclaredFields()) {
                    if (f.getType() == int.class && (f.getName().equals("itemUseCooldown") || f.getName().equals("field_1752"))) {
                        f.setAccessible(true);
                        f.setInt(client, 0);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class AutoToolModule extends Module {
        public AutoToolModule() {
            super("AutoTool", "Автоматически выбирает инструмент в слоте для копания", Category.MISC);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null || client.world == null) return;
            if (client.options.attackKey.isPressed() && client.crosshairTarget instanceof BlockHitResult bhr) {
                BlockState state = client.world.getBlockState(bhr.getBlockPos());
                if (state.isAir()) return;

                int bestSlot = -1;
                float bestSpeed = 1.0f;

                for (int i = 0; i < 9; i++) {
                    ItemStack stack = client.player.getInventory().getStack(i);
                    float sp = stack.getMiningSpeedMultiplier(state);
                    if (sp > bestSpeed) {
                        bestSpeed = sp;
                        bestSlot = i;
                    }
                }

                if (bestSlot != -1 && bestSlot != client.player.getInventory().selectedSlot) {
                    client.player.getInventory().selectedSlot = bestSlot;
                }
            }
        }
    }

    public static class FreeCameraModule extends Module {
        public final SliderSetting speed = new SliderSetting("Скорость", 1.5, 0.5, 5.0, 0.2, "x");
        private OtherClientPlayerEntity dummyCamera = null;

        public FreeCameraModule() {
            super("FreeCamera", "Свободный полёт камеры сквозь блоки (NoClip)", Category.MISC);
            registerSetting(speed);
        }

        @Override
        public void onEnable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.world == null) return;

            dummyCamera = new OtherClientPlayerEntity(mc.world, mc.player.getGameProfile());
            dummyCamera.copyPositionAndRotation(mc.player);
            dummyCamera.setYaw(mc.player.getYaw());
            dummyCamera.setPitch(mc.player.getPitch());
            dummyCamera.noClip = true;
            mc.world.addEntity(dummyCamera);
            mc.setCameraEntity(dummyCamera);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (dummyCamera == null || client.player == null) return;

            long h = client.getWindow().getHandle();
            float sp = speed.get().floatValue() * 0.45f;

            Vec3d forward = Vec3d.fromPolar(0.0f, dummyCamera.getYaw()).multiply(sp);
            Vec3d right = Vec3d.fromPolar(0.0f, dummyCamera.getYaw() + 90.0f).multiply(sp);

            Vec3d mot = Vec3d.ZERO;
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) mot = mot.add(forward);
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) mot = mot.subtract(forward);
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) mot = mot.subtract(right);
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) mot = mot.add(right);
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) mot = mot.add(0.0, sp, 0.0);
            if (GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) mot = mot.add(0.0, -sp, 0.0);

            dummyCamera.setPosition(dummyCamera.getX() + mot.x, dummyCamera.getY() + mot.y, dummyCamera.getZ() + mot.z);
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.setCameraEntity(mc.player);
            if (dummyCamera != null && mc.world != null) {
                mc.world.removeEntity(dummyCamera.getId(), Entity.RemovalReason.DISCARDED);
                dummyCamera = null;
            }
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void init() {
            modules.add(new TriggerBotModule());
            modules.add(new HitBoxesModule());
            modules.add(new VelocityModule());
            modules.add(new TapeMouseModule());

            modules.add(new AutoSprintModule());
            modules.add(new FastBreakModule());
            modules.add(new InventoryMoveModule());
            modules.add(new WaterSpeedModule());

            modules.add(new AspectRatioModule());
            modules.add(new AmbienceModule());
            modules.add(new FullBrightModule());
            modules.add(new ZoomModule());
            modules.add(new CrosshairModule());
            modules.add(new CosmeticsModule());
            modules.add(new TargetHudModule());

            modules.add(new NoHurtCamModule());
            modules.add(new LowFireModule());
            modules.add(new LowShieldModule());
            modules.add(new AntiBlindnessModule());
            modules.add(new NoRenderModule());

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

        public List<Module> getModulesByCategory(Category cat) {
            List<Module> list = new ArrayList<>();
            for (Module m : modules) {
                if (m.getCategory() == cat) list.add(m);
            }
            return list;
        }

        public Module getModule(Class<? extends Module> clazz) {
            for (Module m : modules) {
                if (m.getClass() == clazz) return m;
            }
            return null;
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
            super(Text.literal("Delta Client"));
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

            for (GuiColumn col : columns) col.render(context, mouseX, mouseY);

            MinecraftClient mc = MinecraftClient.getInstance();
            if (!hoveredDescription.isEmpty()) {
                int textW = mc.textRenderer.getWidth(hoveredDescription);
                int titleX = (this.width - textW) / 2;
                drawTextSafe(context, mc.textRenderer, hoveredDescription, titleX, 15, 0xFFFFFFFF, true);
            }

            int btnW = 90;
            int btnH = 18;
            int btnX = this.width - btnW - 14;
            int btnY = 12;
            boolean hovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
            drawSmoothRect(context, btnX, btnY, btnW, btnH, hovered ? 0xDD4338CA : 0xDD312E81, 0xFF818CF8);
            drawTextSafe(context, mc.textRenderer, "✦ Косметика", btnX + 11, btnY + 5, 0xFFFFFFFF, false);

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
            int btnW = 90;
            int btnH = 18;
            int btnX = this.width - btnW - 14;
            int btnY = 12;
            if (button == 0 && mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
                MinecraftClient.getInstance().setScreen(new CosmeticsScreen(moduleManager));
                playClickSound();
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

        public static void playClickSound() { playSoundSafely(SoundEvents.UI_BUTTON_CLICK, 1.0f); }

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
            for (GuiModuleCard card : cards) h += card.getTotalHeight() + 3;
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
            drawTextSafe(context, mc.textRenderer, headerText, titleX, y + 9, 0xFFFFFFFF, true);

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
                if (card.mouseClicked(mouseX, mouseY, button)) return true;
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
                for (GuiSettingWidget w : widgets) h += w.getHeight() + 3;
                h += 4;
            }
            return h;
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            if (y < -500) return;

            MinecraftClient mc = MinecraftClient.getInstance();
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 23;
            if (hovered) ModernRefinedClickGui.hoveredDescription = module.getDescription();

            int bg = module.isEnabled() ? 0xDD3730A3 : (hovered ? 0xDD22222E : 0xB8171720);
            int border = module.isEnabled() ? 0xEE818CF8 : (hovered ? 0x44FFFFFF : 0x1AFFFFFF);

            ModernRefinedClickGui.drawSmoothRect(context, x, y, width, 23, bg, border);

            int textColor = module.isEnabled() ? 0xFFFFFFFF : (hovered ? 0xFFE2E8F0 : 0xFF94A3B8);
            drawTextSafe(context, mc.textRenderer, module.getName(), x + 8, y + 7, textColor, false);

            String bindText = module.isListeningForBind() ? "§e[...]" : (module.getKeyBind() != GLFW.GLFW_KEY_UNKNOWN ? "§7[" + module.getBindName() + "]" : "");
            if (!bindText.isEmpty()) {
                int bindW = mc.textRenderer.getWidth(bindText);
                drawTextSafe(context, mc.textRenderer, bindText, x + width - bindW - 20, y + 7, 0xFFFFFFFF, false);
            }

            if (!widgets.isEmpty()) {
                int iconColor = expanded ? 0xFF818CF8 : (hovered ? 0xFFD1D5DB : 0xFF64748B);
                drawTextSafe(context, mc.textRenderer, "≡", x + width - 14, y + 7, iconColor, false);
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
                    if (w.mouseClicked(x + 5, widgetY, width - 10, mouseX, mouseY, button)) return true;
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
            drawTextSafe(context, mc.textRenderer, text, x + 2, y + 2, 0xFFE2E8F0, false);

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
            drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1, false);

            String modeStr = "§8[§f" + setting.get() + "§8]";
            int modeW = mc.textRenderer.getWidth(modeStr);
            drawTextSafe(context, mc.textRenderer, modeStr, x + width - modeW - 2, y + 4, 0xFF818CF8, false);
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
            drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1, false);

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

    public static class CosmeticsScreen extends Screen {
        private final ModuleManager moduleManager;
        private float playerRotation = 0.0f;
        private boolean draggingPlayer = false;
        private double lastDragX = 0;

        public CosmeticsScreen(ModuleManager moduleManager) {
            super(Text.literal("Cosmetics Studio"));
            this.moduleManager = moduleManager;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0xDD0C0B12);

            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, "✦ COSMETICS STUDIO", 24, 18, 0xFF818CF8, false);
            drawTextSafe(context, mc.textRenderer, "Управляйте своими клиентскими 3D аксессуарами", 24, 30, 0xFF94A3B8, false);

            int backBtnW = 92;
            int backBtnH = 20;
            int backBtnX = this.width - backBtnW - 24;
            int backBtnY = 18;
            boolean backHovered = mouseX >= backBtnX && mouseX <= backBtnX + backBtnW && mouseY >= backBtnY && mouseY <= backBtnY + backBtnH;
            ModernRefinedClickGui.drawSmoothRect(context, backBtnX, backBtnY, backBtnW, backBtnH, backHovered ? 0xDD312E81 : 0xAA1E1B4B, 0xFF6366F1);
            drawTextSafe(context, mc.textRenderer, "← В ClickGUI", backBtnX + 12, backBtnY + 6, 0xFFFFFFFF, false);

            int previewX = 24;
            int previewY = 50;
            int previewW = (this.width / 2) - 34;
            int previewH = this.height - 74;

            ModernRefinedClickGui.drawSmoothRect(context, previewX, previewY, previewW, previewH, 0x99111019, 0x336366F1);

            drawTextSafe(context, mc.textRenderer, "3D Персонаж", previewX + 14, previewY + 14, 0xFFE2E8F0, false);
            drawTextSafe(context, mc.textRenderer, "Зажмите ЛКМ на модели для вращения", previewX + 14, previewY + previewH - 18, 0xFF64748B, false);

            int settingsX = (this.width / 2) + 10;
            int settingsY = 50;
            int settingsW = (this.width / 2) - 34;
            int settingsH = this.height - 74;

            ModernRefinedClickGui.drawSmoothRect(context, settingsX, settingsY, settingsW, settingsH, 0xCC111019, 0x446366F1);

            CosmeticsModule cosm = (CosmeticsModule) moduleManager.getModule(CosmeticsModule.class);
            if (cosm != null) {
                int currY = settingsY + 16;

                boolean activeHover = mouseX >= settingsX + 14 && mouseX <= settingsX + settingsW - 14 && mouseY >= currY && mouseY <= currY + 22;
                int actBg = cosm.isEnabled() ? 0xDD3730A3 : (activeHover ? 0xDD22222E : 0xB8171720);
                ModernRefinedClickGui.drawSmoothRect(context, settingsX + 14, currY, settingsW - 28, 22, actBg, cosm.isEnabled() ? 0xFF818CF8 : 0x33FFFFFF);
                String actStr = cosm.isEnabled() ? "✔ Аксессуары: Включены" : "✖ Аксессуары: Отключены";
                drawTextSafe(context, mc.textRenderer, actStr, settingsX + 24, currY + 7, 0xFFFFFFFF, false);
                currY += 32;

                renderStudioOption(context, "Крылья", cosm.wingStyle.get(), settingsX + 14, currY, settingsW - 28, mouseX, mouseY);
                currY += 28;

                renderStudioSlider(context, "Размах крыльев", cosm.wingScale.get(), cosm.wingScale.getMin(), cosm.wingScale.getMax(), settingsX + 14, currY, settingsW - 28, mouseX, mouseY);
                currY += 34;

                renderStudioOption(context, "Плащ", cosm.capeStyle.get(), settingsX + 14, currY, settingsW - 28, mouseX, mouseY);
                currY += 28;

                renderStudioOption(context, "Голова", cosm.headAccessory.get(), settingsX + 14, currY, settingsW - 28, mouseX, mouseY);
                currY += 28;

                renderStudioToggle(context, "Катана на спине", cosm.backKatana.get(), settingsX + 14, currY, settingsW - 28, mouseX, mouseY);
            }

            super.render(context, mouseX, mouseY, delta);
        }

        private void renderStudioOption(DrawContext context, String label, String value, int x, int y, int w, int mx, int my) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, label, x, y + 4, 0xFFE2E8F0, false);

            int btnW = 90;
            int btnX = x + w - btnW;
            boolean hov = mx >= btnX && mx <= btnX + btnW && my >= y && my <= y + 18;
            ModernRefinedClickGui.drawSmoothRect(context, btnX, y, btnW, 18, hov ? 0xDD2A2A3E : 0xAA181824, 0x446366F1);

            int strW = mc.textRenderer.getWidth(value);
            drawTextSafe(context, mc.textRenderer, value, btnX + (btnW - strW) / 2, y + 5, 0xFF818CF8, false);
        }

        private void renderStudioToggle(DrawContext context, String label, boolean val, int x, int y, int w, int mx, int my) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, label, x, y + 4, 0xFFE2E8F0, false);

            int btnW = 44;
            int btnX = x + w - btnW;
            boolean hov = mx >= btnX && mx <= btnX + btnW && my >= y && my <= y + 18;
            int col = val ? 0xFF6366F1 : 0xFF272738;
            ModernRefinedClickGui.drawSmoothRect(context, btnX, y, btnW, 18, col, hov ? 0xFFFFFFFF : 0x44FFFFFF);
            String txt = val ? "ВКЛ" : "ВЫКЛ";
            int tw = mc.textRenderer.getWidth(txt);
            drawTextSafe(context, mc.textRenderer, txt, btnX + (btnW - tw) / 2, y + 5, 0xFFFFFFFF, false);
        }

        private void renderStudioSlider(DrawContext context, String label, double val, double min, double max, int x, int y, int w, int mx, int my) {
            MinecraftClient mc = MinecraftClient.getInstance();
            String info = String.format("%s: §7%.2fx", label, val);
            drawTextSafe(context, mc.textRenderer, info, x, y, 0xFFE2E8F0, false);

            int barY = y + 12;
            context.fill(x, barY, x + w, barY + 5, 0xFF222230);
            double pct = (val - min) / (max - min);
            int fillW = (int) (w * MathHelper.clamp(pct, 0.0, 1.0));
            context.fill(x, barY, x + fillW, barY + 5, 0xFF6366F1);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int backBtnW = 92;
            int backBtnH = 20;
            int backBtnX = this.width - backBtnW - 24;
            int backBtnY = 18;
            if (button == 0 && mouseX >= backBtnX && mouseX <= backBtnX + backBtnW && mouseY >= backBtnY && mouseY <= backBtnY + backBtnH) {
                MinecraftClient.getInstance().setScreen(new ModernRefinedClickGui(moduleManager));
                ModernRefinedClickGui.playClickSound();
                return true;
            }

            int previewX = 24;
            int previewY = 50;
            int previewW = (this.width / 2) - 34;
            int previewH = this.height - 74;
            if (button == 0 && mouseX >= previewX && mouseX <= previewX + previewW && mouseY >= previewY && mouseY <= previewY + previewH) {
                draggingPlayer = true;
                lastDragX = mouseX;
                return true;
            }

            int settingsX = (this.width / 2) + 10;
            int settingsY = 50;
            int settingsW = (this.width / 2) - 34;

            CosmeticsModule cosm = (CosmeticsModule) moduleManager.getModule(CosmeticsModule.class);
            if (cosm != null && button == 0) {
                int currY = settingsY + 16;
                if (mouseX >= settingsX + 14 && mouseX <= settingsX + settingsW - 14 && mouseY >= currY && mouseY <= currY + 22) {
                    cosm.toggle();
                    ModernRefinedClickGui.playClickSound();
                    return true;
                }
                currY += 32;

                int optBtnW = 90;
                int optX = settingsX + 14 + settingsW - 28 - optBtnW;
                if (mouseX >= optX && mouseX <= optX + optBtnW && mouseY >= currY && mouseY <= currY + 18) {
                    cosm.wingStyle.cycle();
                    ModernRefinedClickGui.playClickSound();
                    return true;
                }
                currY += 28;

                if (mouseX >= settingsX + 14 && mouseX <= settingsX + settingsW - 14 && mouseY >= currY + 8 && mouseY <= currY + 20) {
                    double pct = (mouseX - (settingsX + 14)) / (double) (settingsW - 28);
                    cosm.wingScale.setValueClamped(cosm.wingScale.getMin() + (cosm.wingScale.getMax() - cosm.wingScale.getMin()) * pct);
                    return true;
                }
                currY += 34;

                if (mouseX >= optX && mouseX <= optX + optBtnW && mouseY >= currY && mouseY <= currY + 18) {
                    cosm.capeStyle.cycle();
                    ModernRefinedClickGui.playClickSound();
                    return true;
                }
                currY += 28;

                if (mouseX >= optX && mouseX <= optX + optBtnW && mouseY >= currY && mouseY <= currY + 18) {
                    cosm.headAccessory.cycle();
                    ModernRefinedClickGui.playClickSound();
                    return true;
                }
                currY += 28;

                int tglW = 44;
                int tglX = settingsX + 14 + settingsW - 28 - tglW;
                if (mouseX >= tglX && mouseX <= tglX + tglW && mouseY >= currY && mouseY <= currY + 18) {
                    cosm.backKatana.toggle();
                    ModernRefinedClickGui.playClickSound();
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0) draggingPlayer = false;
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            if (draggingPlayer) {
                playerRotation += (float) (mouseX - lastDragX) * 1.5f;
                lastDragX = mouseX;
                return true;
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean shouldPause() { return false; }
    }

    public static class CustomTitleScreen extends Screen {
        private static final float BTN_W = 210.0f;
        private static final float BTN_H = 34.0f;
        private static final float BTN_GAP = 9.0f;
        private static final float ENTER_OFFSET_Y = 35.0f;

        private final List<MenuButton> mainButtons = new ArrayList<>();
        private final List<GraphicPresetButton> presetButtons = new ArrayList<>();
        private final Map<String, Float> buttonAnimMap = new HashMap<>();

        private final List<CursorParticle> cursorParticles = new ArrayList<>();
        private final List<BackgroundStar> backgroundStars = new ArrayList<>();
        private final List<ClickSpark> clickSparks = new ArrayList<>();
        private final Random random = new Random();

        private float openProgress = 0.0f;
        private long lastFrame = System.nanoTime();

        public static String currentMenuPreset = "Medium";
        public static int particlesPerFrame = 2;
        public static float menuAnimSpeed = 16.0f;
        public static int vignetteAlpha = 0x25;

        private static final int COLOR_BG_OVERLAY      = 0x770C081A;
        private static final int COLOR_CYAN_ACCENT     = 0xFF00E5FF;
        private static final int COLOR_PURPLE_ACCENT   = 0xFFB14EFF;
        private static final int COLOR_BTN_IDLE        = 0xAA160D2E;
        private static final int COLOR_BTN_HOVER       = 0xDD411A8C;
        private static final int COLOR_BTN_STROKE_IDLE = 0x33B14EFF;
        private static final int COLOR_BTN_STROKE_HOV  = 0xFFB14EFF;

        private static final Identifier CLOUD_BG = Identifier.of(MOD_ID, "textures/gui/background.png");

        public CustomTitleScreen() {
            super(Text.literal("Delta Client"));
        }

        private float getHoverAnim(String key, boolean hovered, float delta) {
            float cur = buttonAnimMap.getOrDefault(key, 0.0f);
            float target = hovered ? 1.0f : 0.0f;
            float step = delta * (menuAnimSpeed * 0.4f);
            cur = MathHelper.lerp(step, cur, target);
            cur = MathHelper.clamp(cur, 0.0f, 1.0f);
            buttonAnimMap.put(key, cur);
            return cur;
        }

        @Override
        protected void init() {
            this.mainButtons.clear();
            this.presetButtons.clear();
            this.openProgress = 0.0f;

            int physW = this.width;
            int physH = this.height;

            if (this.backgroundStars.isEmpty()) {
                for (int i = 0; i < 50; i++) {
                    this.backgroundStars.add(new BackgroundStar(
                            random.nextFloat() * physW,
                            random.nextFloat() * physH,
                            1.0f + random.nextFloat() * 2.0f,
                            10.0f + random.nextFloat() * 25.0f,
                            random.nextFloat() * 0.7f + 0.2f
                    ));
                }
            }

            float cx = physW / 2.0f;
            int rowsCount = 4;
            float totalH = rowsCount * BTN_H + (rowsCount - 1) * BTN_GAP;
            float startY = (physH - totalH) / 2.0f + 15.0f;

            this.mainButtons.add(new MenuButton("Одиночная игра", cx - BTN_W / 2.0f, startY, BTN_W, BTN_H,
                    () -> this.client.setScreen(new SelectWorldScreen(this))));

            this.mainButtons.add(new MenuButton("Сетевая игра", cx - BTN_W / 2.0f, startY + (BTN_H + BTN_GAP), BTN_W, BTN_H,
                    () -> this.client.setScreen(new MultiplayerScreen(this))));

            this.mainButtons.add(new MenuButton("Косметика и Студия", cx - BTN_W / 2.0f, startY + (BTN_H + BTN_GAP) * 2, BTN_W, BTN_H,
                    () -> this.client.setScreen(new CosmeticsScreen(VisualModClient.INSTANCE.getModuleManager()))));

            float halfW = BTN_W * 0.485f;
            float rowY = startY + (BTN_H + BTN_GAP) * 3;
            float leftX = cx - BTN_W / 2.0f;
            float rightX = cx + BTN_W / 2.0f - halfW;

            this.mainButtons.add(new MenuButton("Настройки", leftX, rowY, halfW, BTN_H,
                    () -> this.client.setScreen(new OptionsScreen(this, this.client.options))));

            this.mainButtons.add(new MenuButton("Выход", rightX, rowY, halfW, BTN_H,
                    () -> this.client.scheduleStop()));

            float presetX = 20.0f;
            float presetY = physH - 38.0f;
            float presetW = 54.0f;
            float presetH = 22.0f;
            float presetGap = 6.0f;

            String[] presets = {"Low", "Medium", "High", "Ultra"};
            for (int i = 0; i < presets.length; i++) {
                String presetName = presets[i];
                float bx = presetX + i * (presetW + presetGap);
                this.presetButtons.add(new GraphicPresetButton(presetName, bx, presetY, presetW, presetH, () -> {
                    currentMenuPreset = presetName;
                    applyMenuPreset(presetName);
                }));
            }
        }

        private void applyMenuPreset(String preset) {
            switch (preset) {
                case "Low" -> {
                    particlesPerFrame = 0;
                    menuAnimSpeed = 28.0f;
                    vignetteAlpha = 0x10;
                }
                case "Medium" -> {
                    particlesPerFrame = 2;
                    menuAnimSpeed = 16.0f;
                    vignetteAlpha = 0x25;
                }
                case "High" -> {
                    particlesPerFrame = 4;
                    menuAnimSpeed = 12.0f;
                    vignetteAlpha = 0x40;
                }
                case "Ultra" -> {
                    particlesPerFrame = 8;
                    menuAnimSpeed = 8.0f;
                    vignetteAlpha = 0x55;
                }
            }
        }

        private String getGreeting() {
            int hour = LocalTime.now().getHour();
            if (hour >= 4 && hour < 12) return "Доброе утро";
            if (hour >= 12 && hour < 18) return "Добрый день";
            if (hour >= 18 && hour < 23) return "Добрый вечер";
            return "Доброй ночи";
        }

        @Override
        public boolean shouldPause() { return false; }

        private float getOffsetY() {
            float eased = 1.0f - (float) Math.pow(1.0f - this.openProgress, 3);
            return (1.0f - eased) * ENTER_OFFSET_Y;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float deltaTick) {
            long now = System.nanoTime();
            float delta = (now - this.lastFrame) / 1_000_000_000.0f;
            this.lastFrame = now;

            int physW = this.width;
            int physH = this.height;

            drawBackgroundTexture(context, physW, physH);

            context.fill(0, 0, physW, physH, COLOR_BG_OVERLAY);
            drawTransparentPurpleGrid(context, physW, physH, mouseX, mouseY);
            drawBackgroundStars(context, physW, physH, delta);

            int topDark = (vignetteAlpha / 2) << 24 | 0x070614;
            int botDark = vignetteAlpha << 24 | 0x070614;
            context.fillGradient(0, 0, physW, physH, topDark, botDark);

            this.openProgress = Math.min(1.0f, this.openProgress + delta * 3.5f);
            float offsetY = getOffsetY();

            updateAndDrawCursorParticles(context, mouseX, mouseY, delta);
            updateAndDrawClickSparks(context, delta);

            drawGreetingHeader(context, physW, offsetY);
            drawMainButtons(context, mouseX, mouseY, offsetY, delta);
            drawPresetButtons(context, mouseX, mouseY, offsetY, delta);
            drawSystemInfoWidget(context, physW, physH, offsetY);
            drawDisclaimer(context, physH, offsetY);

            super.render(context, mouseX, mouseY, deltaTick);
        }

        private void drawBackgroundTexture(DrawContext context, int w, int h) {
            try {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

                boolean rendered = false;
                for (Method m : DrawContext.class.getMethods()) {
                    if (m.getName().equals("drawTexture") && m.getParameterCount() == 10) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts[0] == Identifier.class && pts[1] == int.class && pts[2] == int.class) {
                            m.invoke(context, CLOUD_BG, 0, 0, 0.0f, 0.0f, w, h, w, h);
                            rendered = true;
                            break;
                        }
                    }
                }
                if (!rendered) {
                    context.drawTexture(java.util.function.Function.identity() != null ? net.minecraft.client.render.RenderLayer::getGuiTextured : null, CLOUD_BG, 0, 0, 0.0f, 0.0f, w, h, w, h);
                }
            } catch (Throwable t) {
                context.fill(0, 0, w, h, 0xFF140F2D);
            }
        }

        private void drawTransparentPurpleGrid(DrawContext context, int physW, int physH, float mx, float my) {
            float moveX = (mx - physW / 2.0f) * 0.012f;
            float moveY = (my - physH / 2.0f) * 0.012f;
            float gridSize = 45.0f;
            int gridColor = 0x187B2CBF;

            for (float x = moveX % gridSize; x < physW; x += gridSize) {
                context.fill((int) x, 0, (int) x + 1, physH, gridColor);
            }
            for (float y = moveY % gridSize; y < physH; y += gridSize) {
                context.fill(0, (int) y, physW, (int) y + 1, gridColor);
            }
        }

        private void drawBackgroundStars(DrawContext context, int physW, int physH, float delta) {
            for (BackgroundStar star : this.backgroundStars) {
                star.update(delta, physW, physH);
                int starAlpha = (int) (star.alpha * 255.0f) << 24;
                int starColor = starAlpha | 0x00E0FFFF;
                context.fill((int) star.x, (int) star.y, (int) (star.x + star.size), (int) (star.y + star.size), starColor);
            }
        }

        private void updateAndDrawCursorParticles(DrawContext context, float mx, float my, float delta) {
            if (particlesPerFrame > 0 && mx >= 0 && my >= 0) {
                for (int i = 0; i < particlesPerFrame; i++) {
                    float vx = (random.nextFloat() - 0.5f) * 30.0f;
                    float vy = (random.nextFloat() - 0.5f) * 30.0f;
                    float size = 1.5f + random.nextFloat() * 2.0f;
                    float life = 0.3f + random.nextFloat() * 0.4f;
                    int color = random.nextBoolean() ? COLOR_CYAN_ACCENT : COLOR_PURPLE_ACCENT;
                    cursorParticles.add(new CursorParticle(mx, my, vx, vy, size, life, color));
                }
            }

            Iterator<CursorParticle> it = cursorParticles.iterator();
            while (it.hasNext()) {
                CursorParticle p = it.next();
                p.update(delta);
                if (p.isDead()) {
                    it.remove();
                } else {
                    int particleColor = ((int) (p.getAlpha() * 255.0f) << 24) | (p.color & 0x00FFFFFF);
                    context.fill((int) (p.x - p.size / 2.0f), (int) (p.y - p.size / 2.0f), (int) (p.x + p.size / 2.0f), (int) (p.y + p.size / 2.0f), particleColor);
                }
            }
        }

        private void updateAndDrawClickSparks(DrawContext context, float delta) {
            Iterator<ClickSpark> it = clickSparks.iterator();
            while (it.hasNext()) {
                ClickSpark s = it.next();
                s.update(delta);
                if (s.isDead()) {
                    it.remove();
                } else {
                    int sparkColor = ((int) (s.getAlpha() * 255.0f) << 24) | (s.color & 0x00FFFFFF);
                    context.fill((int) (s.x - s.size / 2.0f), (int) (s.y - s.size / 2.0f), (int) (s.x + s.size / 2.0f), (int) (s.y + s.size / 2.0f), sparkColor);
                }
            }
        }

        private void drawGreetingHeader(DrawContext context, int physW, float offsetY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            String username = mc.getSession() != null ? mc.getSession().getUsername() : "Player";
            String welcomeText = getGreeting() + ", " + username + "!";

            int w = mc.textRenderer.getWidth(welcomeText);
            drawTextSafe(context, mc.textRenderer, welcomeText, (physW - w) / 2, (int) (70 + offsetY), 0xFFE0D8F5, true);

            String title = "DELTA CLIENT";
            int tw = mc.textRenderer.getWidth(title);
            drawTextSafe(context, mc.textRenderer, "§b§l" + title, (physW - tw) / 2, (int) (52 + offsetY), 0xFF00E5FF, true);
        }

        private void drawMainButtons(DrawContext context, double mx, double my, float offsetY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();

            for (MenuButton btn : this.mainButtons) {
                float renderY = btn.y + offsetY;
                boolean hovered = mx >= btn.x && mx <= btn.x + btn.w && my >= renderY && my <= renderY + btn.h;
                float k = getHoverAnim(btn.label, hovered, delta);

                int bgColor = lerpColor(COLOR_BTN_IDLE, COLOR_BTN_HOVER, k);
                int strokeColor = lerpColor(COLOR_BTN_STROKE_IDLE, COLOR_BTN_STROKE_HOV, k);

                ModernRefinedClickGui.drawSmoothRect(context, (int) btn.x, (int) renderY, (int) btn.w, (int) btn.h, bgColor, strokeColor);

                if (k > 0.01f) {
                    int glowAlpha = ((int) (k * 255.0f) << 24) | (COLOR_PURPLE_ACCENT & 0x00FFFFFF);
                    context.fill((int) btn.x + 4, (int) renderY + 1, (int) (btn.x + btn.w - 4), (int) renderY + 3, glowAlpha);
                }

                int textColor = lerpColor(0xFFD8D2F0, 0xFFFFFFFF, k);
                int textW = mc.textRenderer.getWidth(btn.label);
                drawTextSafe(context, mc.textRenderer, btn.label, (int) (btn.x + (btn.w - textW) / 2.0f), (int) (renderY + (btn.h - 8) / 2.0f), textColor, false);
            }
        }

        private void drawPresetButtons(DrawContext context, double mx, double my, float offsetY, float delta) {
            MinecraftClient mc = MinecraftClient.getInstance();

            for (GraphicPresetButton btn : this.presetButtons) {
                float renderY = btn.y + offsetY;
                boolean isSelected = btn.label.equalsIgnoreCase(currentMenuPreset);
                boolean hovered = mx >= btn.x && mx <= btn.x + btn.w && my >= renderY && my <= renderY + btn.h;
                float k = getHoverAnim("preset_" + btn.label, hovered || isSelected, delta);

                int btnBg = isSelected ? 0x887B2CBF : lerpColor(0x331C103B, 0x665A189A, k);
                int btnBorder = isSelected ? COLOR_PURPLE_ACCENT : lerpColor(0x44B14EFF, 0xAA7B2CBF, k);

                ModernRefinedClickGui.drawSmoothRect(context, (int) btn.x, (int) renderY, (int) btn.w, (int) btn.h, btnBg, btnBorder);

                int textColor = isSelected ? 0xFFFFFFFF : lerpColor(0xFFA09AB8, 0xFFFFFFFF, k);
                int tw = mc.textRenderer.getWidth(btn.label);
                drawTextSafe(context, mc.textRenderer, btn.label, (int) (btn.x + (btn.w - tw) / 2.0f), (int) (renderY + (btn.h - 8) / 2.0f), textColor, false);
            }
        }

        private void drawSystemInfoWidget(DrawContext context, int physW, int physH, float offsetY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int fps = mc.getCurrentFps();
            String infoStr = "Delta Visuals 1.0.0 | FPS: " + fps + " | Fabric 1.21.11";

            int strW = mc.textRenderer.getWidth(infoStr);
            float x = physW - strW - 20.0f;
            float y = physH - 26.0f + offsetY;
            drawTextSafe(context, mc.textRenderer, infoStr, (int) x, (int) y, 0x77A09AB8, false);
        }

        private void drawDisclaimer(DrawContext context, int physH, float offsetY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            float disclaimerX = 20.0f;
            float disclaimerY = physH - 62.0f + offsetY;
            int disclaimerColor = 0x77A09AB8;

            drawTextSafe(context, mc.textRenderer, "Delta Client is not affiliated with Mojang or Microsoft Corporation.", (int) disclaimerX, (int) disclaimerY, disclaimerColor, false);
            drawTextSafe(context, mc.textRenderer, "For educational and informational purposes only.", (int) disclaimerX, (int) disclaimerY + 10, disclaimerColor, false);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button != 0) return super.mouseClicked(mx, my, button);

            float offsetY = getOffsetY();
            spawnClickSparks((float) mx, (float) my);

            for (MenuButton btn : this.mainButtons) {
                float renderY = btn.y + offsetY;
                if (mx >= btn.x && mx <= btn.x + btn.w && my >= renderY && my <= renderY + btn.h) {
                    if (btn.action != null) {
                        btn.action.run();
                        ModernRefinedClickGui.playClickSound();
                    }
                    return true;
                }
            }

            for (GraphicPresetButton btn : this.presetButtons) {
                float renderY = btn.y + offsetY;
                if (mx >= btn.x && mx <= btn.x + btn.w && my >= renderY && my <= renderY + btn.h) {
                    if (btn.action != null) {
                        btn.action.run();
                        ModernRefinedClickGui.playClickSound();
                    }
                    return true;
                }
            }

            return super.mouseClicked(mx, my, button);
        }

        private void spawnClickSparks(float x, float y) {
            for (int i = 0; i < 10; i++) {
                float angle = random.nextFloat() * (float) Math.PI * 2.0f;
                float speed = 30.0f + random.nextFloat() * 70.0f;
                float vx = (float) Math.cos(angle) * speed;
                float vy = (float) Math.sin(angle) * speed;
                float size = 2.0f + random.nextFloat() * 2.5f;
                float life = 0.2f + random.nextFloat() * 0.3f;
                int color = random.nextBoolean() ? COLOR_CYAN_ACCENT : COLOR_PURPLE_ACCENT;
                clickSparks.add(new ClickSpark(x, y, vx, vy, size, life, color));
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) return false;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        private static int lerpColor(int c1, int c2, float t) {
            t = MathHelper.clamp(t, 0.0f, 1.0f);
            int a1 = (c1 >> 24) & 0xFF, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
            int a2 = (c2 >> 24) & 0xFF, r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
            int a = (int) (a1 + (a2 - a1) * t);
            int r = (int) (r1 + (r2 - r1) * t);
            int g = (int) (g1 + (g2 - g1) * t);
            int b = (int) (b1 + (b2 - b1) * t);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static class CursorParticle {
            float x, y, vx, vy, size, maxLife, life;
            int color;

            public CursorParticle(float x, float y, float vx, float vy, float size, float maxLife, int color) {
                this.x = x; this.y = y; this.vx = vx; this.vy = vy;
                this.size = size; this.maxLife = maxLife; this.life = maxLife;
                this.color = color;
            }

            public void update(float delta) {
                this.x += this.vx * delta;
                this.y += this.vy * delta;
                this.life -= delta;
            }

            public boolean isDead() { return this.life <= 0; }
            public float getAlpha() { return Math.max(0.0f, this.life / this.maxLife); }
        }

        private static class ClickSpark {
            float x, y, vx, vy, size, maxLife, life;
            int color;

            public ClickSpark(float x, float y, float vx, float vy, float size, float maxLife, int color) {
                this.x = x; this.y = y; this.vx = vx; this.vy = vy;
                this.size = size; this.maxLife = maxLife; this.life = maxLife;
                this.color = color;
            }

            public void update(float delta) {
                this.x += this.vx * delta;
                this.y += this.vy * delta;
                this.life -= delta;
            }

            public boolean isDead() { return this.life <= 0; }
            public float getAlpha() { return Math.max(0.0f, this.life / this.maxLife); }
        }

        private static class BackgroundStar {
            float x, y, size, speed, alpha;

            public BackgroundStar(float x, float y, float size, float speed, float alpha) {
                this.x = x; this.y = y; this.size = size; this.speed = speed; this.alpha = alpha;
            }

            public void update(float delta, int physW, int physH) {
                this.y -= this.speed * delta;
                if (this.y < -10) {
                    this.y = physH + 10;
                    this.x = new Random().nextFloat() * physW;
                }
            }
        }

        private static class MenuButton {
            final String label;
            final float x, y, w, h;
            final Runnable action;

            public MenuButton(String label, float x, float y, float w, float h, Runnable action) {
                this.label = label; this.x = x; this.y = y; this.w = w; this.h = h;
                this.action = action;
            }
        }

        private static class GraphicPresetButton {
            final String label;
            final float x, y, w, h;
            final Runnable action;

            public GraphicPresetButton(String label, float x, float y, float w, float h, Runnable action) {
                this.label = label; this.x = x; this.y = y; this.w = w; this.h = h;
                this.action = action;
            }
        }
    }
}
