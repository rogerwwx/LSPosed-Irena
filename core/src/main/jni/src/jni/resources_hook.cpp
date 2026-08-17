/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include "dex_builder.h"
#include "framework/androidfw/resource_types.h"
#include "elf_util.h"
#include "native_util.h"
#include "resources_hook.h"
#include "config_bridge.h"

using namespace lsplant;

namespace lspd {
    using TYPE_GET_ATTR_NAME_ID = int32_t (*)(void *, size_t);

    using TYPE_GET_ATTR_NAME_RES_ID = uint32_t (*)(void *, size_t);

    using TYPE_GET_STRINGS = const android::ResStringPool *(*)(void *);

    using TYPE_RESTART = void (*)(void *);

    using TYPE_NEXT = int32_t (*)(void *);

    static jclass classXResources;
    static jmethodID methodXResourcesTranslateAttrId;
    static jmethodID methodXResourcesTranslateResId;

    static TYPE_NEXT ResXMLParser_next = nullptr;
    static TYPE_RESTART ResXMLParser_restart = nullptr;
    static TYPE_GET_ATTR_NAME_ID ResXMLParser_getAttributeNameID = nullptr;
    static TYPE_GET_ATTR_NAME_RES_ID ResXMLParser_getAttributeNameResID = nullptr;
    static TYPE_GET_STRINGS ResXMLParser_getStrings = nullptr;

    static std::string GetXResourcesClassName() {
        auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
        if (obfs_map.empty()) {
            LOGW("GetXResourcesClassName: obfuscation_map empty?????");
        }
        static auto name = lspd::JavaNameToSignature(
                obfs_map.at("android.content.res.XRes"))  // TODO: kill this hardcoded name
                    .substr(1) + "ources";
        LOGD("{}", name.c_str());
        return name;
    }

    static bool PrepareSymbols() {
        SandHook::ElfImg fw(kLibFwName);
        if (!fw.isValid()) {
            return false;
        };
        if (!(ResXMLParser_next = fw.getSymbAddress<TYPE_NEXT>(
                "_ZN7android12ResXMLParser4nextEv"))) {
            return false;
        }
        if (!(ResXMLParser_restart = fw.getSymbAddress<TYPE_RESTART>(
                "_ZN7android12ResXMLParser7restartEv"))) {
            return false;
        };
        if (!(ResXMLParser_getAttributeNameID = fw.getSymbAddress<TYPE_GET_ATTR_NAME_ID>(
                LP_SELECT("_ZNK7android12ResXMLParser18getAttributeNameIDEj",
                          "_ZNK7android12ResXMLParser18getAttributeNameIDEm")))) {
            return false;
        }
        if (!(ResXMLParser_getAttributeNameResID =
                      fw.getSymbAddress<TYPE_GET_ATTR_NAME_RES_ID>(
                              LP_SELECT("_ZNK7android12ResXMLParser21getAttributeNameResIDEj",
                                        "_ZNK7android12ResXMLParser21getAttributeNameResIDEm")))) {
            LOGW("Failed to find symbol: ResXMLParser::getAttributeNameResID");
        }
        if (!(ResXMLParser_getStrings = fw.getSymbAddress<TYPE_GET_STRINGS>(
                      "_ZNK7android12ResXMLParser10getStringsEv"))) {
            LOGW("Failed to find symbol: ResXMLParser::getStrings");
        }
        return android::ResStringPool::setup(InitInfo{
            .art_symbol_resolver = [&](auto s) {
                return fw.template getSymbAddress<>(s);
            }
        });
    }

    LSP_DEF_NATIVE_METHOD(jboolean, ResourcesHook, initXResourcesNative) {
        const auto x_resources_class_name = GetXResourcesClassName();
        if (auto classXResources_ = Context::GetInstance()->FindClassFromCurrentLoader(env,
                                                                                       x_resources_class_name)) {
            classXResources = JNI_NewGlobalRef(env, classXResources_);
        } else {
            LOGE("Error while loading XResources class '{}':", x_resources_class_name);
            return JNI_FALSE;
        }
        methodXResourcesTranslateResId = JNI_GetStaticMethodID(
                env, classXResources, "translateResId",
                fmt::format("(IL{};Landroid/content/res/Resources;)I", x_resources_class_name));
        if (!methodXResourcesTranslateResId) {
            return JNI_FALSE;
        }
        methodXResourcesTranslateAttrId = JNI_GetStaticMethodID(
                env, classXResources, "translateAttrId",
                fmt::format("(Ljava/lang/String;L{};)I", x_resources_class_name));
        if (!methodXResourcesTranslateAttrId) {
            return JNI_FALSE;
        }
        if (!PrepareSymbols()) {
            return JNI_FALSE;
        }
        return JNI_TRUE;
    }

    // @ApiSensitive(Level.MIDDLE)
    LSP_DEF_NATIVE_METHOD(jboolean, ResourcesHook, makeInheritable, jclass target_class) {
        if (lsplant::MakeClassInheritable(env, target_class)) {
            return JNI_TRUE;
        }
        return JNI_FALSE;
    }

    LSP_DEF_NATIVE_METHOD(jobject, ResourcesHook, buildDummyClassLoader, jobject parent,
                          jstring resource_super_class, jstring typed_array_super_class) {
        using namespace startop::dex;
        static auto in_memory_classloader = JNI_NewGlobalRef(env, JNI_FindClass(env,
                                                                                "dalvik/system/InMemoryDexClassLoader"));
        static jmethodID initMid = JNI_GetMethodID(env, in_memory_classloader, "<init>",
                                                   "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        DexBuilder dex_file;

        ClassBuilder xresource_builder{
                dex_file.MakeClass("xposed.dummy.XResourcesSuperClass")};
        xresource_builder.setSuperClass(TypeDescriptor::FromClassname(JUTFString(env, resource_super_class).get()));

        ClassBuilder xtypearray_builder{
                dex_file.MakeClass("xposed.dummy.XTypedArraySuperClass")};
        xtypearray_builder.setSuperClass(TypeDescriptor::FromClassname(JUTFString(env, typed_array_super_class).get()));

        slicer::MemView image{dex_file.CreateImage()};

        auto dex_buffer = env->NewDirectByteBuffer(const_cast<void *>(image.ptr()), image.size());
        return JNI_NewObject(env, in_memory_classloader, initMid,
                             dex_buffer, parent).release();
    }

    // The attribute map lives behind a private ResStringPool whose size changes across Android
    // releases. Probe mapped pages before inspecting candidate fields so a stale layout cannot turn
    // the search into an invalid read.
    static bool IsMapped(uintptr_t addr, size_t len) {
        static const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
        if (page == 0) return false;
        const uintptr_t start = addr & ~(page - 1);
        const size_t span = ((addr + len) - start + page - 1) & ~(page - 1);
        return msync(reinterpret_cast<void *>(start), span, MS_ASYNC) == 0;
    }

    static constexpr size_t kMinMapOffset = LP_SELECT(0x20, 0x40);
    static constexpr size_t kMaxMapOffset = LP_SELECT(0xa0, 0x140);
    static constexpr size_t kMaxMapEntries = 0x4000;
    static constexpr size_t kMapUnusable = ~static_cast<size_t>(0);
    static std::atomic<size_t> attr_map_offset{0};
    static constexpr int kMaxMapSearches = 3;
    static std::atomic<int> attr_map_searches{0};

    // A candidate is the attribute map only if every non-zero ID reported by the parser appears at
    // the string index reported for the same attribute.
    static bool MapsCurrentAttributes(void *parser, const uint32_t *map, size_t count,
                                      size_t attrCount) {
        bool matched = false;
        for (size_t idx = 0; idx < attrCount; idx++) {
            auto resID = ResXMLParser_getAttributeNameResID(parser, idx);
            if (resID == 0) continue;
            auto nameID = ResXMLParser_getAttributeNameID(parser, idx);
            if (nameID < 0 || static_cast<size_t>(nameID) >= count) return false;
            if (map[nameID] != resID) return false;
            matched = true;
        }
        return matched;
    }

    static size_t FindAttributeNameMap(void *parser, uintptr_t pool, size_t attrCount) {
        for (size_t off = kMinMapOffset; off <= kMaxMapOffset; off += sizeof(void *)) {
            if (!IsMapped(pool + off, sizeof(void *) + sizeof(size_t))) break;
            auto candidate = *reinterpret_cast<uint32_t *const *>(pool + off);
            auto count = *reinterpret_cast<const size_t *>(pool + off + sizeof(void *));
            if (candidate == nullptr || count == 0 || count > kMaxMapEntries) continue;
            if (reinterpret_cast<uintptr_t>(candidate) % alignof(uint32_t) != 0) continue;
            if (!IsMapped(reinterpret_cast<uintptr_t>(candidate), count * sizeof(uint32_t))) {
                continue;
            }
            if (!MapsCurrentAttributes(parser, candidate, count, attrCount)) continue;
            return off;
        }
        return 0;
    }

    // Returns the writable slot holding an attribute name's resource ID. The offset is discovered
    // once per process and is revalidated against each document before any framework memory write.
    static uint32_t *AttributeNameSlot(void *parser, const android::ResStringPool *strings,
                                       size_t attrCount, int32_t nameID, uint32_t resID,
                                       bool &searchedHere) {
        const auto pool = reinterpret_cast<uintptr_t>(strings);
        auto offset = attr_map_offset.load(std::memory_order_relaxed);
        if (offset == kMapUnusable) return nullptr;
        if (offset == 0) {
            if (searchedHere) return nullptr;
            searchedHere = true;
            offset = FindAttributeNameMap(parser, pool, attrCount);
            if (offset == 0) {
                if (attr_map_searches.fetch_add(1, std::memory_order_relaxed) + 1 <
                    kMaxMapSearches) {
                    return nullptr;
                }
                size_t unset = 0;
                if (!attr_map_offset.compare_exchange_strong(unset, kMapUnusable,
                                                             std::memory_order_relaxed)) {
                    return nullptr;
                }
                LOGW("Could not locate the attribute name map; leaving attribute names untranslated");
                return nullptr;
            }
            attr_map_offset.store(offset, std::memory_order_relaxed);
        }

        auto map = *reinterpret_cast<uint32_t *const *>(pool + offset);
        auto count = *reinterpret_cast<const size_t *>(pool + offset + sizeof(void *));
        if (map == nullptr || count > kMaxMapEntries) return nullptr;
        if (static_cast<size_t>(nameID) >= count) return nullptr;
        auto slot = map + static_cast<size_t>(nameID);
        return *slot == resID ? slot : nullptr;
    }

    LSP_DEF_NATIVE_METHOD(void, ResourcesHook, rewriteXmlReferencesNative,
                          jlong parserPtr, jobject origRes, jobject repRes) {
        auto parser = (android::ResXMLParser *) parserPtr;

        if (parser == nullptr)
            return;

        auto strings = ResXMLParser_getStrings != nullptr &&
                       ResXMLParser_getAttributeNameResID != nullptr
                           ? ResXMLParser_getStrings(parser)
                           : nullptr;
        android::ResXMLTree_attrExt *tag;
        size_t attrCount;
        bool searchedHere = false;

        do {
            switch (ResXMLParser_next(parser)) {
                case android::ResXMLParser::START_TAG:
                    tag = (android::ResXMLTree_attrExt *) parser->mCurExt;
                    attrCount = tag->attributeCount;
                    for (size_t idx = 0; idx < attrCount; idx++) {
                        auto attr = (android::ResXMLTree_attribute *)
                                (((const uint8_t *) tag)
                                 + tag->attributeStart
                                 + tag->attributeSize * idx);

                        int32_t attrNameID = ResXMLParser_getAttributeNameID(parser, idx);
                        uint32_t oldAttrResID = strings != nullptr
                                                    ? ResXMLParser_getAttributeNameResID(parser, idx)
                                                    : 0;
                        uint32_t *nameSlot = attrNameID >= 0 && oldAttrResID >= 0x7f000000
                                                     ? AttributeNameSlot(parser, strings, attrCount,
                                                                         attrNameID, oldAttrResID,
                                                                         searchedHere)
                                                     : nullptr;
                        if (nameSlot != nullptr) {
                            auto attrName = strings->stringAt(attrNameID);
                            if (attrName.data_ != nullptr) {
                                auto attrNameStr = env->NewString(
                                        reinterpret_cast<const jchar *>(attrName.data_),
                                        attrName.length_);
                                if (env->ExceptionCheck()) goto leave;

                                jint attrResID = env->CallStaticIntMethod(
                                        classXResources, methodXResourcesTranslateAttrId,
                                        attrNameStr, origRes);
                                env->DeleteLocalRef(attrNameStr);
                                if (env->ExceptionCheck()) goto leave;

                                *nameSlot = attrResID;
                            }
                        }

                        // find original resource IDs for reference values (app packages only)
                        if (attr->typedValue.dataType != android::Res_value::TYPE_REFERENCE)
                            continue;

                        jint oldValue = attr->typedValue.data;
                        if (oldValue < 0x7f000000)
                            continue;

                        jint newValue = env->CallStaticIntMethod(classXResources,
                                                                 methodXResourcesTranslateResId,
                                                                 oldValue, origRes, repRes);
                        if (env->ExceptionCheck())
                            goto leave;

                        if (newValue != oldValue)
                            attr->typedValue.data = newValue;
                    }
                    continue;
                case android::ResXMLParser::END_DOCUMENT:
                case android::ResXMLParser::BAD_DOCUMENT:
                    goto leave;
                default:
                    continue;
            }
        } while (true);

        leave:
        ResXMLParser_restart(parser);
    }

    static JNINativeMethod gMethods[] = {
            LSP_NATIVE_METHOD(ResourcesHook, initXResourcesNative, "()Z"),
            LSP_NATIVE_METHOD(ResourcesHook, makeInheritable,"(Ljava/lang/Class;)Z"),
            LSP_NATIVE_METHOD(ResourcesHook, buildDummyClassLoader,
                              "(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/ClassLoader;"),
            LSP_NATIVE_METHOD(ResourcesHook, rewriteXmlReferencesNative,
                              "(JLandroid/content/res/XResources;Landroid/content/res/Resources;)V")
    };

    void RegisterResourcesHook(JNIEnv *env) {
        auto sign = fmt::format("(JL{};Landroid/content/res/Resources;)V", GetXResourcesClassName());
        gMethods[3].signature = sign.c_str();

        REGISTER_LSP_NATIVE_METHODS(ResourcesHook);
    }
}
