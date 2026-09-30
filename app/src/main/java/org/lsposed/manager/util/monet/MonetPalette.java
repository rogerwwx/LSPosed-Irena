/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.util.monet;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import org.lsposed.manager.R;
import org.lsposed.manager.theme.ResolvedPalette;
import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Android adapter for a pure palette. Owns only loaders created by this class. */
public final class MonetPalette {
    private record Attachment(ResourcesLoader loader, ResourcesProvider provider, String key) {}
    private static final Map<Resources, Attachment> ATTACHED = new WeakHashMap<>();
    private static final Map<String, Integer> ROLE_IDS = new LinkedHashMap<>();
    static {
        ROLE_IDS.put("primary", R.color.lsposed_m3e_primary);
        ROLE_IDS.put("onPrimary", R.color.lsposed_m3e_on_primary);
        ROLE_IDS.put("primaryContainer", R.color.lsposed_m3e_primary_container);
        ROLE_IDS.put("onPrimaryContainer", R.color.lsposed_m3e_on_primary_container);
        ROLE_IDS.put("secondary", R.color.lsposed_m3e_secondary);
        ROLE_IDS.put("onSecondary", R.color.lsposed_m3e_on_secondary);
        ROLE_IDS.put("secondaryContainer", R.color.lsposed_m3e_secondary_container);
        ROLE_IDS.put("onSecondaryContainer", R.color.lsposed_m3e_on_secondary_container);
        ROLE_IDS.put("tertiary", R.color.lsposed_m3e_tertiary);
        ROLE_IDS.put("onTertiary", R.color.lsposed_m3e_on_tertiary);
        ROLE_IDS.put("tertiaryContainer", R.color.lsposed_m3e_tertiary_container);
        ROLE_IDS.put("onTertiaryContainer", R.color.lsposed_m3e_on_tertiary_container);
        ROLE_IDS.put("surface", R.color.lsposed_m3e_surface);
        ROLE_IDS.put("onSurface", R.color.lsposed_m3e_on_surface);
        ROLE_IDS.put("surfaceVariant", R.color.lsposed_m3e_surface_variant);
        ROLE_IDS.put("onSurfaceVariant", R.color.lsposed_m3e_on_surface_variant);
        ROLE_IDS.put("outline", R.color.lsposed_m3e_outline);
        ROLE_IDS.put("outlineVariant", R.color.lsposed_m3e_outline_variant);
        ROLE_IDS.put("surfaceContainerLowest", R.color.lsposed_m3e_surface_container_lowest);
        ROLE_IDS.put("surfaceContainerLow", R.color.lsposed_m3e_surface_container_low);
        ROLE_IDS.put("surfaceContainer", R.color.lsposed_m3e_surface_container);
        ROLE_IDS.put("surfaceContainerHigh", R.color.lsposed_m3e_surface_container_high);
        ROLE_IDS.put("surfaceContainerHighest", R.color.lsposed_m3e_surface_container_highest);
        ROLE_IDS.put("background", R.color.lsposed_m3e_background);
        ROLE_IDS.put("onBackground", R.color.lsposed_m3e_on_background);
        ROLE_IDS.put("surfaceDim", R.color.lsposed_m3e_surface_dim);
        ROLE_IDS.put("surfaceBright", R.color.lsposed_m3e_surface_bright);
        ROLE_IDS.put("inverseSurface", R.color.lsposed_m3e_inverse_surface);
        ROLE_IDS.put("inverseOnSurface", R.color.lsposed_m3e_inverse_on_surface);
        ROLE_IDS.put("inversePrimary", R.color.lsposed_m3e_inverse_primary);
        ROLE_IDS.put("error", R.color.lsposed_m3e_error);
        ROLE_IDS.put("onError", R.color.lsposed_m3e_on_error);
        ROLE_IDS.put("errorContainer", R.color.lsposed_m3e_error_container);
        ROLE_IDS.put("onErrorContainer", R.color.lsposed_m3e_on_error_container);
        ROLE_IDS.put("surfaceTint", R.color.lsposed_m3e_surface_tint);
        ROLE_IDS.put("primaryFixed", R.color.lsposed_m3e_primary_fixed);
        ROLE_IDS.put("primaryFixedDim", R.color.lsposed_m3e_primary_fixed_dim);
        ROLE_IDS.put("onPrimaryFixed", R.color.lsposed_m3e_on_primary_fixed);
        ROLE_IDS.put("onPrimaryFixedVariant", R.color.lsposed_m3e_on_primary_fixed_variant);
        ROLE_IDS.put("secondaryFixed", R.color.lsposed_m3e_secondary_fixed);
        ROLE_IDS.put("secondaryFixedDim", R.color.lsposed_m3e_secondary_fixed_dim);
        ROLE_IDS.put("onSecondaryFixed", R.color.lsposed_m3e_on_secondary_fixed);
        ROLE_IDS.put("onSecondaryFixedVariant", R.color.lsposed_m3e_on_secondary_fixed_variant);
        ROLE_IDS.put("tertiaryFixed", R.color.lsposed_m3e_tertiary_fixed);
        ROLE_IDS.put("tertiaryFixedDim", R.color.lsposed_m3e_tertiary_fixed_dim);
        ROLE_IDS.put("onTertiaryFixed", R.color.lsposed_m3e_on_tertiary_fixed);
        ROLE_IDS.put("onTertiaryFixedVariant", R.color.lsposed_m3e_on_tertiary_fixed_variant);
    }
    private MonetPalette() {}

    public static synchronized boolean apply(Context context, ResolvedPalette palette, String key) {
        if (Build.VERSION.SDK_INT < 30) return false;
        Resources resources = context.getResources();
        Attachment previous = ATTACHED.get(resources);
        if (palette != null && previous != null && previous.key().equals(key)) return true;
        Attachment next = null;
        try {
            if (palette != null) next = build(context, palette, key);
            // Add first so a construction failure leaves the old attachment intact.
            if (next != null) resources.addLoaders(next.loader());
            if (previous != null) resources.removeLoaders(previous.loader());
            ATTACHED.remove(resources);
            if (previous != null) release(previous);
            if (next != null) ATTACHED.put(resources, next);
            return next != null;
        } catch (Exception | LinkageError failure) {
            Log.e("ThemePalette", "Unable to install palette " + key, failure);
            if (next != null) {
                try { resources.removeLoaders(next.loader()); } catch (RuntimeException ignored) {}
                release(next);
            }
            // A failed application never selects the palette theme overlay.
            return false;
        }
    }

    private static void release(Attachment attachment) {
        try {
            attachment.loader().clearProviders();
            attachment.provider().close();
        } catch (RuntimeException failure) {
            Log.w("ThemePalette", "Unable to release unused palette provider", failure);
        }
    }

    @androidx.annotation.RequiresApi(30)
    private static Attachment build(Context context, ResolvedPalette palette, String key) throws Exception {
        Map<Integer, Integer> light = new LinkedHashMap<>();
        Map<Integer, Integer> dark = new LinkedHashMap<>();
        ROLE_IDS.forEach((role, id) -> {
            light.put(id, palette.light().get(role));
            dark.put(id, palette.dark().get(role));
        });
        var table = ColorResourcesTable.create(context.getPackageName(),
                context.getResources()::getResourceEntryName, light, dark);
        File file = File.createTempFile("theme_palette_", ".arsc", context.getCacheDir());
        try {
            try (var output = new FileOutputStream(file)) {
                output.write(table.array(), table.arrayOffset() + table.position(), table.remaining());
            }
            try (var descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                ResourcesProvider provider = ResourcesProvider.loadFromTable(descriptor, null);
                ResourcesLoader loader = new ResourcesLoader();
                try { loader.addProvider(provider); }
                catch (RuntimeException failure) { provider.close(); throw failure; }
                return new Attachment(loader, provider, key);
            }
        } finally {
            if (!file.delete()) file.deleteOnExit();
        }
    }
}
