package com.visuals.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Главный клиентский класс мода Visuals для Minecraft 1.21.x Fabric.
 * Полностью совместим с 1.21.0 - 1.21.11+.
 */
public class VisualModClient implements ClientModInitializer {
    public static final String MOD_ID = "visuals_mod";
    public static VisualModClient INSTANCE;

    // Кэш рефлексии для безопасной отрисовки текста на 1.21.0 - 1.21.11+
    private static Method cachedDrawTextString = null;
    private static Method cachedDrawTextText = null;
    private static boolean textRendererInitialized = false;

    private KeyBinding clickGuiKey;
    private final ModuleManager moduleManager = new ModuleManager();
    private boolean wasInsertKeyDown = false;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        // Безопасное создание KeyBinding с поддержкой 1.21.11 (KeyBinding.Category) и более ранних 1.21.x
        clickGuiKey = createKeyBindingSafely("key.visuals.clickgui", GLFW.GLFW_KEY_INSERT);
        if (clickGuiKey != null) {
            try {
                KeyBindingHelper.registerKeyBinding(clickGuiKey);
            } catch (Throwable ignored) {
                // Если Fabric API отклонит бинд, будет работать прямой GLFW-перехват ниже
            }
        }

        // Инициализация модулей
        moduleManager.init();

        // Подписка на клиентские тики для открытия GUI и логики
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean openRequested = false;

            // 1. Проверка через KeyBinding (если успешно зарегистрирован)
            if (clickGuiKey != null && clickGuiKey.wasPressed()) {
                openRequested = true;
            }

            // 2. Прямой опрос GLFW Insert на случай несовместимости KeyBinding на 1.21.11
            if (client.getWindow() != null && client.getWindow().getHandle() != 0) {
                long handle = client.getWindow().getHandle();
                boolean isDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS;
                if (isDown && !wasInsertKeyDown) {
                    openRequested = true;
                }
                wasInsertKeyDown = isDown;
            }

            if (openRequested && client.currentScreen == null) {
                client.setScreen(new ClickGuiScreen(moduleManager));
            }

            if (client.world != null && client.player != null) {
                moduleManager.onTick(client);
            }
        });
    }

    /**
     * Создает KeyBinding с учетом изменений сигнатуры конструктора в Minecraft 1.21.9 - 1.21.11
     */
    private KeyBinding createKeyBindingSafely(String translationKey, int defaultKey) {
        try {
            for (Constructor<?> ctor : KeyBinding.class.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                
                // Конструктор вида (String, int, Category/String)
                if (params.length == 3 && params[0] == String.class && params[1] == int.class) {
                    if (params[2] == String.class) {
                        return (KeyBinding) ctor.newInstance(translationKey, defaultKey, "category.visuals");
                    } else {
                        Object category = resolveCategoryInstance(params[2]);
                        return (KeyBinding) ctor.newInstance(translationKey, defaultKey, category);
                    }
                }

                // Конструктор вида (String, InputUtil.Type, int, Category/String)
                if (params.length == 4 && params[0] == String.class && params[2] == int.class) {
                    if (params[3] == String.class) {
                        return (KeyBinding) ctor.newInstance(translationKey, InputUtil.Type.KEYSYM, defaultKey, "category.visuals");
                    } else {
                        Object category = resolveCategoryInstance(params[3]);
                        return (KeyBinding) ctor.newInstance(translationKey, InputUtil.Type.KEYSYM, defaultKey, category);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[VisualsMod] KeyBinding registration fallback to GLFW: " + t.getMessage());
        }
        return null;
    }

    private Object resolveCategoryInstance(Class<?> categoryClass) {
        try {
            // В 1.21.11 класс KeyBinding.Category содержит константы MISC, MOVEMENT и т.д.
            for (Field field : categoryClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && categoryClass.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object cat = field.get(null);
                    if (cat != null) return cat;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    /**
     * Безопасная отрисовка текста, совместимая со всеми версиями 1.21.x.
     * В 1.21.11 метод DrawContext.drawText изменил возвращаемое значение с int на void,
     * что вызывает NoSuchMethodError при прямом вызове. Динамический вызов решает проблему.
     */
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
                // Сигнатура: (TextRenderer, String/Text, int, int, int, boolean)
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
        } catch (Throwable t) {
            System.out.println("[VisualsMod] Failed to resolve drawText safely: " + t.getMessage());
        }
    }

    public enum Category {
        VISUAL("Visuals"),
        RENDER("Render"),
        WORLD("World");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static abstract class Setting<T> {
        protected String name;
        protected T value;

        public Setting(String name, T defaultValue) {
            this.name = name;
            this.value = defaultValue;
        }

        public String getName() {
            return name;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }

    public static class BooleanSetting extends Setting<Boolean> {
        public BooleanSetting(String name, boolean defaultValue) {
            super(name, defaultValue);
        }

        public void toggle() {
            this.value = !this.value;
        }
    }

    public static class NumberSetting extends Setting<Double> {
        private final double min;
        private final double max;
        private final double increment;

        public NumberSetting(String name, double defaultValue, double min, double max, double increment) {
            super(name, defaultValue);
            this.min = min;
            this.max = max;
            this.increment = increment;
        }

        public double getMin() {
            return min;
        }

        public double getMax() {
            return max;
        }

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
            this.index = modes.indexOf(defaultMode);
            if (this.index == -1) this.index = 0;
        }

        public void cycle() {
            index = (index + 1) % modes.size();
            this.value = modes.get(index);
        }

        public List<String> getModes() {
            return modes;
        }
    }

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
            this.enabled = !this.enabled;
            if (this.enabled) {
                onEnable();
            } else {
                onDisable();
            }
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

    /**
     * Модуль Aspect Ratio: позволяет изменять соотношение сторон экрана.
     */
    public static class AspectRatioModule extends Module {
        public final NumberSetting ratio = new NumberSetting("Ratio", 1.77, 0.50, 2.50, 0.05);
        public final BooleanSetting custom = new BooleanSetting("Custom Aspect", true);
        public final ModeSetting presets = new ModeSetting("Presets", "Custom", List.of("Custom", "4:3", "16:9", "1:1", "21:9", "5:4"));

        public AspectRatioModule() {
            super("Aspect Ratio", "Changes the camera projection aspect ratio", Category.RENDER);
            registerSetting(presets);
            registerSetting(ratio);
            registerSetting(custom);
        }

        public float getAspectRatio(float defaultAspect) {
            if (!isEnabled()) return defaultAspect;

            return switch (presets.get()) {
                case "4:3" -> 4.0f / 3.0f;
                case "16:9" -> 16.0f / 9.0f;
                case "1:1" -> 1.0f;
                case "21:9" -> 21.0f / 9.0f;
                case "5:4" -> 5.0f / 4.0f;
                default -> ratio.get().floatValue();
            };
        }

        public Matrix4f applyProjection(Matrix4f matrix, float fov, float defaultAspect, float nearPlane, float farPlane) {
            if (!isEnabled()) return matrix;
            float newAspect = getAspectRatio(defaultAspect);
            matrix.identity();
            return matrix.perspective((float) Math.toRadians(fov), newAspect, nearPlane, farPlane);
        }
    }

    /**
     * Модуль Ambience: управление визуальной атмосферой мира.
     */
    public static class AmbienceModule extends Module {
        public final ModeSetting timeMode = new ModeSetting("Time", "Sunset", List.of("Day", "Sunset", "Night", "Custom", "Cycle"));
        public final NumberSetting customTime = new NumberSetting("Custom Time", 18000, 0, 24000, 500);
        public final BooleanSetting customFog = new BooleanSetting("Custom Fog", true);
        public final NumberSetting fogRed = new NumberSetting("Fog Red", 0.6, 0.0, 1.0, 0.05);
        public final NumberSetting fogGreen = new NumberSetting("Fog Green", 0.3, 0.0, 1.0, 0.05);
        public final NumberSetting fogBlue = new NumberSetting("Fog Blue", 0.8, 0.0, 1.0, 0.05);

        private long cycleTicks = 0;

        public AmbienceModule() {
            super("Ambience", "Customizes the sky, time of day, and fog atmosphere", Category.WORLD);
            registerSetting(timeMode);
            registerSetting(customTime);
            registerSetting(customFog);
            registerSetting(fogRed);
            registerSetting(fogGreen);
            registerSetting(fogBlue);
        }

        @Override
        public void onTick(MinecraftClient client) {
            if (!isEnabled() || client.world == null) return;

            long targetTime = switch (timeMode.get()) {
                case "Day" -> 1000L;
                case "Sunset" -> 12800L;
                case "Night" -> 18000L;
                case "Cycle" -> {
                    cycleTicks = (cycleTicks + 30) % 24000;
                    yield cycleTicks;
                }
                default -> customTime.get().longValue();
            };

            client.world.setTimeOfDay(targetTime);
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void init() {
            modules.add(new AspectRatioModule());
            modules.add(new AmbienceModule());
        }

        public void onTick(MinecraftClient client) {
            for (Module mod : modules) {
                if (mod.isEnabled()) {
                    mod.onTick(client);
                }
            }
        }

        public List<Module> getModules() {
            return modules;
        }

        public List<Module> getModulesByCategory(Category category) {
            List<Module> list = new ArrayList<>();
            for (Module m : modules) {
                if (m.getCategory() == category) {
                    list.add(m);
                }
            }
            return list;
        }

        @SuppressWarnings("unchecked")
        public <T extends Module> T getModule(Class<T> clazz) {
            for (Module m : modules) {
                if (m.getClass() == clazz) return (T) m;
            }
            return null;
        }
    }

    public static class ClickGuiScreen extends Screen {
        private final List<GuiPanel> panels = new ArrayList<>();

        public ClickGuiScreen(ModuleManager moduleManager) {
            super(Text.literal("Visuals Client ClickGUI"));

            int startX = 30;
            int startY = 30;
            int panelWidth = 120;

            for (Category cat : Category.values()) {
                List<Module> mods = moduleManager.getModulesByCategory(cat);
                panels.add(new GuiPanel(cat.getDisplayName(), startX, startY, panelWidth, mods));
                startX += panelWidth + 20;
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Надежное полупрозрачное затемнение фона без зависимости от сигнатуры renderBackground
            context.fill(0, 0, this.width, this.height, 0x88000000);

            for (GuiPanel panel : panels) {
                panel.render(context, mouseX, mouseY);
            }

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            for (int i = panels.size() - 1; i >= 0; i--) {
                if (panels.get(i).mouseClicked((int) mouseX, (int) mouseY, button)) {
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (GuiPanel panel : panels) {
                panel.mouseReleased(button);
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            for (GuiPanel panel : panels) {
                panel.mouseDragged((int) mouseX, (int) mouseY);
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }

    public static class GuiPanel {
        private final String title;
        private int x;
        private int y;
        private final int width;
        private boolean isDragging;
        private int dragOffsetX;
        private int dragOffsetY;
        private final List<ModuleButton> buttons = new ArrayList<>();

        public GuiPanel(String title, int x, int y, int width, List<Module> modules) {
            this.title = title;
            this.x = x;
            this.y = y;
            this.width = width;

            int currentY = 18;
            for (Module mod : modules) {
                buttons.add(new ModuleButton(mod, this, currentY));
                currentY += 16;
            }
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            int headerHeight = 16;
            int totalContentHeight = calculateContentHeight();

            // Фон панели
            context.fill(x, y + headerHeight, x + width, y + headerHeight + totalContentHeight, 0xDD121217);

            // Шапка панели
            context.fill(x, y, x + width, y + headerHeight, 0xFF1E1E26);
            context.fill(x, y + headerHeight - 1, x + width, y + headerHeight, 0xFF6366F1);

            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, title, x + 6, y + 4, 0xFFFFFFFF, false);

            int offsetY = y + headerHeight;
            for (ModuleButton btn : buttons) {
                btn.render(context, x, offsetY, width, mouseX, mouseY);
                offsetY += btn.getHeight();
            }

            // Безопасная отрисовка границы
            drawOutline(context, x, y, width, headerHeight + totalContentHeight, 0x44FFFFFF);
        }

        private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
            context.fill(x, y, x + w, y + 1, color);
            context.fill(x, y + h - 1, x + w, y + h, color);
            context.fill(x, y, x + 1, y + h, color);
            context.fill(x + w - 1, y, x + w, y + h, color);
        }

        public int calculateContentHeight() {
            int height = 0;
            for (ModuleButton btn : buttons) {
                height += btn.getHeight();
            }
            return Math.max(height, 4);
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 16) {
                if (button == 0) {
                    isDragging = true;
                    dragOffsetX = mouseX - x;
                    dragOffsetY = mouseY - y;
                    return true;
                }
            }

            int offsetY = y + 16;
            for (ModuleButton btn : buttons) {
                if (btn.mouseClicked(x, offsetY, width, mouseX, mouseY, button)) {
                    return true;
                }
                offsetY += btn.getHeight();
            }

            return false;
        }

        public void mouseReleased(int button) {
            if (button == 0) {
                isDragging = false;
            }
            for (ModuleButton btn : buttons) {
                btn.mouseReleased(button);
            }
        }

        public void mouseDragged(int mouseX, int mouseY) {
            if (isDragging) {
                x = mouseX - dragOffsetX;
                y = mouseY - dragOffsetY;
            }
            for (ModuleButton btn : buttons) {
                btn.mouseDragged(mouseX, mouseY);
            }
        }
    }

    public static class ModuleButton {
        private final Module module;
        private final GuiPanel parent;
        private boolean expanded = false;
        private final List<SettingComponent> settingComponents = new ArrayList<>();

        public ModuleButton(Module module, GuiPanel parent, int relativeY) {
            this.module = module;
            this.parent = parent;

            for (Setting<?> setting : module.getSettings()) {
                if (setting instanceof BooleanSetting bool) {
                    settingComponents.add(new BooleanComponent(bool));
                } else if (setting instanceof NumberSetting num) {
                    settingComponents.add(new SliderComponent(num));
                } else if (setting instanceof ModeSetting mode) {
                    settingComponents.add(new ModeComponent(mode));
                }
            }
        }

        public int getHeight() {
            int h = 16;
            if (expanded) {
                for (SettingComponent comp : settingComponents) {
                    h += comp.getHeight();
                }
            }
            return h;
        }

        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 16;

            int bgColor = module.isEnabled()
                    ? (hovered ? 0xFF4F46E5 : 0xFF4338CA)
                    : (hovered ? 0xFF24242F : 0xFF181820);

            context.fill(x + 2, y + 1, x + width - 2, y + 15, bgColor);

            int textColor = module.isEnabled() ? 0xFFFFFFFF : 0xFFA0A0AB;
            drawTextSafe(context, mc.textRenderer, module.getName(), x + 6, y + 4, textColor, false);

            if (!module.getSettings().isEmpty()) {
                drawTextSafe(context, mc.textRenderer, expanded ? "-" : "+", x + width - 12, y + 4, 0xFF888899, false);
            }

            if (expanded) {
                int settingY = y + 16;
                for (SettingComponent comp : settingComponents) {
                    comp.render(context, x + 4, settingY, width - 8, mouseX, mouseY);
                    settingY += comp.getHeight();
                }
            }
        }

        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 16) {
                if (button == 0) {
                    module.toggle();
                    return true;
                } else if (button == 1) {
                    expanded = !expanded;
                    return true;
                }
            }

            if (expanded) {
                int settingY = y + 16;
                for (SettingComponent comp : settingComponents) {
                    if (comp.mouseClicked(x + 4, settingY, width - 8, mouseX, mouseY, button)) {
                        return true;
                    }
                    settingY += comp.getHeight();
                }
            }
            return false;
        }

        public void mouseReleased(int button) {
            for (SettingComponent comp : settingComponents) {
                comp.mouseReleased(button);
            }
        }

        public void mouseDragged(int mouseX, int mouseY) {
            for (SettingComponent comp : settingComponents) {
                comp.mouseDragged(mouseX, mouseY);
            }
        }
    }

    public interface SettingComponent {
        int getHeight();
        void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY);
        boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button);
        default void mouseReleased(int button) {}
        default void mouseDragged(int mouseX, int mouseY) {}
    }

    public static class BooleanComponent implements SettingComponent {
        private final BooleanSetting setting;

        public BooleanComponent(BooleanSetting setting) {
            this.setting = setting;
        }

        @Override
        public int getHeight() { return 14; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            context.fill(x, y, x + width, y + 13, 0xFF141419);
            drawTextSafe(context, mc.textRenderer, setting.getName(), x + 4, y + 3, 0xFFCCCCCC, false);

            int checkColor = setting.get() ? 0xFF22C55E : 0xFFEF4444;
            context.fill(x + width - 14, y + 2, x + width - 4, y + 12, checkColor);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 14 && button == 0) {
                setting.toggle();
                return true;
            }
            return false;
        }
    }

    public static class SliderComponent implements SettingComponent {
        private final NumberSetting setting;
        private boolean sliding = false;
        private int currentX;
        private int currentWidth;

        public SliderComponent(NumberSetting setting) {
            this.setting = setting;
        }

        @Override
        public int getHeight() { return 18; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            this.currentX = x;
            this.currentWidth = width;
            MinecraftClient mc = MinecraftClient.getInstance();

            context.fill(x, y, x + width, y + 17, 0xFF141419);

            double pct = (setting.get() - setting.getMin()) / (setting.getMax() - setting.getMin());
            int fillWidth = (int) (width * MathHelper.clamp(pct, 0.0, 1.0));

            context.fill(x, y + 12, x + fillWidth, y + 15, 0xFF6366F1);
            context.fill(x + fillWidth, y + 12, x + width, y + 15, 0xFF2E2E38);

            String text = String.format("%s: %.2f", setting.getName(), setting.get());
            drawTextSafe(context, mc.textRenderer, text, x + 4, y + 2, 0xFFDDDDDD, false);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 18 && button == 0) {
                sliding = true;
                updateValue(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int button) {
            sliding = false;
        }

        @Override
        public void mouseDragged(int mouseX, int mouseY) {
            if (sliding) {
                updateValue(mouseX);
            }
        }

        private void updateValue(int mouseX) {
            double percent = (double) (mouseX - currentX) / (double) currentWidth;
            double newVal = setting.getMin() + (setting.getMax() - setting.getMin()) * percent;
            setting.setValueClamped(newVal);
        }
    }

    public static class ModeComponent implements SettingComponent {
        private final ModeSetting setting;

        public ModeComponent(ModeSetting setting) {
            this.setting = setting;
        }

        @Override
        public int getHeight() { return 15; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            context.fill(x, y, x + width, y + 14, 0xFF141419);

            String text = setting.getName() + ": " + setting.get();
            drawTextSafe(context, mc.textRenderer, text, x + 4, y + 3, 0xFF93C5FD, false);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 15 && button == 0) {
                setting.cycle();
                return true;
            }
            return false;
        }
    }
}
