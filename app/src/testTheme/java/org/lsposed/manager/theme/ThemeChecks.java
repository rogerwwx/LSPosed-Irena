package org.lsposed.manager.theme;

import io.material.color.utilities.utils.ColorUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Standalone JVM checks; no Android SDK or device required. */
public final class ThemeChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static double ratio(int a, int b) {
        double x = ColorUtils.xyzFromArgb(a)[1], y = ColorUtils.xyzFromArgb(b)[1];
        return (Math.max(x, y) + 5) / (Math.min(x, y) + 5);
    }
    private static void contrast(Map<String, Integer> colors, String foreground, String background,
                                 double minimum, String label) {
        double value = ratio(colors.get(foreground), colors.get(background));
        check(value >= minimum, label + ": " + foreground + "/" + background + " = " + value);
    }
    public static void main(String[] args) throws Exception {
        ThemeConfig fresh = ThemeConfig.from(Map.of());
        check(fresh.source() == ThemeConfig.Source.SYSTEM_SEED && fresh.spec().equals("SPEC_2025"), "fresh defaults");
        check(!fresh.material(), "default skin preserved");
        check(fresh.followSystemAccent(), "default miuix system accent preserved");
        check(ThemeConfig.from(Map.of("ui_style", "MATERIAL")).source() == ThemeConfig.Source.SYSTEM_DIRECT, "legacy system");
        check(ThemeConfig.from(Map.of("doh", true)).source() == ThemeConfig.Source.SYSTEM_DIRECT, "legacy defaults without theme keys");
        ThemeConfig legacy = ThemeConfig.from(Map.of("palette_style", "VIBRANT", "color_spec", "SYSTEM"));
        check(legacy.source() == ThemeConfig.Source.WALLPAPER && legacy.effectiveSpec().equals("SPEC_2021"), "legacy generated");
        ThemeConfig fixed = ThemeConfig.from(Map.of("follow_system_accent", false, "theme_color", "COLOR_BLUE"));
        check(fixed.source() == ThemeConfig.Source.FIXED && fixed.fixedSeed() == 0xff2196f3, "legacy blue alias");
        check(!fixed.followSystemAccent(), "legacy miuix fixed accent preserved");
        ThemeConfig invalid = ThemeConfig.from(Map.of("theme_config_version", 1, "color_source", "bad", "palette_style", "bad", "color_spec", "bad", "theme_color", "bad"));
        check(invalid.source() == ThemeConfig.Source.SYSTEM_SEED && invalid.variant().equals("TONAL_SPOT"), "unknown values");
        check(invalid.effectiveSpec().equals("SPEC_2025") && invalid.fixedSeed() == ThemeConfig.DEFAULT_SEED, "unknown defaults");
        ThemeConfig wrongTypes = ThemeConfig.from(Map.of("theme_config_version", 1,
                "follow_system_accent", "false", "black_dark_theme", "true", "dark_theme", 42,
                "palette_style", false, "color_source", 42, "theme_color", false));
        check(wrongTypes.followSystemAccent() && !wrongTypes.black(), "wrong boolean types normalized");
        check(wrongTypes.nightMode().equals("MODE_NIGHT_FOLLOW_SYSTEM")
                && wrongTypes.variant().equals("TONAL_SPOT") && wrongTypes.fixedSeed() == ThemeConfig.DEFAULT_SEED,
                "wrong string types normalized");
        check(!fresh.signature(1, false).equals(ThemeConfig.from(Map.of("theme_config_version", 1,
                "follow_system_accent", false)).signature(1, false)), "miuix accent invalidates signature");
        check(!fresh.signature(1, false).equals(fresh.signature(2, false)), "seed invalidates signature");
        check(!fresh.signature(1, false).equals(fresh.signature(1, true)), "night invalidates signature");
        checkSeedFallbacks(fresh, fixed);
        var html = new StringBuilder("<!doctype html><meta charset='utf-8'><title>Theme palette comparison</title><style>body{font:16px system-ui;margin:24px;background:#ddd}section{display:inline-block;width:280px;padding:20px;margin:10px;border-radius:24px;vertical-align:top}.card{padding:16px;margin:5px 0;border-radius:18px}.muted{font-size:14px}button{border:0;border-radius:24px;padding:10px 20px;font:inherit}nav{margin-top:20px}small{display:block}</style><h1>Material palette comparison</h1><p>Generated role previews, not Android screenshots. Tonal Spot / 2025; same seed in light and dark.</p>");
        StringBuilder baseline = new StringBuilder();
        int[] seeds = {0xffff9ca8, 0xfff44336, 0xffffeb3b, 0xff4faf50, 0xff2196f3, 0xff9c27b0, 0xff808080};
        int schemes = 0;
        for (String variant : new java.util.TreeSet<>(ThemeConfig.VARIANTS)) {
            for (String spec : new String[]{"SPEC_2021", "SPEC_2025"}) {
                ThemeConfig config = ThemeConfig.from(Map.of("theme_config_version", 1, "ui_style", "MATERIAL", "palette_style", variant, "color_spec", spec));
                check(config.generatesPalette(30) && !config.generatesPalette(29), "API capability");
                check(config.supports2025() || config.effectiveSpec().equals("SPEC_2021"), "effective spec");
                for (int seed : seeds) {
                    ResolvedPalette palette = ResolvedPalette.generate(config, seed);
                    check(palette.light().keySet().equals(palette.dark().keySet()), "role parity");
                    check(palette.light().size() == 47, "complete role set");
                    for (boolean dark : new boolean[]{false, true}) {
                        schemes++;
                        Map<String, Integer> colors = dark ? palette.dark() : palette.light();
                        String label = variant + "/" + spec + "/" + Integer.toHexString(seed) + "/" + dark;
                        String page = dark ? "surface" : "surfaceContainerLow";
                        String card = dark ? "surfaceContainerLow" : "surface";
                        for (String surface : new String[]{page, card, "surfaceContainerHigh"}) {
                            contrast(colors, "onSurface", surface, 4.5, label);
                            contrast(colors, "onSurfaceVariant", surface, 4.5, label);
                        }
                        for (String role : new String[]{"primary", "secondary", "tertiary", "error"}) {
                            String on = "on" + Character.toUpperCase(role.charAt(0)) + role.substring(1);
                            contrast(colors, on, role, 4.5, label);
                            contrast(colors, on + "Container", role + "Container", 4.5, label);
                        }
                        contrast(colors, "outline", card, 3, label);
                        contrast(colors, "primary", page, 4.5, label);
                        contrast(colors, "primary", card, 3, label);
                        check(ColorUtils.lstarFromArgb(colors.get(card)) > ColorUtils.lstarFromArgb(colors.get(page)), label + " card separation");
                        if (variant.equals("TONAL_SPOT") && spec.equals("SPEC_2025")) {
                            html.append("<section style='background:").append(hex(colors.get(page))).append(";color:").append(hex(colors.get("onSurface"))).append("'><small>").append(hex(seed)).append(dark ? " · Dark" : " · Light").append("</small><h2>主题设置</h2>");
                            for (String title : new String[]{"主题 · 跟随系统", "强调色 · 系统选色", "调色板 · Tonal Spot"}) html.append("<div class='card' style='background:").append(hex(colors.get(card))).append("'>").append(title).append("<div class='muted' style='color:").append(hex(colors.get("onSurfaceVariant"))).append("'>轻微染色，清晰的文字层次</div></div>");
                            html.append("<nav><button style='background:").append(hex(colors.get("secondaryContainer"))).append(";color:").append(hex(colors.get("onSecondaryContainer"))).append("'>设置</button></nav>");
                            html.append("<div class='card' style='margin-top:20px;background:").append(hex(colors.get("surfaceContainerHigh"))).append("'><h3>弹窗／菜单</h3><p class='muted' style='color:").append(hex(colors.get("onSurfaceVariant"))).append("'>浮层与页面、卡片使用不同表面角色</p><button style='background:").append(hex(colors.get("primary"))).append(";color:").append(hex(colors.get("onPrimary"))).append("'>确定</button></div></section>");
                            if (seed == ThemeConfig.DEFAULT_SEED) {
                                baseline.append(dark ? "DARK\n" : "LIGHT\n");
                                colors.forEach((key, value) -> baseline.append(key).append('=').append(String.format("#%08X", value)).append('\n'));
                            }
                        }
                    }
                }
            }
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Files.writeString(output.resolve("palette-comparison.html"), html);
        Files.writeString(output.resolve("baseline-colors.txt"), baseline);
        System.out.println("PASS: " + checks + " assertions across " + schemes + " light/dark schemes");
    }
    private static void checkSeedFallbacks(ThemeConfig fresh, ThemeConfig fixed) {
        int red = 0xfff44336;
        IntSupplier system = () -> red;
        IntSupplier missing = () -> { throw new IllegalStateException("missing system color"); };
        IntSupplier unusedSystem = () -> { throw new AssertionError("system should not be read"); };
        Supplier<Map<Integer, Integer>> unusedWallpaper = () -> { throw new AssertionError("wallpaper should not be read"); };
        Supplier<Map<Integer, Integer>> denied = () -> { throw new SecurityException("permission denied"); };
        Supplier<Map<Integer, Integer>> candidates = () -> Map.of(0xff808080, 1, red, 1);
        check(SeedSelection.resolve(fixed, 31, unusedSystem, unusedWallpaper) == fixed.fixedSeed(), "fixed source bypasses readers");
        check(SeedSelection.resolve(fresh, 31, system, unusedWallpaper) == red, "system takes precedence");
        check(SeedSelection.resolve(fresh, 31, missing, candidates) == red, "system failure falls back to ranked wallpaper");
        check(SeedSelection.resolve(fresh, 30, unusedSystem, candidates) == red, "API 30 bypasses system colors");
        check(SeedSelection.resolve(fresh, 31, missing, denied) == ThemeConfig.DEFAULT_SEED, "permission failure falls back to blue");
        check(SeedSelection.resolve(fresh, 31, missing, () -> null) == ThemeConfig.DEFAULT_SEED, "null wallpaper fallback");
        check(SeedSelection.resolve(fresh, 31, missing, Map::of) == ThemeConfig.DEFAULT_SEED, "empty wallpaper fallback");
        check(SeedSelection.resolve(fresh, 31, missing, () -> Map.of(0xff808080, 1)) == ThemeConfig.DEFAULT_SEED,
                "unsuitable wallpaper fallback");
        ThemeConfig wallpaper = ThemeConfig.from(Map.of("theme_config_version", 1, "color_source", "WALLPAPER"));
        check(SeedSelection.resolve(wallpaper, 31, unusedSystem, candidates) == red, "explicit wallpaper skips system");
        ThemeConfig direct = ThemeConfig.from(Map.of("theme_config_version", 1, "ui_style", "MATERIAL", "color_source", "SYSTEM_DIRECT"));
        check(!direct.generatesPalette(31), "direct system palette skips generation");
    }
    private static String hex(int argb) { return String.format("#%06x", argb & 0xffffff); }
}
