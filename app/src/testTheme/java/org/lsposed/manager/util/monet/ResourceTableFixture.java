package org.lsposed.manager.util.monet;

import org.lsposed.manager.theme.ResolvedPalette;
import org.lsposed.manager.theme.ThemeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Produces sparse IDs and renamed entries, as seen after release resource optimization. */
public final class ResourceTableFixture {
    public static void main(String[] args) throws Exception {
        var palette = ResolvedPalette.generate(ThemeConfig.from(Map.of()), ThemeConfig.DEFAULT_SEED);
        var light = new LinkedHashMap<Integer, Integer>();
        var dark = new LinkedHashMap<Integer, Integer>();
        var expected = new StringBuilder();
        int index = 3;
        for (String role : palette.light().keySet()) {
            int id = 0x7f060000 | index;
            light.put(id, palette.light().get(role));
            dark.put(id, palette.dark().get(role));
            expected.append(String.format("%08x %08x %08x%n", id, light.get(id), dark.get(id)));
            index += 7;
        }
        var table = ColorResourcesTable.create("org.lsposed.manager", id -> "r" + (id & 0xffff), light, dark);
        Path output = Path.of(args[0]);
        Files.write(output.resolve("palette.arsc"), table.array());
        Files.writeString(output.resolve("palette-expected.txt"), expected);
    }
}
