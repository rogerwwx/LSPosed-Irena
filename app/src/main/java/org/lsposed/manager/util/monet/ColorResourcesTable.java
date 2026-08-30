/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.lsposed.manager.util.monet;

import android.content.res.Resources;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a minimal resources.arsc color table for {@link android.content.res.loader.ResourcesLoader}.
 *
 * <p>The table declares the application package (id 0x7f, name matching the
 * app) and a single "color" type placed at the same type index the app's
 * R.color entries use, with each overridden color at its original entry
 * index. Non-overridden entries are emitted as NO_ENTRY so the framework
 * falls through to the application's own values. Two configurations are
 * written: an unqualified (light) one and a night-qualified one.
 */
final class ColorResourcesTable {

    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;
    private static final int RES_TABLE_TYPE_SPEC_TYPE = 0x0202;
    private static final int RES_TABLE_TYPE_TYPE = 0x0201;

    private static final int TABLE_HEADER_SIZE = 12;
    private static final int STRING_POOL_HEADER_SIZE = 28;
    // ResTable_package includes the trailing typeIdOffset field (288 bytes);
    // LoadedArsc rejects packages whose headerSize is below the struct size.
    private static final int PACKAGE_HEADER_SIZE = 288;
    private static final int TYPE_SPEC_HEADER_SIZE = 16;
    private static final int TYPE_HEADER_SIZE_WITH_CONFIG = 52;
    private static final int CONFIG_SIZE = 32;

    private static final int NO_ENTRY = 0xFFFFFFFF;
    private static final int SPEC_PUBLIC = 0x40000000;
    private static final int VALUE_TYPE_COLOR = 0x1C; // INT_COLOR_ARGB8
    private static final int UI_MODE_NIGHT_YES = 0x20;

    private ColorResourcesTable() {
    }

    /**
     * @param packageName application package name (must match, the table joins
     *                    the package group by id and name)
     * @param resources   application resources, used to resolve the resource ids
     * @param lightColors resource name -> ARGB for the unqualified config
     * @param nightColors resource name -> ARGB for the night config
     */
    static ByteBuffer create(String packageName, Resources resources,
                             Map<String, Integer> lightColors, Map<String, Integer> nightColors) {
        // entry index (resource id low 16 bits) -> values
        TreeMap<Integer, String> indexToName = new TreeMap<>();
        for (String name : lightColors.keySet()) {
            int id = resources.getIdentifier(name, "color", packageName);
            if (id == 0) {
                throw new IllegalStateException("Palette color not found: " + name);
            }
            indexToName.put(id & 0xFFFF, name);
        }
        if (indexToName.isEmpty()) {
            throw new IllegalStateException("No palette colors given");
        }
        int entryCount = indexToName.lastKey() + 1;
        int typeByte = (resources.getIdentifier(
                indexToName.firstEntry().getValue(), "color", packageName) >> 16) & 0xFF;

        ByteBuffer typeStrings = stringPool(typeStrings(typeByte));
        ByteBuffer keyStrings = stringPool(new ArrayList<>(indexToName.values()));
        ByteBuffer typeSpec = typeSpec(typeByte, entryCount, indexToName.keySet());
        ByteBuffer typeLight = typeChunk(typeByte, entryCount, indexToName, lightColors, false);
        ByteBuffer typeNight = typeChunk(typeByte, entryCount, indexToName, nightColors, true);

        int packageSize = PACKAGE_HEADER_SIZE
                + typeStrings.remaining() + keyStrings.remaining()
                + typeSpec.remaining() + typeLight.remaining() + typeNight.remaining();

        ByteBuffer table = ByteBuffer.allocate(TABLE_HEADER_SIZE + STRING_POOL_HEADER_SIZE + packageSize);
        table.order(ByteOrder.LITTLE_ENDIAN);

        table.putShort((short) RES_TABLE_TYPE);
        table.putShort((short) TABLE_HEADER_SIZE);
        table.putInt(table.capacity());
        table.putInt(1); // packageCount

        writeEmptyStringPool(table);

        int packageStart = table.position();
        table.putShort((short) RES_TABLE_PACKAGE_TYPE);
        table.putShort((short) PACKAGE_HEADER_SIZE);
        table.putInt(packageSize);
        table.putInt(0x7F); // package id
        for (int i = 0; i < 128; i++) { // package name, UTF-16, zero padded
            char c = i < packageName.length() ? packageName.charAt(i) : 0;
            table.putChar(c);
        }
        int typeStringsOffset = table.position() - packageStart;
        table.putInt(typeStringsOffset);
        table.putInt(typeByte); // lastPublicType = type string count (dummies + "color")
        int keyStringsOffset = typeStringsOffset + typeStrings.remaining();
        table.putInt(keyStringsOffset);
        table.putInt(indexToName.size()); // lastPublicKey
        table.putInt(0); // typeIdOffset (dense table)

        table.put(typeStrings.duplicate());
        table.position(packageStart + keyStringsOffset);
        table.put(keyStrings.duplicate());
        table.position(packageStart + keyStringsOffset + keyStrings.remaining());
        table.put(typeSpec.duplicate());
        table.position(packageStart + keyStringsOffset + keyStrings.remaining() + typeSpec.remaining());
        table.put(typeLight.duplicate());
        table.position(packageStart + keyStringsOffset + keyStrings.remaining()
                + typeSpec.remaining() + typeLight.remaining());
        table.put(typeNight.duplicate());

        table.flip();
        return table;
    }

    private static List<String> typeStrings(int colorTypeByte) {
        // The pool index of "color" must equal (colorTypeByte - 1); pad with
        // placeholder names for the types before it.
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < colorTypeByte - 1; i++) {
            strings.add("t" + i);
        }
        strings.add("color");
        return strings;
    }

    private static void writeEmptyStringPool(ByteBuffer out) {
        out.putShort((short) RES_STRING_POOL_TYPE);
        out.putShort((short) STRING_POOL_HEADER_SIZE);
        out.putInt(STRING_POOL_HEADER_SIZE);
        out.putInt(0); // string count
        out.putInt(0); // style count
        out.putInt(0x100); // UTF-8 flag
        out.putInt(STRING_POOL_HEADER_SIZE); // strings start
        out.putInt(0); // styles start
    }

    private static ByteBuffer stringPool(List<String> strings) {
        List<byte[]> encoded = new ArrayList<>();
        int stringsSize = 0;
        for (String s : strings) {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // u8 char length, u8 byte length, payload, terminator
            int len = 1 + 1 + bytes.length + 1;
            encoded.add(bytes);
            stringsSize += len;
        }
        int offsetsSize = 4 * strings.size();
        int stringsStart = STRING_POOL_HEADER_SIZE + align4(offsetsSize);
        int size = align4(stringsStart + stringsSize);

        ByteBuffer out = ByteBuffer.allocate(size);
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_STRING_POOL_TYPE);
        out.putShort((short) STRING_POOL_HEADER_SIZE);
        out.putInt(size);
        out.putInt(strings.size());
        out.putInt(0); // style count
        out.putInt(0x100); // UTF-8
        out.putInt(stringsStart);
        out.putInt(0); // styles start

        int offset = 0;
        int stringIndex = 0;
        for (String s : strings) {
            out.putInt(stringsStart + offset);
            byte[] bytes = encoded.get(stringIndex++);
            offset += 1 + 1 + bytes.length + 1;
        }
        out.position(STRING_POOL_HEADER_SIZE + offsetsSize);
        out.position(align4(out.position()));
        stringIndex = 0;
        for (String s : strings) {
            byte[] bytes = encoded.get(stringIndex++);
            out.put((byte) s.length());
            out.put((byte) bytes.length);
            out.put(bytes);
            out.put((byte) 0);
        }
        while (out.position() % 4 != 0) {
            out.put((byte) 0);
        }
        out.flip();
        return out;
    }

    private static ByteBuffer typeSpec(int typeByte, int entryCount, java.util.Set<Integer> overridden) {
        int size = TYPE_SPEC_HEADER_SIZE + 4 * entryCount;
        ByteBuffer out = ByteBuffer.allocate(size);
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_TABLE_TYPE_SPEC_TYPE);
        out.putShort((short) TYPE_SPEC_HEADER_SIZE);
        out.putInt(size);
        out.put((byte) typeByte);
        out.put((byte) 0); // res0
        out.putShort((short) 0); // res1
        out.putInt(entryCount);
        for (int i = 0; i < entryCount; i++) {
            out.putInt(overridden.contains(i) ? SPEC_PUBLIC : 0);
        }
        out.flip();
        return out;
    }

    private static ByteBuffer typeChunk(int typeByte, int entryCount,
                                        TreeMap<Integer, String> indexToName,
                                        Map<String, Integer> values, boolean night) {
        int entriesStart = TYPE_HEADER_SIZE_WITH_CONFIG + 4 * entryCount;
        // entries must be laid out in ascending entry index order
        List<Integer> indices = new ArrayList<>(indexToName.keySet());
        List<byte[]> entries = new ArrayList<>();
        int entriesSize = 0;
        for (int index : indices) {
            int argb = values.get(indexToName.get(index));
            // ResTable_entry (8) + Res_value (8)
            ByteBuffer entry = ByteBuffer.allocate(16);
            entry.order(ByteOrder.LITTLE_ENDIAN);
            entry.putShort((short) 8); // entry size
            entry.putShort((short) 0); // flags: simple
            entry.putInt(keyIndex(indexToName, index));
            entry.putShort((short) 8); // Res_value size
            entry.put((byte) 0); // res0
            entry.put((byte) VALUE_TYPE_COLOR);
            entry.putInt(argb);
            entries.add(entry.array());
            entriesSize += 16;
        }
        int size = entriesStart + entriesSize;

        ByteBuffer out = ByteBuffer.allocate(size);
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_TABLE_TYPE_TYPE);
        out.putShort((short) TYPE_HEADER_SIZE_WITH_CONFIG);
        out.putInt(size);
        out.put((byte) typeByte);
        out.put((byte) 0); // flags: not sparse/compact
        out.putShort((short) 0); // reserved
        out.putInt(entryCount);
        out.putInt(entriesStart);
        writeConfig(out, night);

        int offset = 0;
        int entryIdx = 0;
        for (int i = 0; i < entryCount; i++) {
            if (indices.contains(i)) {
                out.putInt(offset);
                offset += 16;
            } else {
                out.putInt(NO_ENTRY);
            }
        }
        for (byte[] entry : entries) {
            out.put(entry);
        }
        out.flip();
        return out;
    }

    private static int keyIndex(TreeMap<Integer, String> indexToName, int entryIndex) {
        // keys were written in iteration (ascending index) order
        int key = 0;
        for (int index : indexToName.keySet()) {
            if (index == entryIndex) {
                return key;
            }
            key++;
        }
        return 0;
    }

    private static void writeConfig(ByteBuffer out, boolean night) {
        int start = out.position();
        out.putInt(CONFIG_SIZE);
        // bytes 4..31 stay zero; uiMode lives at offset 29 within the config
        for (int i = 4; i < CONFIG_SIZE; i++) {
            if (night && i == 29) {
                out.put((byte) UI_MODE_NIGHT_YES);
            } else {
                out.put((byte) 0);
            }
        }
        if (out.position() - start != CONFIG_SIZE) {
            throw new IllegalStateException();
        }
    }

    private static int align4(int value) {
        return (value + 3) & ~3;
    }
}
