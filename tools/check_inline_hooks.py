"""Check per-package policy, resource wiring and native restoration without an Android SDK."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def check_resources():
    resources = ROOT / "app/src/main/res"
    names = {}
    for folder in ("values", "values-zh-rCN", "values-zh-rTW", "values-zh-rHK"):
        strings = ET.parse(resources / folder / "strings.xml").getroot()
        entries = [e.get("name") for e in strings if e.get("name", "").startswith("inline_hooks_")]
        assert len(entries) == len(set(entries)), f"Duplicate strings in {folder}"
        names[folder] = set(entries)
    assert len(names["values"]) == 8
    assert all(n == names["values"] for n in names.values())
    for relative in ("layout/item_inline_hook_notice.xml", "menu/menu_inline_hook_apps.xml",
                     "navigation/main_nav.xml", "xml/prefs.xml"):
        ET.parse(resources / relative)
    ui = (ROOT / "app/src/main/java/org/lsposed/manager/ui/fragment/InlineHookAppsFragment.java").read_text()
    for drawable in re.findall(r"R\.drawable\.(\w+)", ui):
        assert list(resources.glob(f"drawable*/{drawable}.*")), drawable
    for name in re.findall(r"R\.string\.(inline_hooks_\w+)", ui):
        assert name in names["values"], name
    ns = "{http://schemas.android.com/apk/res/android}"
    nav = ET.parse(resources / "navigation/main_nav.xml")
    assert any(e.get(ns + "name", "").endswith(".InlineHookAppsFragment") for e in nav.iter())
    prefs = ET.parse(resources / "xml/prefs.xml")
    setting = next(e for e in prefs.iter() if e.get(ns + "key") == "restore_inline_hooks")
    assert setting.tag == "Preference", "The entry must be a page, not a global switch"
    print("Inline hook resources and navigation: passed", flush=True)


def check_java():
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("Java checks require a JDK (21+); use --native-only on WSL.")
    with tempfile.TemporaryDirectory(prefix="lspd-inline-java-") as directory:
        sources = [
            ROOT / "daemon/src/main/java/org/lsposed/lspd/service/InlineHookPolicy.java",
            ROOT / "daemon/src/testInlineHooks/java/org/lsposed/lspd/service/InlineHookPolicyTest.java",
        ]
        subprocess.run([javac, "--release", "21", "-d", directory, *map(str, sources)], check=True)
        subprocess.run([java, "-cp", directory, "org.lsposed.lspd.service.InlineHookPolicyTest"],
                       check=True, timeout=20)


def check_native():
    compiler = shutil.which("g++")
    if compiler is None:
        raise SystemExit("Native checks require Linux/WSL with g++; use --java-only on Windows.")
    with tempfile.TemporaryDirectory(prefix="lspd-inline-native-") as directory:
        work = Path(directory)
        (work / "common").mkdir()
        (work / "common/logging.h").write_text(
            "#pragma once\n" + "".join(f"#define {name}(...) ((void)0)\n"
                                      for name in ("LOGE", "LOGW", "LOGI", "PLOGE")), encoding="utf-8")
        # Compile the actual loader callbacks too: lambda return deduction can break the
        # Android build even when the standalone restorer itself compiles successfully.
        module = (ROOT / "zygisk/src/main/jni/src/module.cpp").read_text()
        initializer = re.search(r"const lsplant::InitInfo init_info_\{.*?\n    \};", module, re.S)
        assert initializer
        callbacks = work / "callbacks.cpp"
        callbacks.write_text("""
#include <functional>
#include <string_view>
#include <core/art_inline_hook_invalidation.h>
using namespace lspd::native;
int HookInline(void *, void *, void **);
int UnhookInline(void *);
struct ArtSymbols {
    void *getSymbAddress(std::string_view);
    void *getSymbPrefixFirstAddress(std::string_view);
};
struct ElfSymbolCache { static ArtSymbols *GetArt(); };
namespace lsplant {
struct InitInfo {
    std::function<void *(void *, void *)> inline_hooker;
    std::function<bool(void *)> inline_unhooker;
    std::function<void *(std::string_view)> art_symbol_resolver;
    std::function<void *(std::string_view)> art_symbol_prefix_resolver;
};
}
""" + initializer.group(), encoding="utf-8")
        subprocess.run([compiler, "-std=c++23", "-Wall", "-Wextra", "-Werror", "-fsyntax-only",
                        "-I", str(ROOT / "native/include"), str(callbacks)], check=True)
        executable = work / "inline_hook_test"
        subprocess.run([compiler, "-std=c++23", "-pthread", "-Wall", "-Wextra", "-Werror",
                        "-Wno-unused-variable", "-fsanitize=undefined", "-fno-sanitize-recover=all",
                        "-I", str(work), "-I", str(ROOT / "native/include"),
                        str(ROOT / "native/src/core/art_inline_hook_cleanup.cpp"),
                        str(ROOT / "native/tests/art_inline_hook_test.cpp"), "-o", str(executable)],
                       check=True)
        subprocess.run([str(executable)], check=True, timeout=20)
        print("LSPlant loader callback types: passed", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--java-only", action="store_true")
    mode.add_argument("--native-only", action="store_true")
    args = parser.parse_args()
    check_resources()
    if not args.native_only:
        check_java()
    if not args.java_only:
        check_native()
