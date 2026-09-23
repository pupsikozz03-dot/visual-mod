package com.visuals.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

public class VisualModClient implements ClientModInitializer {
    public static final String MOD_ID = "visuals_mod";
    public static VisualModClient INSTANCE;

    private static Method cachedDrawTextString = null;
    private static Method cachedDrawTextText = null;
    private static boolean textRendererInitialized = false;

    private KeyBinding clickGuiKey;
    private final ModuleManager moduleManager = new ModuleManager();
    private boolean wasInsertKeyDown = false;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        clickGuiKey = registerKeyBindingSafely("key.visuals.clickgui", GLFW.GLFW_KEY_INSERT, "category.visuals");
        moduleManager.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean openRequested = false;

            if (clickGuiKey != null && clickGuiKey.wasPressed()) {
                openRequested = true;
            }

            if (client.getWindow() != null && client.getWindow().getHandle() != 0) {
                long handle = client.getWindow().getHandle();
                boolean isDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS;
                if (isDown && !wasInsertKeyDown) {
                    openRequested = true;
                }
                wasInsertKeyDown = isDown;
            }

            if (openRequested && client.currentScreen == null) {
                client.setScreen(new CelestialColumnClickGui(moduleManager));
            }

            if (client.world != null && client.player != null) {
                moduleManager.onTick(client);
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
                System.out.println("[VisualMod] Using GLFW direct polling for Insert key.");
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

    // ==========================================
    // КАТЕГОРИИ ИНТЕРФЕЙСА
    // ==========================================
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

    // ==========================================
    // РАСШИРЕННАЯ СИСТЕМА НАСТРОЕК
    // ==========================================
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
        public BooleanSetting(String name, boolean defaultValue) {
            super(name, defaultValue);
        }
        public void toggle() { this.value = !this.value; }
    }

    public static class SliderSetting extends Setting<Double> {
        private final double min;
        private final double max;
        private final double increment;
        private final String suffix;

        public SliderSetting(String name, double defaultValue, double min, double max, double increment) {
            this(name, defaultValue, min, max, increment, "");
        }

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

    // ==========================================
    // БАЗОВЫЙ МОДУЛЬ
    // ==========================================
    public static abstract class Module {
        private final String name;
        private final String description;
        private final Category category;
        private boolean enabled;
        private final List<Setting<?>> settings = new ArrayList<>();

        public Module(String name, String description, Category category) {
            this.name = name;
            this.description = description;
            this.category = category;
        }

        public void toggle() {
            setEnabled(!this.enabled);
        }

        public void setEnabled(boolean state) {
            if (this.enabled != state) {
                this.enabled = state;
                if (state) onEnable(); else onDisable();
            }
        }

        public void onEnable() {}
        public void onDisable() {}
        public void onTick(MinecraftClient client) {}

        protected void registerSetting(Setting<?> setting) {
            this.settings.add(setting);
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public Category getCategory() { return category; }
        public boolean isEnabled() { return enabled; }
        public List<Setting<?>> getSettings() { return settings; }
    }

    // ==========================================
    // МОДУЛИ: RENDER & VISUALS
    // ==========================================
    public static class AspectRatioModule extends Module {
        public final ModeSetting presets = new ModeSetting("Aspect Ratio", "16:9", List.of("16:9", "16:10", "4:3", "5:4", "1:1", "21:9", "3:2", "Custom"));
        public final SliderSetting customRatio = new SliderSetting("Custom Ratio", 1.77, 0.40, 2.60, 0.05);
        public final BooleanSetting stretchFov = new BooleanSetting("Stretch FOV", true);
        public final SliderSetting fovMultiplier = new SliderSetting("FOV Scale", 1.0, 0.5, 1.8, 0.05, "x");
        public final BooleanSetting handRatio = new BooleanSetting("Aspect Hand", true);
        public final BooleanSetting smoothTransition = new BooleanSetting("Smooth Transition", true);

        private Integer originalFov = null;

        public AspectRatioModule() {
            super("AspectRatio", "Изменяет соотношение сторон дисплея и горизонтальное растяжение FOV", Category.RENDER);
            registerSetting(presets);
            registerSetting(customRatio);
            registerSetting(stretchFov);
            registerSetting(fovMultiplier);
            registerSetting(handRatio);
            registerSetting(smoothTransition);
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

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            if (originalFov == null) {
                originalFov = client.options.getFov().getValue();
            }

            if (stretchFov.get()) {
                float targetRatio = getRatio();
                double scale = fovMultiplier.get();
                int fovModifier = (int) (originalFov * (1.77f / targetRatio) * scale);
                fovModifier = MathHelper.clamp(fovModifier, 30, 130);
                client.options.getFov().setValue(fovModifier);
            }
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null && originalFov != null) {
                mc.options.getFov().setValue(originalFov);
            }
        }
    }

    public static class AmbienceModule extends Module {
        public final ModeSetting timeMode = new ModeSetting("Time", "Sunset", List.of("Day", "Noon", "Sunset", "Night", "Midnight", "Cycle", "Custom"));
        public final SliderSetting customTime = new SliderSetting("Custom Time", 12800, 0, 24000, 500, "t");
        public final SliderSetting cycleSpeed = new SliderSetting("Cycle Speed", 40, 5, 250, 5);
        public final BooleanSetting clearWeather = new BooleanSetting("Clear Sky", true);
        public final BooleanSetting disableThunder = new BooleanSetting("No Thunder", true);
        public final ModeSetting skyColorMode = new ModeSetting("Sky Color", "Vanilla", List.of("Vanilla", "Purple", "Crimson", "Cyberpunk", "Dark"));
        public final SliderSetting cloudHeight = new SliderSetting("Cloud Height", 192, 64, 320, 16);

        private long cycleTicks = 0;

        public AmbienceModule() {
            super("Ambience", "Кастомное визуальное время суток, чистое небо и атмосферный тон", Category.RENDER);
            registerSetting(timeMode);
            registerSetting(customTime);
            registerSetting(cycleSpeed);
            registerSetting(clearWeather);
            registerSetting(disableThunder);
            registerSetting(skyColorMode);
            registerSetting(cloudHeight);
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
            }
            if (disableThunder.get()) {
                client.world.setThunderGradient(0.0f);
            }
        }
    }

    public static class FullBrightModule extends Module {
        public final SliderSetting gamma = new SliderSetting("Brightness", 12.0, 1.0, 20.0, 1.0);
        public final ModeSetting brightMode = new ModeSetting("Mode", "Gamma", List.of("Gamma", "NightVision", "Vibrant"));
        public final BooleanSetting smoothTransition = new BooleanSetting("Smooth Gamma", true);
        private Double previousGamma = null;

        public FullBrightModule() {
            super("FullBright", "Максимальная видимость в пещерах и ночью без необходимости факелов", Category.RENDER);
            registerSetting(gamma);
            registerSetting(brightMode);
            registerSetting(smoothTransition);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            if (previousGamma == null) {
                previousGamma = client.options.getGamma().getValue();
            }
            client.options.getGamma().setValue(gamma.get());
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null && previousGamma != null) {
                mc.options.getGamma().setValue(previousGamma);
            }
        }
    }

    public static class ZoomModule extends Module {
        public final SliderSetting zoomLevel = new SliderSetting("Zoom Power", 3.5, 1.5, 8.0, 0.5, "x");
        public final BooleanSetting smoothZoom = new BooleanSetting("Smooth Motion", true);
        public final BooleanSetting cinematicCamera = new BooleanSetting("Cinematic Pan", true);
        public final BooleanSetting scrollZoom = new BooleanSetting("Mouse Scroll", true);
        private Integer baseFov = null;

        public ZoomModule() {
            super("Zoom", "Кинематографическое плавное приближение камеры", Category.RENDER);
            registerSetting(zoomLevel);
            registerSetting(smoothZoom);
            registerSetting(cinematicCamera);
            registerSetting(scrollZoom);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            if (baseFov == null) {
                baseFov = client.options.getFov().getValue();
            }
            int targetFov = (int) (baseFov / zoomLevel.get());
            client.options.getFov().setValue(MathHelper.clamp(targetFov, 10, 110));
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null && baseFov != null) {
                mc.options.getFov().setValue(baseFov);
            }
        }
    }

    public static class CrosshairModule extends Module {
        public final ModeSetting style = new ModeSetting("Style", "Classic", List.of("Classic", "Dot", "Circle", "Cross", "T-Cross"));
        public final SliderSetting gap = new SliderSetting("Center Gap", 3.0, 0.0, 12.0, 1.0, "px");
        public final SliderSetting length = new SliderSetting("Line Length", 5.0, 1.0, 15.0, 1.0, "px");
        public final SliderSetting thickness = new SliderSetting("Thickness", 1.5, 1.0, 5.0, 0.5, "px");
        public final BooleanSetting dynamicSpread = new BooleanSetting("Dynamic Attack", true);
        public final BooleanSetting rainbow = new BooleanSetting("Rainbow Tint", false);

        public CrosshairModule() {
            super("Crosshair", "Кастомный киберспортивный прицел с точной настройкой геометрии", Category.RENDER);
            registerSetting(style);
            registerSetting(gap);
            registerSetting(length);
            registerSetting(thickness);
            registerSetting(dynamicSpread);
            registerSetting(rainbow);
        }
    }

    public static class ChinaHatModule extends Module {
        public final ModeSetting hatColor = new ModeSetting("Palette", "Indigo", List.of("Indigo", "Rainbow", "Red", "Cyan", "Gold"));
        public final SliderSetting radius = new SliderSetting("Radius", 0.65, 0.3, 1.2, 0.05);
        public final SliderSetting height = new SliderSetting("Height", 0.28, 0.1, 0.6, 0.02);
        public final BooleanSetting firstPerson = new BooleanSetting("Show In 1st Person", false);

        public ChinaHatModule() {
            super("ChinaHat", "Стильная восточная коническая шляпа над головой персонажа", Category.RENDER);
            registerSetting(hatColor);
            registerSetting(radius);
            registerSetting(height);
            registerSetting(firstPerson);
        }
    }

    // ==========================================
    // МОДУЛИ: REMOVALS (PVP ОЧИСТКА)
    // ==========================================
    public static class NoHurtCamModule extends Module {
        public final SliderSetting shake = new SliderSetting("Shake Factor", 0.0, 0.0, 1.0, 0.05);
        public final BooleanSetting removeRedOverlay = new BooleanSetting("No Red Tint", true);
        public final ModeSetting shakeMode = new ModeSetting("Mode", "Total Off", List.of("Total Off", "Subtle 20%", "Custom"));

        public NoHurtCamModule() {
            super("NoHurtCam", "Отключает дезориентирующую тряску экрана и покраснение при ударах", Category.REMOVALS);
            registerSetting(shake);
            registerSetting(shakeMode);
            registerSetting(removeRedOverlay);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.options == null) return;
            double factor = switch (shakeMode.get()) {
                case "Total Off" -> 0.0;
                case "Subtle 20%" -> 0.20;
                default -> shake.get();
            };
            client.options.getDamageTiltStrength().setValue(factor);
        }

        @Override
        public void onDisable() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options != null) {
                mc.options.getDamageTiltStrength().setValue(1.0);
            }
        }
    }

    public static class LowFireModule extends Module {
        public final ModeSetting heightMode = new ModeSetting("Height Mode", "Invisible", List.of("Invisible", "Minimal (15%)", "Low (30%)", "Half (50%)", "Custom"));
        public final SliderSetting customHeight = new SliderSetting("Height Offset", 0.20, 0.0, 1.0, 0.05);
        public final SliderSetting opacity = new SliderSetting("Opacity", 0.50, 0.0, 1.0, 0.05);
        public final BooleanSetting blueFire = new BooleanSetting("Soul Fire Blue", false);

        public LowFireModule() {
            super("LowFire", "Опускает или скрывает языки пламени при горении от 1 лица", Category.REMOVALS);
            registerSetting(heightMode);
            registerSetting(customHeight);
            registerSetting(opacity);
            registerSetting(blueFire);
        }
    }

    public static class AntiBlindnessModule extends Module {
        public final BooleanSetting removeBlindness = new BooleanSetting("Potion Blindness", true);
        public final BooleanSetting removeDarkness = new BooleanSetting("Warden Darkness", true);
        public final BooleanSetting removeNausea = new BooleanSetting("Portal Wobble", true);
        public final BooleanSetting clearLavaFog = new BooleanSetting("Clear Lava Fog", true);
        public final BooleanSetting clearWaterFog = new BooleanSetting("Clear Water Fog", true);
        public final BooleanSetting removePumpkin = new BooleanSetting("No Pumpkin Overlay", true);

        public AntiBlindnessModule() {
            super("AntiBlindness", "Очищает экран от ослепления, тьмы Вардена, лавового тумана и тыквы", Category.REMOVALS);
            registerSetting(removeBlindness);
            registerSetting(removeDarkness);
            registerSetting(removeNausea);
            registerSetting(clearLavaFog);
            registerSetting(clearWaterFog);
            registerSetting(removePumpkin);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (removeBlindness.get() && client.player.hasStatusEffect(StatusEffects.BLINDNESS)) {
                client.player.removeStatusEffect(StatusEffects.BLINDNESS);
            }
            if (removeDarkness.get() && client.player.hasStatusEffect(StatusEffects.DARKNESS)) {
                client.player.removeStatusEffect(StatusEffects.DARKNESS);
            }
            if (removeNausea.get() && client.player.hasStatusEffect(StatusEffects.NAUSEA)) {
                client.player.removeStatusEffect(StatusEffects.NAUSEA);
            }
        }
    }

    public static class LowShieldModule extends Module {
        public final SliderSetting offsetY = new SliderSetting("Offset Y", 0.35, 0.0, 0.80, 0.05);
        public final SliderSetting offsetX = new SliderSetting("Offset X", 0.0, -0.4, 0.4, 0.05);
        public final SliderSetting scale = new SliderSetting("Shield Scale", 0.65, 0.25, 1.0, 0.05);
        public final BooleanSetting hideWhenHitting = new BooleanSetting("Hide On Swing", true);
        public final BooleanSetting applyToMainHand = new BooleanSetting("Main Hand Also", false);

        public LowShieldModule() {
            super("LowShield", "Опускает и уменьшает щит в руке, освобождая центр экрана", Category.REMOVALS);
            registerSetting(offsetY);
            registerSetting(offsetX);
            registerSetting(scale);
            registerSetting(hideWhenHitting);
            registerSetting(applyToMainHand);
        }
    }

    public static class NoRenderModule extends Module {
        public final BooleanSetting totemAnimation = new BooleanSetting("Totem Animation", true);
        public final BooleanSetting explosions = new BooleanSetting("Explosion Smoke", true);
        public final BooleanSetting firework = new BooleanSetting("Firework Particles", true);
        public final BooleanSetting fallingBlocks = new BooleanSetting("Falling Sand/Anvil", false);
        public final BooleanSetting weatherRain = new BooleanSetting("Rain / Snow Particles", true);

        public NoRenderModule() {
            super("NoRender", "Блокирует спам лагающих частиц взрывов, тотемов и фейерверков", Category.REMOVALS);
            registerSetting(totemAnimation);
            registerSetting(explosions);
            registerSetting(firework);
            registerSetting(fallingBlocks);
            registerSetting(weatherRain);
        }
    }

    // ==========================================
    // МОДУЛИ: COMBAT
    // ==========================================
    public static class TriggerBotModule extends Module {
        public final SliderSetting cooldown = new SliderSetting("Attack Cooldown", 1.0, 0.8, 1.0, 0.02, "%");
        public final SliderSetting delayMs = new SliderSetting("Hit Delay", 25.0, 0.0, 200.0, 10.0, "ms");
        public final BooleanSetting playersOnly = new BooleanSetting("Players Only", true);
        public final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only (Sword/Axe)", true);
        public final BooleanSetting critOnly = new BooleanSetting("Crit Only (Falling)", false);
        public final BooleanSetting checkShield = new BooleanSetting("Check Shield", true);

        public TriggerBotModule() {
            super("TriggerBot", "Автоматический выверенный удар при наведении прицела на противника", Category.COMBAT);
            registerSetting(cooldown);
            registerSetting(delayMs);
            registerSetting(playersOnly);
            registerSetting(weaponOnly);
            registerSetting(critOnly);
            registerSetting(checkShield);
        }
    }

    public static class HitBoxesModule extends Module {
        public final SliderSetting expand = new SliderSetting("Expand Size", 0.25, 0.05, 1.20, 0.05, "m");
        public final SliderSetting expandY = new SliderSetting("Expand Height", 0.0, 0.0, 0.60, 0.05, "m");
        public final BooleanSetting playersOnly = new BooleanSetting("Players Only", true);
        public final BooleanSetting showBox = new BooleanSetting("Render Outline", true);
        public final ModeSetting boxColor = new ModeSetting("Box Color", "Indigo", List.of("Indigo", "Crimson", "Green", "White"));

        public HitBoxesModule() {
            super("HitBoxes", "Расширяет хитбоксы целей для легкого попадания в динамичном PvP", Category.COMBAT);
            registerSetting(expand);
            registerSetting(expandY);
            registerSetting(playersOnly);
            registerSetting(showBox);
            registerSetting(boxColor);
        }
    }

    public static class VelocityModule extends Module {
        public final SliderSetting horizontal = new SliderSetting("Horizontal", 0.0, 0.0, 1.0, 0.05, "%");
        public final SliderSetting vertical = new SliderSetting("Vertical", 0.0, 0.0, 1.0, 0.05, "%");
        public final SliderSetting chance = new SliderSetting("Chance", 100.0, 20.0, 100.0, 5.0, "%");
        public final BooleanSetting onlyMoving = new BooleanSetting("Only While Moving", false);
        public final BooleanSetting waterCheck = new BooleanSetting("Water Check", true);

        public VelocityModule() {
            super("Velocity", "Снижает или полностью убирает отдачу от ударов, луков и взрывов", Category.COMBAT);
            registerSetting(horizontal);
            registerSetting(vertical);
            registerSetting(chance);
            registerSetting(onlyMoving);
            registerSetting(waterCheck);
        }
    }

    public static class AutoClickerModule extends Module {
        public final SliderSetting minCps = new SliderSetting("Min CPS", 9.0, 4.0, 20.0, 1.0);
        public final SliderSetting maxCps = new SliderSetting("Max CPS", 13.0, 6.0, 25.0, 1.0);
        public final SliderSetting jitter = new SliderSetting("Jitter Factor", 0.0, 0.0, 2.0, 0.2);
        public final BooleanSetting swordOnly = new BooleanSetting("Hold Weapon Only", true);
        public final BooleanSetting breakBlocks = new BooleanSetting("Break Blocks Check", true);

        public AutoClickerModule() {
            super("TapeMouse", "Эмуляция быстрого кликанья мыши с реалистичным разбросом CPS", Category.COMBAT);
            registerSetting(minCps);
            registerSetting(maxCps);
            registerSetting(jitter);
            registerSetting(swordOnly);
            registerSetting(breakBlocks);
        }
    }

    // ==========================================
    // МОДУЛИ: MOVEMENT
    // ==========================================
    public static class AutoSprintModule extends Module {
        public final ModeSetting mode = new ModeSetting("Sprint Mode", "Vanilla", List.of("Vanilla", "Omni Sprint", "KeepSprint"));
        public final BooleanSetting stopInWater = new BooleanSetting("Water Check", false);
        public final BooleanSetting checkHunger = new BooleanSetting("Hunger Check", true);

        public AutoSprintModule() {
            super("AutoSprint", "Автоматический непрерывный бег без двойного нажатия W", Category.MOVEMENT);
            registerSetting(mode);
            registerSetting(stopInWater);
            registerSetting(checkHunger);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.player == null) return;
            if (checkHunger.get() && client.player.getHungerManager().getFoodLevel() <= 6) return;
            if (stopInWater.get() && client.player.isTouchingWater()) return;

            if (client.player.forwardSpeed > 0 && !client.player.isSneaking() && !client.player.horizontalCollision) {
                client.player.setSprinting(true);
            }
        }
    }

    public static class FastBreakModule extends Module {
        public final SliderSetting speedMultiplier = new SliderSetting("Speed Multiplier", 1.40, 1.0, 3.0, 0.1, "x");
        public final BooleanSetting instantCreative = new BooleanSetting("Instant Creative", true);
        public final BooleanSetting noDelay = new BooleanSetting("Zero Hit Delay", true);

        public FastBreakModule() {
            super("FastBreak", "Ускоряет разрушение блоков и убирает паузу между ломанием", Category.MOVEMENT);
            registerSetting(speedMultiplier);
            registerSetting(instantCreative);
            registerSetting(noDelay);
        }
    }

    public static class InventoryMoveModule extends Module {
        public final BooleanSetting allowSprint = new BooleanSetting("Allow Sprint", true);
        public final BooleanSetting allowJump = new BooleanSetting("Allow Jump", true);
        public final BooleanSetting allowSneak = new BooleanSetting("Allow Sneak", true);

        public InventoryMoveModule() {
            super("InventoryMove", "Позволяет свободно передвигаться и прыгать во время открытого инвентаря", Category.MOVEMENT);
            registerSetting(allowSprint);
            registerSetting(allowJump);
            registerSetting(allowSneak);
        }
    }

    public static class WaterSpeedModule extends Module {
        public final SliderSetting speedBoost = new SliderSetting("Speed Factor", 1.30, 1.0, 2.5, 0.05, "x");
        public final BooleanSetting upwardBoost = new BooleanSetting("Upward Boost", true);
        public final BooleanSetting dolphinGrace = new BooleanSetting("Dolphin Grace Sim", false);

        public WaterSpeedModule() {
            super("WaterSpeed", "Увеличивает скорость плавания и маневренность под водой", Category.MOVEMENT);
            registerSetting(speedBoost);
            registerSetting(upwardBoost);
            registerSetting(dolphinGrace);
        }
    }

    // ==========================================
    // МОДУЛИ: MISC
    // ==========================================
    public static class FastPlaceModule extends Module {
        public final SliderSetting delay = new SliderSetting("Delay Ticks", 0.0, 0.0, 3.0, 1.0, "t");
        public final BooleanSetting blocksOnly = new BooleanSetting("Blocks Only", true);
        public final BooleanSetting projectiles = new BooleanSetting("Fast Pearls/Snowballs", true);

        public FastPlaceModule() {
            super("FastPlace", "Убирает 4-тиковую задержку ПКМ при строительстве и бросках", Category.MISC);
            registerSetting(delay);
            registerSetting(blocksOnly);
            registerSetting(projectiles);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled()) return;
            try {
                for (Field f : MinecraftClient.class.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        if (f.getName().equals("itemUseCooldown") || f.getName().equals("field_1752")) {
                            f.setInt(client, delay.get().intValue());
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static class AutoToolModule extends Module {
        public final BooleanSetting switchBack = new BooleanSetting("Switch Back", true);
        public final BooleanSetting swordForWeb = new BooleanSetting("Sword For Cobweb", true);
        public final BooleanSetting preferSilkTouch = new BooleanSetting("Prefer Silk Touch", false);

        public AutoToolModule() {
            super("AutoTool", "Автоматически выбирает наиболее эффективный инструмент при копании", Category.MISC);
            registerSetting(switchBack);
            registerSetting(swordForWeb);
            registerSetting(preferSilkTouch);
        }
    }

    public static class FreeCameraModule extends Module {
        public final SliderSetting flySpeed = new SliderSetting("Camera Speed", 1.8, 0.5, 5.0, 0.2, "x");
        public final BooleanSetting freezePlayer = new BooleanSetting("Freeze Body", true);
        public final BooleanSetting showOriginalBody = new BooleanSetting("Show Ghost Body", true);

        public FreeCameraModule() {
            super("FreeCamera", "Свободный полет камерой сквозь стены для осмотра базы или шахт", Category.MISC);
            registerSetting(flySpeed);
            registerSetting(freezePlayer);
            registerSetting(showOriginalBody);
        }
    }

    // ==========================================
    // МЕНЕДЖЕР МОДУЛЕЙ
    // ==========================================
    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void init() {
            // Combat
            modules.add(new TriggerBotModule());
            modules.add(new HitBoxesModule());
            modules.add(new VelocityModule());
            modules.add(new AutoClickerModule());

            // Movement
            modules.add(new AutoSprintModule());
            modules.add(new FastBreakModule());
            modules.add(new InventoryMoveModule());
            modules.add(new WaterSpeedModule());

            // Render
            modules.add(new AspectRatioModule());
            modules.add(new AmbienceModule());
            modules.add(new FullBrightModule());
            modules.add(new ZoomModule());
            modules.add(new CrosshairModule());
            modules.add(new ChinaHatModule());

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
                    m.onTick(client);
                }
            }
        }

        public List<Module> getModulesByCategory(Category cat) {
            List<Module> list = new ArrayList<>();
            for (Module m : modules) {
                if (m.getCategory() == cat) list.add(m);
            }
            return list;
        }
    }

    // ==========================================
    // CLICKGUI С ПОДДЕРЖКОЙ ВЕРТИКАЛЬНОГО СКРОЛЛА
    // ==========================================
    public static class CelestialColumnClickGui extends Screen {
        private final ModuleManager moduleManager;
        private final List<GuiColumn> columns = new ArrayList<>();
        private static String hoveredDescription = "";

        public CelestialColumnClickGui(ModuleManager moduleManager) {
            super(Text.literal("Celestial ClickGUI"));
            this.moduleManager = moduleManager;
        }

        @Override
        protected void init() {
            columns.clear();
            Category[] categories = Category.values();

            int colWidth = 148;
            int gap = 8;
            int totalWidth = (categories.length * colWidth) + ((categories.length - 1) * gap);
            int startX = Math.max(10, (this.width - totalWidth) / 2);
            int startY = 46;

            for (int i = 0; i < categories.length; i++) {
                Category cat = categories[i];
                int x = startX + i * (colWidth + gap);
                columns.add(new GuiColumn(cat, moduleManager.getModulesByCategory(cat), x, startY, colWidth, this.height));
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Мягкое затемнение фона
            context.fill(0, 0, this.width, this.height, 0x880A0A0F);

            hoveredDescription = "";

            // Рендер колонок
            for (GuiColumn col : columns) {
                col.render(context, mouseX, mouseY);
            }

            // ВЕРХНЯЯ СТРОКА ОПИСАНИЯ
            MinecraftClient mc = MinecraftClient.getInstance();
            String descText = hoveredDescription.isEmpty()
                    ? "Delta Visuals • ЛКМ: Вкл/Выкл | ПКМ или [≡]: Настройки | Колесико: Скролл колонок"
                    : hoveredDescription;

            int textWidth = mc.textRenderer.getWidth(descText);
            int centerX = this.width / 2;
            int badgeY = 16;

            drawGlassPlate(context, centerX - (textWidth / 2) - 14, badgeY - 5, textWidth + 28, 20, 0xEE111118, 0x336366F1);
            drawTextSafe(context, mc.textRenderer, descText, centerX - (textWidth / 2), badgeY + 1, 0xFFFFFFFF, true);

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            for (GuiColumn col : columns) {
                if (col.isMouseOver((int) mouseX, (int) mouseY)) {
                    col.handleScroll(verticalAmount);
                    return true;
                }
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (GuiColumn col : columns) {
                if (col.mouseClicked((int) mouseX, (int) mouseY, button)) {
                    playClickSound();
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (GuiColumn col : columns) {
                col.mouseReleased(button);
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            for (GuiColumn col : columns) {
                col.mouseDragged((int) mouseX, (int) mouseY);
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        public static void playClickSound() {
            try {
                MinecraftClient.getInstance().getSoundManager().play(
                        PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f)
                );
            } catch (Throwable ignored) {}
        }

        public static void drawGlassPlate(DrawContext context, int x, int y, int w, int h, int bg, int border) {
            context.fill(x, y, x + w, y + h, bg);
            context.fill(x, y, x + w, y + 1, border);
            context.fill(x, y + h - 1, x + w, y + h, border);
            context.fill(x, y, x + 1, y + h, border);
            context.fill(x + w - 1, y, x + w, y + h, border);
        }
    }

    // ==========================================
    // ПАНЕЛЬ-КОЛОНКА С ИНТЕЛЛЕКТУАЛЬНЫМ СКРОЛЛОМ
    // ==========================================
    public static class GuiColumn {
        private final Category category;
        private final int x;
        private final int y;
        private final int width;
        private final int screenHeight;
        private int scrollY = 0;
        private final List<GuiModuleCard> cards = new ArrayList<>();

        public GuiColumn(Category category, List<Module> modules, int x, int y, int width, int screenHeight) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.width = width;
            this.screenHeight = screenHeight;

            for (Module m : modules) {
                cards.add(new GuiModuleCard(m, x + 5, width - 10));
            }
        }

        public int getTotalContentHeight() {
            int h = 34;
            for (GuiModuleCard card : cards) {
                h += card.getTotalHeight() + 3;
            }
            return h + 6;
        }

        public int getMaxVisibleHeight() {
            return screenHeight - y - 16;
        }

        public boolean isMouseOver(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + Math.min(getTotalContentHeight(), getMaxVisibleHeight());
        }

        public void handleScroll(double amount) {
            int maxScroll = Math.max(0, getTotalContentHeight() - getMaxVisibleHeight());
            scrollY -= (int) (amount * 22);
            scrollY = MathHelper.clamp(scrollY, 0, maxScroll);
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();

            int renderHeight = Math.min(getTotalContentHeight(), getMaxVisibleHeight());
            CelestialColumnClickGui.drawGlassPlate(context, x, y, width, renderHeight, 0xDD111116, 0x22FFFFFF);

            // Шапка колонки
            String headerText = category.getIcon() + "  " + category.getDisplayName();
            int headerWidth = mc.textRenderer.getWidth(headerText);
            int titleX = x + (width - headerWidth) / 2;

            drawTextSafe(context, mc.textRenderer, headerText, titleX, y + 10, 0xFFFFFFFF, true);
            context.fill(x + 8, y + 26, x + width - 8, y + 27, 0x1FFFFFFF);

            // Отрисовка карточек с учетом скролла
            int currentY = y + 32 - scrollY;
            int bottomClip = y + renderHeight - 4;

            for (GuiModuleCard card : cards) {
                int cardH = card.getTotalHeight();
                if (currentY + cardH >= y + 30 && currentY <= bottomClip) {
                    card.setY(currentY);
                    card.render(context, mouseX, mouseY);
                } else {
                    card.setY(-9999);
                }
                currentY += cardH + 3;
            }
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            int renderHeight = Math.min(getTotalContentHeight(), getMaxVisibleHeight());
            if (mouseY < y + 30 || mouseY > y + renderHeight) return false;

            for (GuiModuleCard card : cards) {
                if (card.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
            return false;
        }

        public void mouseReleased(int button) {
            for (GuiModuleCard card : cards) card.mouseReleased(button);
        }

        public void mouseDragged(int mouseX, int mouseY) {
            for (GuiModuleCard card : cards) card.mouseDragged(mouseX, mouseY);
        }
    }

    // ==========================================
    // КАРТОЧКА МОДУЛЯ (АККОРДЕОН НАСТРОЕК)
    // ==========================================
    public static class GuiModuleCard {
        private final Module module;
        private final int x;
        private int y;
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
            int h = 22;
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
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 22;

            if (hovered) {
                CelestialColumnClickGui.hoveredDescription = module.getDescription();
            }

            int bg = module.isEnabled() ? 0xFF28243C : (hovered ? 0xFF20202A : 0xAA16161E);
            int border = module.isEnabled() ? 0x996366F1 : (hovered ? 0x44FFFFFF : 0x14FFFFFF);

            CelestialColumnClickGui.drawGlassPlate(context, x, y, width, getTotalHeight(), bg, border);

            int textColor = module.isEnabled() ? 0xFFFFFFFF : (hovered ? 0xFFD1D5DB : 0xFF888899);
            drawTextSafe(context, mc.textRenderer, module.getName(), x + 8, y + 6, textColor, false);

            if (!widgets.isEmpty()) {
                int iconColor = expanded ? 0xFF818CF8 : 0xFF555566;
                drawTextSafe(context, mc.textRenderer, "≡", x + width - 15, y + 6, iconColor, false);
            }

            if (expanded) {
                int widgetY = y + 24;
                context.fill(x + 5, y + 22, x + width - 5, y + 23, 0x1FFFFFFF);
                for (GuiSettingWidget w : widgets) {
                    w.render(context, x + 6, widgetY, width - 12, mouseX, mouseY);
                    widgetY += w.getHeight() + 3;
                }
            }
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (y < -500) return false;

            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 22) {
                if (button == 0) {
                    module.toggle();
                    return true;
                } else if (button == 1) {
                    if (!widgets.isEmpty()) {
                        expanded = !expanded;
                    }
                    return true;
                }
            }

            if (expanded) {
                int widgetY = y + 24;
                for (GuiSettingWidget w : widgets) {
                    if (w.mouseClicked(x + 6, widgetY, width - 12, mouseX, mouseY, button)) {
                        return true;
                    }
                    widgetY += w.getHeight() + 3;
                }
            }
            return false;
        }

        public void mouseReleased(int button) {
            for (GuiSettingWidget w : widgets) w.mouseReleased(button);
        }

        public void mouseDragged(int mouseX, int mouseY) {
            for (GuiSettingWidget w : widgets) w.mouseDragged(mouseX, mouseY);
        }
    }

    // ==========================================
    // ВИДЖЕТЫ НАСТРОЕК
    // ==========================================
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

        @Override
        public int getHeight() { return 21; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            this.lastX = x;
            this.lastW = width;
            MinecraftClient mc = MinecraftClient.getInstance();

            String text = String.format("%s: §7%.2f%s", setting.getName(), setting.get(), setting.getSuffix());
            drawTextSafe(context, mc.textRenderer, text, x + 2, y + 2, 0xFFCCCCCC, false);

            int barY = y + 13;
            context.fill(x, barY, x + width, barY + 4, 0xFF22222E);

            double pct = (setting.get() - setting.getMin()) / (setting.getMax() - setting.getMin());
            int fillW = (int) (width * MathHelper.clamp(pct, 0.0, 1.0));
            context.fill(x, barY, x + fillW, barY + 4, 0xFF6366F1);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y + 9 && mouseY <= y + 19) {
                this.sliding = true;
                update(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int button) { this.sliding = false; }

        @Override
        public void mouseDragged(int mouseX, int mouseY) {
            if (sliding) update(mouseX);
        }

        private void update(int mouseX) {
            double pct = (double) (mouseX - lastX) / (double) lastW;
            double val = setting.getMin() + (setting.getMax() - setting.getMin()) * pct;
            setting.setValueClamped(val);
        }
    }

    public static class GuiModeWidget implements GuiSettingWidget {
        private final ModeSetting setting;

        public GuiModeWidget(ModeSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 17; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 3, 0xFFAAAAAA, false);

            String modeStr = "§8[§f" + setting.get() + "§8]";
            int modeW = mc.textRenderer.getWidth(modeStr);
            drawTextSafe(context, mc.textRenderer, modeStr, x + width - modeW - 2, y + 3, 0xFF818CF8, false);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 17) {
                setting.cycle();
                return true;
            }
            return false;
        }
    }

    public static class GuiBooleanWidget implements GuiSettingWidget {
        private final BooleanSetting setting;

        public GuiBooleanWidget(BooleanSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 17; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 3, 0xFFAAAAAA, false);

            int btnX = x + width - 13;
            int col = setting.get() ? 0xFF6366F1 : 0xFF2A2A38;
            CelestialColumnClickGui.drawGlassPlate(context, btnX, y + 2, 12, 12, col, 0x44FFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 17) {
                setting.toggle();
                return true;
            }
            return false;
        }
    }
}
