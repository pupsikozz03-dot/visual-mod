package com.visuals.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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

        // Конструктор на 3 аргумента (String, int, String) для надежной совместимости
        clickGuiKey = registerKeyBindingSafely("key.visuals.clickgui", GLFW.GLFW_KEY_INSERT, "category.visuals");

        moduleManager.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean openRequested = false;

            if (clickGuiKey != null && clickGuiKey.wasPressed()) {
                openRequested = true;
            }

            // Прямой опрос GLFW на случай кастомных версий Fabric/драйверов
            if (client.getWindow() != null && client.getWindow().getHandle() != 0) {
                long handle = client.getWindow().getHandle();
                boolean isDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS;
                if (isDown && !wasInsertKeyDown) {
                    openRequested = true;
                }
                wasInsertKeyDown = isDown;
            }

            if (openRequested && client.currentScreen == null) {
                client.setScreen(new ModernDeltaClickGui(moduleManager));
            }

            if (client.world != null && client.player != null) {
                moduleManager.onTick(client);
            }
        });

        // Хук рендера NameTags в мировом пространстве
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            NameTagsModule nameTags = moduleManager.getModule(NameTagsModule.class);
            if (nameTags != null && nameTags.isEnabled()) {
                nameTags.renderInWorld(context);
            }
        });
    }

    private KeyBinding registerKeyBindingSafely(String translationKey, int defaultKey, String category) {
        try {
            // Прямой вызов конструктора (String, int, String)
            Constructor<KeyBinding> ctor = KeyBinding.class.getConstructor(String.class, int.class, String.class);
            KeyBinding binding = ctor.newInstance(translationKey, defaultKey, category);
            KeyBindingHelper.registerKeyBinding(binding);
            return binding;
        } catch (Throwable ignored) {
            try {
                // Запасной вариант через рефлексию категорий 1.21.11+
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
                System.out.println("[VisualMod] KeyBinding fallback to GLFW direct polling.");
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
        } catch (Throwable t) {
            System.out.println("[VisualMod] Safe text reflection error: " + t.getMessage());
        }
    }

    public enum Category {
        VISUALS("Visuals", "Display & Enhancements"),
        ANIMATIONS("Animations", "Custom motion & scales"),
        REMOVALS("Removals", "PvP cleaner & clutter removal"),
        WORLD("ESP / World", "World overrides & entity tags"),
        SETTINGS("Settings", "Client preferences & themes");

        private final String displayName;
        private final String subTitle;

        Category(String displayName, String subTitle) {
            this.displayName = displayName;
            this.subTitle = subTitle;
        }

        public String getDisplayName() { return displayName; }
        public String getSubTitle() { return subTitle; }
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
        public BooleanSetting(String name, boolean defaultValue) {
            super(name, defaultValue);
        }

        public void toggle() {
            this.value = !this.value;
        }
    }

    public static class SliderSetting extends Setting<Double> {
        private final double min;
        private final double max;
        private final double increment;

        public SliderSetting(String name, double defaultValue, double min, double max, double increment) {
            super(name, defaultValue);
            this.min = min;
            this.max = max;
            this.increment = increment;
        }

        public double getMin() { return min; }
        public double getMax() { return max; }

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

    public static class ColorSetting extends Setting<Integer> {
        private int red;
        private int green;
        private int blue;
        private int alpha;

        public ColorSetting(String name, int hexWithAlpha) {
            super(name, hexWithAlpha);
            updateComponents(hexWithAlpha);
        }

        private void updateComponents(int argb) {
            this.alpha = (argb >> 24) & 0xFF;
            this.red = (argb >> 16) & 0xFF;
            this.green = (argb >> 8) & 0xFF;
            this.blue = argb & 0xFF;
        }

        public void setRGBA(int r, int g, int b, int a) {
            this.red = MathHelper.clamp(r, 0, 255);
            this.green = MathHelper.clamp(g, 0, 255);
            this.blue = MathHelper.clamp(b, 0, 255);
            this.alpha = MathHelper.clamp(a, 0, 255);
            this.value = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }

        public int getRed() { return red; }
        public int getGreen() { return green; }
        public int getBlue() { return blue; }
        public int getAlpha() { return alpha; }
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

    public static class AspectRatioModule extends Module {
        public final ModeSetting presets = new ModeSetting("Aspect Preset", "Custom", List.of("Custom", "4:3", "16:9", "1:1", "21:9", "5:4"));
        public final SliderSetting ratio = new SliderSetting("Ratio Value", 1.77, 0.50, 2.50, 0.05);

        public AspectRatioModule() {
            super("Aspect Ratio", "Changes the camera projection aspect ratio", Category.VISUALS);
            registerSetting(presets);
            registerSetting(ratio);
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
            float targetAspect = getAspectRatio(defaultAspect);
            matrix.identity();
            return matrix.perspective((float) Math.toRadians(fov), targetAspect, nearPlane, farPlane);
        }
    }

    public static class AmbienceModule extends Module {
        public final ModeSetting timeMode = new ModeSetting("Time", "Sunset", List.of("Day", "Sunset", "Night", "Custom", "Cycle"));
        public final SliderSetting customTime = new SliderSetting("Custom Time", 18000, 0, 24000, 500);
        public final BooleanSetting customFog = new BooleanSetting("Fog Override", true);
        public final ColorSetting fogColor = new ColorSetting("Fog Color", 0xFF6366F1);
        private long cycleTicks = 0;

        public AmbienceModule() {
            super("Ambience", "Custom sky time and fog atmosphere", Category.WORLD);
            registerSetting(timeMode);
            registerSetting(customTime);
            registerSetting(customFog);
            registerSetting(fogColor);
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

    public static class LowFireModule extends Module {
        public final SliderSetting height = new SliderSetting("Fire Height", 0.20, 0.0, 1.0, 0.05);

        public LowFireModule() {
            super("Low Fire", "Lowers or disables first-person fire overlay", Category.REMOVALS);
            registerSetting(height);
        }
    }

    public static class LowShieldModule extends Module {
        public final SliderSetting offsetY = new SliderSetting("Offset Y", 0.25, 0.0, 0.60, 0.05);
        public final SliderSetting scale = new SliderSetting("Shield Scale", 0.75, 0.30, 1.0, 0.05);

        public LowShieldModule() {
            super("Low Shield", "Lowers offhand shield position to clear FOV", Category.REMOVALS);
            registerSetting(offsetY);
            registerSetting(scale);
        }
    }

    public static class NoHurtCamModule extends Module {
        public final SliderSetting intensity = new SliderSetting("Cam Shake", 0.0, 0.0, 1.0, 0.1);

        public NoHurtCamModule() {
            super("No Hurt Cam", "Disables camera shake when taking damage", Category.REMOVALS);
            registerSetting(intensity);
        }
    }

    public static class NoPumpkinOverlayModule extends Module {
        public NoPumpkinOverlayModule() {
            super("No Pumpkin", "Removes carved pumpkin head overlay", Category.REMOVALS);
        }
    }

    public static class NoPortalOverlayModule extends Module {
        public NoPortalOverlayModule() {
            super("No Portal", "Removes nether portal nausea & distortion", Category.REMOVALS);
        }
    }

    public static class AntiBlindnessModule extends Module {
        public final BooleanSetting removeDarkness = new BooleanSetting("Remove Darkness", true);
        public final BooleanSetting removeBlindness = new BooleanSetting("Remove Blindness", true);

        public AntiBlindnessModule() {
            super("Anti Blindness", "Disables warden darkness flashes and blindness", Category.REMOVALS);
            registerSetting(removeDarkness);
            registerSetting(removeBlindness);
        }
    }

    public static class NameTagsModule extends Module {
        public final BooleanSetting showHealth = new BooleanSetting("Health Bar", true);
        public final BooleanSetting showPing = new BooleanSetting("Ping Indicator", true);
        public final BooleanSetting showEquipment = new BooleanSetting("Equipment & Durability", true);
        public final SliderSetting scaleFactor = new SliderSetting("Plate Scale", 1.0, 0.5, 2.0, 0.1);
        public final ColorSetting tagColor = new ColorSetting("Background Color", 0xCC111116);

        public NameTagsModule() {
            super("NameTags", "Smooth distance-scaled player plates with HP & armor", Category.WORLD);
            registerSetting(showHealth);
            registerSetting(showPing);
            registerSetting(showEquipment);
            registerSetting(scaleFactor);
            registerSetting(tagColor);
        }

        public void renderInWorld(WorldRenderContext context) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world == null || mc.player == null) return;

            Camera camera = context.camera();
            Vec3d camPos = camera.getPos();
            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();

            if (matrices == null || consumers == null) return;

            for (PlayerEntity target : mc.world.getPlayers()) {
                if (target == mc.player || !target.isAlive()) continue;

                matrices.push();

                // Вычисление смещения относительно камеры
                double posX = MathHelper.lerp(context.tickCounter().getTickDelta(true), target.lastRenderX, target.getX()) - camPos.x;
                double posY = MathHelper.lerp(context.tickCounter().getTickDelta(true), target.lastRenderY, target.getY()) - camPos.y + target.getHeight() + 0.55;
                double posZ = MathHelper.lerp(context.tickCounter().getTickDelta(true), target.lastRenderZ, target.getZ()) - camPos.z;

                matrices.translate(posX, posY, posZ);

                // Поворот плашки лицом к камере
                matrices.multiply(camera.getRotation());

                // Динамическое масштабирование от дистанции
                double distance = camPos.distanceTo(target.getPos());
                float scale = (float) Math.max(0.015f * scaleFactor.get(), (distance * 0.0028f) * scaleFactor.get());
                matrices.scale(-scale, -scale, scale);

                renderPlate(matrices, consumers, target, mc);

                matrices.pop();
            }
        }

        private void renderPlate(MatrixStack matrices, VertexConsumerProvider consumers, PlayerEntity player, MinecraftClient mc) {
            TextRenderer tr = mc.textRenderer;
            String name = player.getName().getString();

            int ping = -1;
            if (mc.getNetworkHandler() != null) {
                PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
                if (entry != null) ping = entry.getLatency();
            }

            String pingStr = (showPing.get() && ping >= 0) ? " " + ping + "ms" : "";
            float hp = player.getHealth() + player.getAbsorptionAmount();
            float maxHp = player.getMaxHealth() + player.getAbsorptionAmount();
            String hpStr = String.format(" %.1f", hp);

            String fullText = name + pingStr + (showHealth.get() ? hpStr : "");
            int textWidth = tr.getWidth(fullText);
            int halfWidth = textWidth / 2 + 6;

            // Рендер текста никнейма и статуса
            tr.draw(
                    Text.literal(fullText),
                    -textWidth / 2f,
                    -10,
                    0xFFFFFF,
                    true,
                    matrices.peek().getPositionMatrix(),
                    consumers,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    0,
                    15728880
            );

            // Рендер полоски здоровья под ником
            if (showHealth.get()) {
                float hpPercent = MathHelper.clamp(hp / maxHp, 0.0f, 1.0f);
                int barWidth = (int) ((halfWidth * 2) * hpPercent);
                int hpColor = getHealthColor(hpPercent);

                tr.draw(
                        Text.literal("▪".repeat(Math.max(1, barWidth / 4))),
                        -halfWidth,
                        2,
                        hpColor,
                        false,
                        matrices.peek().getPositionMatrix(),
                        consumers,
                        TextRenderer.TextLayerType.SEE_THROUGH,
                        0,
                        15728880
                );
            }
        }

        private int getHealthColor(float percent) {
            int r = (int) (255 * (1.0f - percent));
            int g = (int) (255 * percent);
            return 0xFF000000 | (r << 16) | (g << 8);
        }
    }

    public static class ModuleManager {
        private final List<Module> modules = new ArrayList<>();

        public void init() {
            // Visuals
            modules.add(new AspectRatioModule());

            // Removals
            modules.add(new LowFireModule());
            modules.add(new LowShieldModule());
            modules.add(new NoHurtCamModule());
            modules.add(new NoPumpkinOverlayModule());
            modules.add(new NoPortalOverlayModule());
            modules.add(new AntiBlindnessModule());

            // ESP & World
            modules.add(new NameTagsModule());
            modules.add(new AmbienceModule());
        }

        public void onTick(MinecraftClient client) {
            for (Module m : modules) {
                if (m.isEnabled()) {
                    m.onTick(client);
                }
            }
        }

        public List<Module> getModules() { return modules; }

        public List<Module> getModulesByCategory(Category cat) {
            List<Module> list = new ArrayList<>();
            for (Module m : modules) {
                if (m.getCategory() == cat) list.add(m);
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

    public static class ModernDeltaClickGui extends Screen {
        private final ModuleManager moduleManager;
        private Category selectedCategory = Category.VISUALS;

        private final int guiWidth = 520;
        private final int guiHeight = 330;
        private int leftX;
        private int topY;

        private final List<DeltaModuleCard> cards = new ArrayList<>();

        public ModernDeltaClickGui(ModuleManager moduleManager) {
            super(Text.literal("Delta Client Visuals"));
            this.moduleManager = moduleManager;
        }

        @Override
        protected void init() {
            this.leftX = (this.width - guiWidth) / 2;
            this.topY = (this.height - guiHeight) / 2;
            reloadCategoryCards();
        }

        private void reloadCategoryCards() {
            cards.clear();
            List<Module> mods = moduleManager.getModulesByCategory(selectedCategory);
            int startCardY = topY + 48;
            for (Module mod : mods) {
                cards.add(new DeltaModuleCard(mod, leftX + 145, startCardY, guiWidth - 155));
                startCardY += 40;
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Эффект глубокого затемнения фона (Vignette / Blur Dim)
            context.fill(0, 0, this.width, this.height, 0x990A0A0F);

            // Основной каркас окна в палитре Delta (#0F0F14 / #16161E)
            renderRoundedCard(context, leftX, topY, guiWidth, guiHeight, 0xF00F0F14, 0x446366F1);

            // Отрисовка боковой панели навигации (Sidebar)
            context.fill(leftX, topY, leftX + 135, topY + guiHeight, 0xEE14141C);
            context.fill(leftX + 135, topY, leftX + 136, topY + guiHeight, 0x22818CF8);

            // Логотип и брендинг Delta Client
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, "DELTA", leftX + 14, topY + 16, 0xFF818CF8, true);
            drawTextSafe(context, mc.textRenderer, "CLIENT 1.21.11", leftX + 48, topY + 17, 0xFF888899, false);

            // Элементы категорий слева
            int catY = topY + 46;
            for (Category cat : Category.values()) {
                boolean isCurrent = (cat == selectedCategory);
                boolean hovered = mouseX >= leftX + 8 && mouseX <= leftX + 128 && mouseY >= catY && mouseY <= catY + 28;

                if (isCurrent) {
                    renderRoundedCard(context, leftX + 8, catY, 120, 28, 0xFF1E1E2A, 0x886366F1);
                    context.fill(leftX + 8, catY + 5, leftX + 11, catY + 23, 0xFF6366F1); // Левая неоновая полоса
                } else if (hovered) {
                    context.fill(leftX + 8, catY, leftX + 128, catY + 28, 0x33252535);
                }

                int textColor = isCurrent ? 0xFFFFFFFF : (hovered ? 0xFFD1D5DB : 0xFF71717A);
                drawTextSafe(context, mc.textRenderer, cat.getDisplayName(), leftX + 18, catY + 6, textColor, false);
                drawTextSafe(context, mc.textRenderer, cat.getSubTitle(), leftX + 18, catY + 16, 0xFF52525B, false);

                catY += 32;
            }

            // Заголовок активной секции справа
            drawTextSafe(context, mc.textRenderer, selectedCategory.getDisplayName().toUpperCase(), leftX + 148, topY + 16, 0xFFFFFFFF, true);
            drawTextSafe(context, mc.textRenderer, selectedCategory.getSubTitle(), leftX + 148, topY + 28, 0xFF71717A, false);

            // Карточки модулей
            int currentCardY = topY + 44;
            for (DeltaModuleCard card : cards) {
                card.setY(currentCardY);
                card.render(context, mouseX, mouseY);
                currentCardY += card.getTotalHeight() + 6;
            }

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            // Клик по боковой панели
            if (mouseX >= leftX + 8 && mouseX <= leftX + 128) {
                int catY = topY + 46;
                for (Category cat : Category.values()) {
                    if (mouseY >= catY && mouseY <= catY + 28) {
                        this.selectedCategory = cat;
                        reloadCategoryCards();
                        return true;
                    }
                    catY += 32;
                }
            }

            // Клик по модулям
            for (DeltaModuleCard card : cards) {
                if (card.mouseClicked((int) mouseX, (int) mouseY, button)) {
                    return true;
                }
            }

            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            for (DeltaModuleCard card : cards) {
                card.mouseReleased(button);
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
            for (DeltaModuleCard card : cards) {
                card.mouseDragged((int) mouseX, (int) mouseY);
            }
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        public static void renderRoundedCard(DrawContext context, int x, int y, int w, int h, int bg, int border) {
            // Плавный стилизованный прямоугольник с обводкой
            context.fill(x, y, x + w, y + h, bg);
            context.fill(x, y, x + w, y + 1, border);
            context.fill(x, y + h - 1, x + w, y + h, border);
            context.fill(x, y, x + 1, y + h, border);
            context.fill(x + w - 1, y, x + w, y + h, border);
        }
    }

    public static class DeltaModuleCard {
        private final Module module;
        private final int x;
        private int y;
        private final int width;
        private boolean expanded = false;
        private float toggleAnim = 0.0f;

        private final List<DeltaSettingWidget> widgets = new ArrayList<>();

        public DeltaModuleCard(Module module, int x, int y, int width) {
            this.module = module;
            this.x = x;
            this.y = y;
            this.width = width;
            this.toggleAnim = module.isEnabled() ? 1.0f : 0.0f;

            for (Setting<?> s : module.getSettings()) {
                if (s instanceof BooleanSetting b) widgets.add(new DeltaToggleWidget(b));
                else if (s instanceof SliderSetting sl) widgets.add(new DeltaSliderWidget(sl));
                else if (s instanceof ModeSetting m) widgets.add(new DeltaModeWidget(m));
                else if (s instanceof ColorSetting c) widgets.add(new DeltaColorWidget(c));
            }
        }

        public void setY(int y) { this.y = y; }

        public int getTotalHeight() {
            int h = 34;
            if (expanded) {
                for (DeltaSettingWidget w : widgets) {
                    h += w.getHeight() + 4;
                }
                h += 6;
            }
            return h;
        }

        public void render(DrawContext context, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 34;

            // Плавная интерполяция переключателя
            float targetAnim = module.isEnabled() ? 1.0f : 0.0f;
            toggleAnim += (targetAnim - toggleAnim) * 0.25f;

            int cardBg = hovered ? 0xFF1B1B26 : 0xFF14141C;
            ModernDeltaClickGui.renderRoundedCard(context, x, y, width, getTotalHeight(), cardBg, module.isEnabled() ? 0x666366F1 : 0x22333344);

            // Текст названия и описания модуля
            int titleColor = module.isEnabled() ? 0xFFFFFFFF : 0xFFA1A1AA;
            drawTextSafe(context, mc.textRenderer, module.getName(), x + 12, y + 8, titleColor, false);
            drawTextSafe(context, mc.textRenderer, module.getDescription(), x + 12, y + 20, 0xFF52525B, false);

            // Неоновый тумблер Toggle Switch справа
            int switchX = x + width - 38;
            int switchY = y + 10;
            int switchBg = (toggleAnim > 0.05f) ? 0xFF6366F1 : 0xFF272732;
            ModernDeltaClickGui.renderRoundedCard(context, switchX, switchY, 26, 14, switchBg, 0x44FFFFFF);

            int knobX = switchX + 2 + (int) (12 * toggleAnim);
            context.fill(knobX, switchY + 2, knobX + 10, switchY + 12, 0xFFFFFFFF);

            // Иконка раскрытия параметров (если есть настройки)
            if (!widgets.isEmpty()) {
                drawTextSafe(context, mc.textRenderer, expanded ? "▲" : "▼", switchX - 16, y + 13, 0xFF71717A, false);
            }

            // Рендер раскрытых настроек
            if (expanded) {
                int widgetY = y + 36;
                context.fill(x + 8, y + 34, x + width - 8, y + 35, 0x22FFFFFF); // Разделитель
                for (DeltaSettingWidget w : widgets) {
                    w.render(context, x + 12, widgetY, width - 24, mouseX, mouseY);
                    widgetY += w.getHeight() + 4;
                }
            }
        }

        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            // Клик по тумблеру или строке
            if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 34) {
                if (button == 0) {
                    module.toggle();
                    return true;
                } else if (button == 1 && !widgets.isEmpty()) {
                    expanded = !expanded;
                    return true;
                }
            }

            if (expanded) {
                int widgetY = y + 36;
                for (DeltaSettingWidget w : widgets) {
                    if (w.mouseClicked(x + 12, widgetY, width - 24, mouseX, mouseY, button)) {
                        return true;
                    }
                    widgetY += w.getHeight() + 4;
                }
            }
            return false;
        }

        public void mouseReleased(int button) {
            for (DeltaSettingWidget w : widgets) w.mouseReleased(button);
        }

        public void mouseDragged(int mouseX, int mouseY) {
            for (DeltaSettingWidget w : widgets) w.mouseDragged(mouseX, mouseY);
        }
    }

    public interface DeltaSettingWidget {
        int getHeight();
        void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY);
        boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button);
        default void mouseReleased(int button) {}
        default void mouseDragged(int mouseX, int mouseY) {}
    }

    public static class DeltaToggleWidget implements DeltaSettingWidget {
        private final BooleanSetting setting;

        public DeltaToggleWidget(BooleanSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 18; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, setting.getName(), x, y + 4, 0xFFCCCCCC, false);

            int btnX = x + width - 18;
            int col = setting.get() ? 0xFF6366F1 : 0xFF272732;
            ModernDeltaClickGui.renderRoundedCard(context, btnX, y + 2, 14, 14, col, 0x44FFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 18) {
                setting.toggle();
                return true;
            }
            return false;
        }
    }

    public static class DeltaSliderWidget implements DeltaSettingWidget {
        private final SliderSetting setting;
        private boolean sliding = false;
        private int lastX, lastW;

        public DeltaSliderWidget(SliderSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 24; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            this.lastX = x;
            this.lastW = width;
            MinecraftClient mc = MinecraftClient.getInstance();

            String text = String.format("%s: §7%.2f", setting.getName(), setting.get());
            drawTextSafe(context, mc.textRenderer, text, x, y + 2, 0xFFE2E8F0, false);

            // Полоса слайдера
            int barY = y + 14;
            context.fill(x, barY, x + width, barY + 5, 0xFF272732);

            double pct = (setting.get() - setting.getMin()) / (setting.getMax() - setting.getMin());
            int fillW = (int) (width * MathHelper.clamp(pct, 0.0, 1.0));
            context.fill(x, barY, x + fillW, barY + 5, 0xFF6366F1);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y + 10 && mouseY <= y + 24) {
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

    public static class DeltaModeWidget implements DeltaSettingWidget {
        private final ModeSetting setting;

        public DeltaModeWidget(ModeSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 18; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, setting.getName(), x, y + 4, 0xFFCCCCCC, false);

            String modeStr = "§8[§f" + setting.get() + "§8]";
            int modeW = mc.textRenderer.getWidth(modeStr);
            drawTextSafe(context, mc.textRenderer, modeStr, x + width - modeW, y + 4, 0xFF818CF8, false);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 18) {
                setting.cycle();
                return true;
            }
            return false;
        }
    }

    public static class DeltaColorWidget implements DeltaSettingWidget {
        private final ColorSetting setting;

        public DeltaColorWidget(ColorSetting setting) { this.setting = setting; }

        @Override
        public int getHeight() { return 18; }

        @Override
        public void render(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
            MinecraftClient mc = MinecraftClient.getInstance();
            drawTextSafe(context, mc.textRenderer, setting.getName(), x, y + 4, 0xFFCCCCCC, false);

            // Квадрат предварительного просмотра цвета с альфой
            int previewX = x + width - 24;
            context.fill(previewX, y + 2, previewX + 20, y + 16, setting.get());
            ModernDeltaClickGui.renderRoundedCard(context, previewX, y + 2, 20, 14, 0x00000000, 0x66FFFFFF);
        }

        @Override
        public boolean mouseClicked(int x, int y, int width, int mouseX, int mouseY, int button) {
            if (button == 0 && mouseX >= x + width - 24 && mouseX <= x + width && mouseY >= y && mouseY <= y + 18) {
                // Циклический сдвиг оттенков для быстрого переключения в GUI
                int nextHue = (setting.getRed() + 40) % 256;
                setting.setRGBA(nextHue, setting.getGreen(), setting.getBlue(), setting.getAlpha());
                return true;
            }
            return false;
        }
    }
}
