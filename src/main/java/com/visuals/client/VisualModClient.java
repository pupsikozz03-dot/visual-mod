package com.visuals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.effect.StatusEffectInstance;
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

// Глобальное отслеживание нажатых клавиш для биндов
private final boolean[] keyStates = new boolean[512];

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

            // Обработка биндов модулей в игре (только когда не открыт экран/чат)
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
            System.out.println("[VisualMod] Using direct GLFW polling for Insert.");
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

public static void playSoundSafely(net.minecraft.sound.SoundEvent sound, float pitch) {
    try {
        MinecraftClient.getInstance().getSoundManager().play(
                PositionedSoundInstance.master(sound, pitch)
        );
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
// КАТЕГОРИИ
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
// НАСТРОЙКИ
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
// БАЗОВЫЙ МОДУЛЬ С БИНДАМИ
// ==========================================
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

// ==========================================
// МОДУЛИ RENDER & VISUALS (БЕЗ КРАШЕЙ)
// ==========================================
public static class AspectRatioModule extends Module {
    public final ModeSetting presets = new ModeSetting("Соотношение", "4:3", List.of("16:9", "16:10", "4:3", "5:4", "1:1", "21:9", "3:2", "Custom"));
    public final SliderSetting customRatio = new SliderSetting("Кастомный Aspect", 1.33, 0.50, 2.40, 0.05, "");
    public final BooleanSetting stretchFov = new BooleanSetting("Растягивать FOV", true);
    public final SliderSetting fovScale = new SliderSetting("Множитель угла", 1.05, 0.7, 1.4, 0.05, "x");
    private Integer originalFov = null;

    public AspectRatioModule() {
        super("AspectRatio", "Изменяет соотношение сторон экрана и угол обзора FOV", Category.RENDER);
        registerSetting(presets);
        registerSetting(customRatio);
        registerSetting(stretchFov);
        registerSetting(fovScale);
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
        if (originalFov == null) originalFov = client.options.getFov().getValue();

        if (stretchFov.get()) {
            float targetRatio = getRatio();
            double scale = fovScale.get();
            int fovModifier = (int) (originalFov * (1.777f / targetRatio) * scale);
            // Строгий безопасный clamp от 30 до 110, чтобы движок не ругался на Illegal Option Value
            client.options.getFov().setValue(MathHelper.clamp(fovModifier, 30, 110));
        }
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null && originalFov != null) {
            mc.options.getFov().setValue(MathHelper.clamp(originalFov, 30, 110));
        }
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

        // Безопасная установка времени суток через reflection без падений
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
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == long.class) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("time") || name.startsWith("method_")) {
                        m.setAccessible(true);
                        m.invoke(world, time);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            // Попытка через WorldProperties
            Method getProps = world.getClass().getMethod("getLevelProperties");
            Object props = getProps.invoke(world);
            for (Method m : props.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == long.class && m.getName().toLowerCase().contains("time")) {
                    m.setAccessible(true);
                    m.invoke(props, time);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}

public static class FullBrightModule extends Module {
    public FullBrightModule() {
        super("FullBright", "Максимальная видимость в пещерах и темных локациях", Category.RENDER);
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!isEnabled() || client.player == null) return;
        // Идеальное ночное зрение без спама в лог и без изменения ванильной гаммы
        try {
            client.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 200, 0, false, false, false));
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

public static class ChinaHatModule extends Module {
    public final ModeSetting palette = new ModeSetting("Цвет", "Indigo", List.of("Indigo", "Rainbow", "Red", "Cyan", "Gold"));
    public final SliderSetting radius = new SliderSetting("Радиус", 0.65, 0.3, 1.2, 0.05, "m");
    public final SliderSetting height = new SliderSetting("Высота", 0.28, 0.1, 0.6, 0.02, "m");

    public ChinaHatModule() {
        super("ChinaHat", "Азиатская коническая шляпа над головой игрока", Category.RENDER);
        registerSetting(palette);
        registerSetting(radius);
        registerSetting(height);
    }
}

public static class ZoomModule extends Module {
    public final SliderSetting zoomLevel = new SliderSetting("Кратность зума", 3.0, 1.5, 6.0, 0.5, "x");
    private Integer baseFov = null;

    public ZoomModule() {
        super("Zoom", "Кинематографическое приближение камеры", Category.RENDER);
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

    public CrosshairModule() {
        super("Crosshair", "Кастомный статический прицел с настройкой размера", Category.RENDER);
        registerSetting(style);
        registerSetting(gap);
        registerSetting(length);
    }
}

// ==========================================
// МОДУЛИ REMOVALS
// ==========================================
public static class NoHurtCamModule extends Module {
    public final SliderSetting shake = new SliderSetting("Сила тряски", 0.0, 0.0, 1.0, 0.05, "%");
    public final ModeSetting mode = new ModeSetting("Режим", "Полное отключение", List.of("Полное отключение", "Мягкий 20%", "Свой"));

    public NoHurtCamModule() {
        super("NoHurtCam", "Отключает дезориентирующую тряску экрана при получении ударов", Category.REMOVALS);
        registerSetting(shake);
        registerSetting(mode);
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!isEnabled() || client.options == null) return;
        double factor = mode.get().equals("Полное отключение") ? 0.0 : (mode.get().equals("Мягкий 20%") ? 0.20 : shake.get());
        try {
            client.options.getDamageTiltStrength().setValue(factor);
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

public static class LowFireModule extends Module {
    public final SliderSetting height = new SliderSetting("Смещение пламени", 0.15, 0.0, 1.0, 0.05, "");
    public final SliderSetting opacity = new SliderSetting("Прозрачность", 0.50, 0.0, 1.0, 0.05, "%");

    public LowFireModule() {
        super("LowFire", "Опускает языки огня от 1-го лица, освобождая обзор", Category.REMOVALS);
        registerSetting(height);
        registerSetting(opacity);
    }
}

public static class AntiBlindnessModule extends Module {
    public final BooleanSetting blindness = new BooleanSetting("Слепота зелья", true);
    public final BooleanSetting darkness = new BooleanSetting("Тьма Вардена", true);
    public final BooleanSetting nausea = new BooleanSetting("Искажение портала", true);

    public AntiBlindnessModule() {
        super("AntiBlindness", "Очищает экран от эффектов слепоты, тьмы и укачивания", Category.REMOVALS);
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

public static class LowShieldModule extends Module {
    public final SliderSetting offsetY = new SliderSetting("Опустить щит Y", 0.35, 0.0, 0.80, 0.05, "");
    public final SliderSetting scale = new SliderSetting("Масштаб щита", 0.70, 0.30, 1.0, 0.05, "x");

    public LowShieldModule() {
        super("LowShield", "Уменьшает и опускает щит во второй руке", Category.REMOVALS);
        registerSetting(offsetY);
        registerSetting(scale);
    }
}

public static class NoRenderModule extends Module {
    public final BooleanSetting totem = new BooleanSetting("Анимация тотема", true);
    public final BooleanSetting explosion = new BooleanSetting("Дым взрывов", true);

    public NoRenderModule() {
        super("NoRender", "Блокирует спам лагающих частиц взрывов и тотемов", Category.REMOVALS);
        registerSetting(totem);
        registerSetting(explosion);
    }
}

// ==========================================
// МОДУЛИ COMBAT & MOVEMENT & MISC
// ==========================================
public static class TriggerBotModule extends Module {
    public final SliderSetting cooldown = new SliderSetting("Кулдаун атаки", 1.0, 0.8, 1.0, 0.02, "%");
    public TriggerBotModule() {
        super("TriggerBot", "Автоматический удар при наведении перекрестия на цель", Category.COMBAT);
        registerSetting(cooldown);
    }
}

public static class HitBoxesModule extends Module {
    public final SliderSetting expand = new SliderSetting("Расширение", 0.30, 0.05, 1.20, 0.05, "m");
    public HitBoxesModule() {
        super("HitBoxes", "Увеличивает объем хитбоксов целей для попаданий", Category.COMBAT);
        registerSetting(expand);
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
}

public static class TapeMouseModule extends Module {
    public final SliderSetting minCps = new SliderSetting("Мин. CPS", 9.0, 4.0, 20.0, 1.0, "");
    public final SliderSetting maxCps = new SliderSetting("Макс. CPS", 13.0, 6.0, 25.0, 1.0, "");
    public TapeMouseModule() {
        super("TapeMouse", "Эмуляция зажатия мыши с реалистичным разбросом CPS", Category.COMBAT);
        registerSetting(minCps);
        registerSetting(maxCps);
    }
}

public static class AutoSprintModule extends Module {
    public final BooleanSetting checkHunger = new BooleanSetting("Проверка сытости", true);
    public AutoSprintModule() {
        super("AutoSprint", "Автоматический непрерывный бег без двойного W", Category.MOVEMENT);
        registerSetting(checkHunger);
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!isEnabled() || client.player == null) return;
        if (checkHunger.get() && client.player.getHungerManager().getFoodLevel() <= 6) return;
        if (client.player.forwardSpeed > 0 && !client.player.isSneaking() && !client.player.horizontalCollision) {
            client.player.setSprinting(true);
        }
    }
}

public static class FastBreakModule extends Module {
    public final SliderSetting speed = new SliderSetting("Множитель скорости", 1.40, 1.0, 2.5, 0.1, "x");
    public FastBreakModule() {
        super("FastBreak", "Увеличивает скорость вашего копания в половину", Category.MOVEMENT);
        registerSetting(speed);
    }
}

public static class InventoryMoveModule extends Module {
    public InventoryMoveModule() {
        super("InventoryMove", "Свободное перемещение и прыжки при открытом инвентаре", Category.MOVEMENT);
    }
}

public static class WaterSpeedModule extends Module {
    public final SliderSetting speed = new SliderSetting("Ускорение в воде", 1.35, 1.0, 2.5, 0.05, "x");
    public WaterSpeedModule() {
        super("WaterSpeed", "Увеличивает маневренность и скорость плавания под водой", Category.MOVEMENT);
        registerSetting(speed);
    }
}

public static class FastPlaceModule extends Module {
    public final SliderSetting delay = new SliderSetting("Задержка ПКМ", 0.0, 0.0, 3.0, 1.0, "t");
    public FastPlaceModule() {
        super("FastPlace", "Убирает стандартную задержку ПКМ при строительстве", Category.MISC);
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
        super("AutoTool", "Автоматически выбирает эффективный инструмент в руку при ударе", Category.MISC);
    }
}

public static class FreeCameraModule extends Module {
    public final SliderSetting flySpeed = new SliderSetting("Скорость камеры", 1.8, 0.5, 5.0, 0.2, "x");
    public FreeCameraModule() {
        super("FreeCamera", "Свободный полет камерой сквозь стены для осмотра базы", Category.MISC);
        registerSetting(flySpeed);
    }
}

// ==========================================
// МОДУЛЬНЫЙ МЕНЕДЖЕР
// ==========================================
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

    public List<Module> getModulesByCategory(Category cat) {
        List<Module> list = new ArrayList<>();
        for (Module m : modules) {
            if (m.getCategory() == cat) list.add(m);
        }
        return list;
    }
}

// ==========================================
// КРАСИВЫЙ CLICKGUI КАК НА РЕФЕРЕНСЕ
// ==========================================
public static class ModernRefinedClickGui extends Screen {
    private final ModuleManager moduleManager;
    private final List<GuiColumn> columns = new ArrayList<>();
    public static String hoveredDescription = "";

    // Прямой опрос состояния мыши для 100% срабатывания
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
        // Мягкое полупрозрачное затемнение заднего плана
        context.fill(0, 0, this.width, this.height, 0x66000000);

        hoveredDescription = "";

        // Обработка кликов мыши (ЛКМ, ПКМ, СКМ)
        handleMouseInput(mouseX, mouseY);

        // Рендер 5 высоких колонок
        for (GuiColumn col : columns) {
            col.render(context, mouseX, mouseY);
        }

        // ВЕРХНЯЯ СТРОКА ОПИСАНИЯ ПО ЦЕНТРУ ЭКРАНА (БЕЗ РАМОК, ЧИСТЫЙ ТЕКСТ КАК НА СКРИНШОТЕ)
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!hoveredDescription.isEmpty()) {
            int textW = mc.textRenderer.getWidth(hoveredDescription);
            int titleX = (this.width - textW) / 2;
            drawTextSafe(context, mc.textRenderer, hoveredDescription, titleX, 15, 0xFFFFFFFF, true);
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

        // ЛКМ: переключение модуля или слайдер
        if (leftDown && !wasLeftPressed) dispatchClick(mouseX, mouseY, 0);

        // ПКМ: настройки модуля
        if (rightDown && !wasRightPressed) dispatchClick(mouseX, mouseY, 1);

        // СКМ (Колёсико): назначение бинда!
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
        // Если какой-то модуль ждет бинда
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
        // Полупрозрачный фон
        context.fill(x + 1, y, x + w - 1, y + h, bg);
        context.fill(x, y + 1, x + 1, y + h - 1, bg);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, bg);

        // Тонкая аккуратная рамка
        if (border != 0) {
            context.fill(x + 1, y, x + w - 1, y + 1, border);
            context.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
            context.fill(x, y + 1, x + 1, y + h - 1, border);
            context.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
        }
    }
}

// ==========================================
// ВЕРТИКАЛЬНАЯ ВЫСОКАЯ ПАНЕЛЬ КАТЕГОРИИ
// ==========================================
public static class GuiColumn {
    private final Category category;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
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

        // Матовое полупрозрачное стекло панели (как на оригинальном фото)
        ModernRefinedClickGui.drawSmoothRect(context, x, y, width, height, 0xCC111116, 0x26FFFFFF);

        // Заголовок категории с иконкой
        String headerText = category.getIcon() + "  " + category.getDisplayName();
        int headerW = mc.textRenderer.getWidth(headerText);
        int titleX = x + (width - headerW) / 2;
        drawTextSafe(context, mc.textRenderer, headerText, titleX, y + 9, 0xFFFFFFFF, true);

        // Тонкая разделительная линия
        context.fill(x + 8, y + 25, x + width - 8, y + 26, 0x1AFFFFFF);

        // Карточки модулей со скроллом
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

    public void mouseReleased(int button) {
        for (GuiModuleCard card : cards) card.mouseReleased(button);
    }

    public void mouseDragged(int mouseX, int mouseY) {
        for (GuiModuleCard card : cards) card.mouseDragged(mouseX, mouseY);
    }
}

// ==========================================
// КАРТОЧКА МОДУЛЯ С БИНДОМ
// ==========================================
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

        // Фон карточки: активная подсвечивается индиго, неактивная - темная
        int bg = module.isEnabled() ? 0xDD3730A3 : (hovered ? 0xDD22222E : 0xB8171720);
        int border = module.isEnabled() ? 0xEE818CF8 : (hovered ? 0x44FFFFFF : 0x1AFFFFFF);

        ModernRefinedClickGui.drawSmoothRect(context, x, y, width, 23, bg, border);

        // Текст названия
        int textColor = module.isEnabled() ? 0xFFFFFFFF : (hovered ? 0xFFE2E8F0 : 0xFF94A3B8);
        drawTextSafe(context, mc.textRenderer, module.getName(), x + 8, y + 7, textColor, false);

        // Отображение бинда (если есть) или статус прослушивания
        String bindText = module.isListeningForBind() ? "§e[...]" : (module.getKeyBind() != GLFW.GLFW_KEY_UNKNOWN ? "§7[" + module.getBindName() + "]" : "");
        if (!bindText.isEmpty()) {
            int bindW = mc.textRenderer.getWidth(bindText);
            drawTextSafe(context, mc.textRenderer, bindText, x + width - bindW - 20, y + 7, 0xFFFFFFFF, false);
        }

        // Иконка настроек "≡"
        if (!widgets.isEmpty()) {
            int iconColor = expanded ? 0xFF818CF8 : (hovered ? 0xFFD1D5DB : 0xFF64748B);
            drawTextSafe(context, mc.textRenderer, "≡", x + width - 14, y + 7, iconColor, false);
        }

        // Выпадающий список настроек
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

        // Клик по основной плашке
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 23) {
            // СКМ (Колёсико мыши): установка бинда!
            if (button == 2) {
                module.setListeningForBind(!module.isListeningForBind());
                return true;
            }

            // Клик по значку настроек "≡" справа
            if (mouseX >= x + width - 18) {
                if (!widgets.isEmpty()) expanded = !expanded;
                return true;
            }

            if (button == 0) {
                // ЛКМ: включить / выключить
                module.toggle();
                return true;
            } else if (button == 1) {
                // ПКМ: раскрыть параметры
                if (!widgets.isEmpty()) expanded = !expanded;
                return true;
            }
        }

        // Клик по настройкам
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
        drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1, false);

        String modeStr = "§8[§f" + setting.get() + "§8]";
        int modeW = mc.textRenderer.getWidth(modeStr);
        drawTextSafe(context, mc.textRenderer, modeStr, x + width - modeW - 2, y + 4, 0xFF818CF8, false);
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
        drawTextSafe(context, mc.textRenderer, setting.getName(), x + 2, y + 4, 0xFFCBD5E1, false);

        int btnX = x + width - 13;
        int col = setting.get() ? 0xFF6366F1 : 0xFF2A2A3C;
        ModernRefinedClickGui.drawSmoothRect(context, btnX, y + 3, 11, 11, col, 0x44FFFFFF);
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
