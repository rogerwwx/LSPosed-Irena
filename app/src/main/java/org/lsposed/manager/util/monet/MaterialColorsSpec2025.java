/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.util.monet;

import static com.google.android.material.color.utilities.Variant.EXPRESSIVE;
import static com.google.android.material.color.utilities.Variant.NEUTRAL;
import static com.google.android.material.color.utilities.Variant.TONAL_SPOT;
import static com.google.android.material.color.utilities.Variant.VIBRANT;

import android.annotation.SuppressLint;

import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.TonalPalette;

/**
 * Role tones of the Material 3 color spec 2025 ("Expressive"), ported from
 * material-color-utilities ColorSpec2025 (PHONE platform, contrast level 0)
 * on top of the scheme classes bundled with the material library. Foreground
 * roles keep their 2021 static tones; the visible 2025 changes (surface tier
 * tones, container tones and the chroma boost on neutral roles) are ported
 * verbatim.
 */
@SuppressLint("RestrictedApi")
final class MaterialColorsSpec2025 {

    private MaterialColorsSpec2025() {
    }

    static int getArgb(DynamicScheme s, String role) {
        switch (role) {
            case "primary": {
                TonalPalette p = s.primaryPalette;
                double tone;
                if (s.variant == NEUTRAL) {
                    tone = s.isDark ? 80.0 : 40.0;
                } else if (s.variant == TONAL_SPOT) {
                    tone = s.isDark ? 80.0 : tMaxC(p);
                } else if (s.variant == EXPRESSIVE) {
                    tone = tMaxC(p, 0, s.isDark
                            ? (isCyan(p.getHue()) ? 88 : 98)
                            : (isYellow(p.getHue()) ? 25 : 98));
                } else {
                    // VIBRANT and the remaining variants
                    tone = tMaxC(p, 0, isCyan(p.getHue()) ? 88 : 98);
                }
                return colorOf(p, 1.0, tone);
            }
            case "onPrimary":
                return colorOf(s.primaryPalette, 1.0, s.isDark ? 20.0 : 100.0);
            case "primaryContainer": {
                TonalPalette p = s.primaryPalette;
                double tone;
                if (s.variant == NEUTRAL) {
                    tone = s.isDark ? 30.0 : 90.0;
                } else if (s.variant == TONAL_SPOT) {
                    tone = s.isDark ? tMinC(p, 35, 93) : tMaxC(p, 0, 90);
                } else if (s.variant == EXPRESSIVE) {
                    tone = s.isDark ? tMinC(p, 30, 93)
                            : tMaxC(p, 78, isCyan(p.getHue()) ? 88 : 90);
                } else {
                    tone = s.isDark ? tMinC(p, 66, 93)
                            : tMaxC(p, 66, isCyan(p.getHue()) ? 88 : 93);
                }
                return colorOf(p, 1.0, tone);
            }
            case "onPrimaryContainer":
                return colorOf(s.primaryPalette, 1.0, s.isDark ? 90.0 : 10.0);
            case "secondary": {
                TonalPalette p = s.secondaryPalette;
                double tone;
                if (s.variant == NEUTRAL) {
                    tone = s.isDark ? tMinC(p, 0, 98) : tMaxC(p);
                } else if (s.variant == VIBRANT) {
                    tone = tMaxC(p, 0, s.isDark ? 90 : 98);
                } else {
                    // EXPRESSIVE and TONAL_SPOT
                    tone = s.isDark ? 80.0 : tMaxC(p);
                }
                return colorOf(p, 1.0, tone);
            }
            case "onSecondary":
                return colorOf(s.secondaryPalette, 1.0, s.isDark ? 20.0 : 100.0);
            case "secondaryContainer": {
                TonalPalette p = s.secondaryPalette;
                double tone;
                if (s.variant == VIBRANT) {
                    tone = s.isDark ? tMinC(p, 30, 40) : tMaxC(p, 84, 90);
                } else if (s.variant == EXPRESSIVE) {
                    tone = s.isDark ? 15.0 : tMaxC(p, 90, 95);
                } else {
                    tone = s.isDark ? 25.0 : 90.0;
                }
                return colorOf(p, 1.0, tone);
            }
            case "onSecondaryContainer":
                return colorOf(s.secondaryPalette, 1.0, s.isDark ? 90.0 : 10.0);
            case "tertiary": {
                TonalPalette p = s.tertiaryPalette;
                double tone;
                if (s.variant == EXPRESSIVE || s.variant == VIBRANT) {
                    tone = tMaxC(p, 0, isCyan(p.getHue()) ? 88 : (s.isDark ? 98 : 100));
                } else {
                    // NEUTRAL and TONAL_SPOT
                    tone = s.isDark ? tMaxC(p, 0, 98) : tMaxC(p);
                }
                return colorOf(p, 1.0, tone);
            }
            case "onTertiary":
                return colorOf(s.tertiaryPalette, 1.0, s.isDark ? 20.0 : 100.0);
            case "tertiaryContainer": {
                TonalPalette p = s.tertiaryPalette;
                double tone;
                if (s.variant == NEUTRAL) {
                    tone = s.isDark ? tMaxC(p, 0, 93) : tMaxC(p, 0, 96);
                } else if (s.variant == TONAL_SPOT) {
                    tone = tMaxC(p, 0, s.isDark ? 93 : 100);
                } else if (s.variant == EXPRESSIVE) {
                    tone = tMaxC(p, 75, isCyan(p.getHue()) ? 88 : (s.isDark ? 93 : 100));
                } else {
                    tone = s.isDark ? tMaxC(p, 0, 93) : tMaxC(p, 72, 100);
                }
                return colorOf(p, 1.0, tone);
            }
            case "onTertiaryContainer":
                return colorOf(s.tertiaryPalette, 1.0, s.isDark ? 90.0 : 10.0);
            case "surface": {
                double tone;
                if (s.isDark) {
                    tone = 4.0;
                } else if (isYellow(s.neutralPalette.getHue())) {
                    tone = 99.0;
                } else if (s.variant == VIBRANT) {
                    tone = 97.0;
                } else {
                    tone = 98.0;
                }
                return colorOf(s.neutralPalette, 1.0, tone);
            }
            case "onSurface":
                return colorOf(s.neutralPalette, 1.0, s.isDark ? 90.0 : 10.0);
            case "surfaceVariant":
                // Remapped to surfaceContainerHighest in the 2025 spec.
                return getArgb(s, "surfaceContainerHighest");
            case "onSurfaceVariant": {
                // neutral palette with a 2025 chroma boost, 2021 tones
                double mult = chromaMultiplier(s, 2.2, 1.7, 1.6, 2.3, 3.0, 1.0);
                return colorOf(s.neutralPalette, mult, s.isDark ? 80.0 : 30.0);
            }
            case "outline": {
                double mult = chromaMultiplier(s, 2.2, 1.7, 1.6, 2.3, 3.0, 1.0);
                return colorOf(s.neutralPalette, mult, s.isDark ? 60.0 : 50.0);
            }
            case "outlineVariant": {
                double mult = chromaMultiplier(s, 2.2, 1.7, 1.6, 2.3, 3.0, 1.0);
                return colorOf(s.neutralPalette, mult, s.isDark ? 30.0 : 80.0);
            }
            case "surfaceContainerLowest":
                return colorOf(s.neutralPalette, 1.0, s.isDark ? 0.0 : 100.0);
            case "surfaceContainerLow": {
                // dark 6; light: yellow 98 / vibrant 95 / other 96
                double tone = s.isDark ? 6.0
                        : isYellow(s.neutralPalette.getHue()) ? 98.0
                        : s.variant == VIBRANT ? 95.0 : 96.0;
                return colorOf(s.neutralPalette, chromaMultiplier(s, 1.3, 1.25, 1.15, 1.3, 1.3, 1.08), tone);
            }
            case "surfaceContainer": {
                double tone = s.isDark ? 9.0
                        : isYellow(s.neutralPalette.getHue()) ? 96.0
                        : s.variant == VIBRANT ? 92.0 : 94.0;
                return colorOf(s.neutralPalette, chromaMultiplier(s, 1.6, 1.4, 1.3, 1.6, 1.6, 1.15), tone);
            }
            case "surfaceContainerHigh": {
                double tone = s.isDark ? 12.0
                        : isYellow(s.neutralPalette.getHue()) ? 94.0
                        : s.variant == VIBRANT ? 90.0 : 92.0;
                return colorOf(s.neutralPalette, chromaMultiplier(s, 1.9, 1.5, 1.45, 1.95, 1.95, 1.22), tone);
            }
            case "surfaceContainerHighest": {
                double tone = s.isDark ? 15.0
                        : isYellow(s.neutralPalette.getHue()) ? 92.0
                        : s.variant == VIBRANT ? 88.0 : 90.0;
                return colorOf(s.neutralPalette, chromaMultiplier(s, 2.2, 1.7, 1.6, 2.3, 2.3, 1.29), tone);
            }
            default:
                throw new IllegalArgumentException("Unknown palette role: " + role);
        }
    }

    /**
     * Chroma multiplier the 2025 spec applies to roles drawn from the neutral
     * palette. The multiplier differs per variant and per surface tier, so
     * every tier passes its own set (expressive values are the non-yellow and
     * the yellow-hue variants; the last one is the dark-mode yellow value).
     */
    private static double chromaMultiplier(DynamicScheme s, double neutral, double tonalSpot,
                                           double expressive, double expressiveYellowLight,
                                           double expressiveYellowDark, double vibrant) {
        if (s.variant == NEUTRAL) {
            return neutral;
        } else if (s.variant == TONAL_SPOT) {
            return tonalSpot;
        } else if (s.variant == EXPRESSIVE) {
            return isYellow(s.neutralPalette.getHue())
                    ? (s.isDark ? expressiveYellowDark : expressiveYellowLight)
                    : expressive;
        } else if (s.variant == VIBRANT) {
            return vibrant;
        }
        return 1.0;
    }

    private static int colorOf(TonalPalette palette, double chromaMultiplier, double tone) {
        return Hct.from(palette.getHue(), palette.getChroma() * chromaMultiplier, tone).toInt();
    }

    private static boolean isYellow(double hue) {
        return hue >= 105.0 && hue < 135.0;
    }

    private static boolean isCyan(double hue) {
        return hue >= 170.0 && hue < 210.0;
    }

    /** Tone of the most chromatic color of the palette, clamped to [lowerBound, upperBound]. */
    private static double tMaxC(TonalPalette palette) {
        return clamp(findBestToneForChroma(palette, true), 0, 100);
    }

    private static double tMaxC(TonalPalette palette, double lowerBound, double upperBound) {
        return clamp(findBestToneForChroma(palette, true), lowerBound, upperBound);
    }

    private static double tMinC(TonalPalette palette, double lowerBound, double upperBound) {
        return clamp(findBestToneForChroma(palette, false), lowerBound, upperBound);
    }

    private static double findBestToneForChroma(TonalPalette palette, boolean maximize) {
        double bestTone = 0.0;
        double bestChroma = -1.0;
        for (double tone = 0.0; tone <= 100.0; tone += 1.0) {
            double chroma = Hct.from(palette.getHue(), palette.getChroma(), tone).getChroma();
            boolean better = maximize ? chroma > bestChroma : chroma < bestChroma;
            if (bestChroma < 0 || better) {
                bestChroma = chroma;
                bestTone = tone;
            }
        }
        return bestTone;
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.min(Math.max(value, lower), upper);
    }
}
