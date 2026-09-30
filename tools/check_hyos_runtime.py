"""Exercise the production HyperOS companion with real Unix sockets (Linux/WSL, g++)."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path,
                    default=ROOT / "zygisk/src/main/jni/src/hyos_runtime.cpp",
                    help="Override source to reproduce a regression against an earlier revision")
args = parser.parse_args()
source = args.source.resolve()
compiler = shutil.which("g++")
if compiler is None:
    raise SystemExit("Run this check on Linux/WSL with g++ installed.")

with tempfile.TemporaryDirectory(prefix="lspd-hyos-check-") as directory:
    work = Path(directory)
    (work / "common").mkdir()
    (work / "core").mkdir()
    (work / "common/logging.h").write_text("""#pragma once
#define LOGE(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGI(...) ((void)0)
#define LOGD(...) ((void)0)
""", encoding="utf-8")
    (work / "core/native_api.h").write_text("""#pragma once
#include <string>
struct NativeAPIEntries {};
namespace lspd::native {
using NativeInit = void* (*)(const NativeAPIEntries*);
inline bool InstallNativeAPI() { return false; }
inline const NativeAPIEntries* GetNativeAPIEntries() { return nullptr; }
inline void RegisterNativeLib(const std::string&) {}
inline void SetHookBackend(int (*)(void*, void*, void**), int (*)(void*)) {}
}
""", encoding="utf-8")
    command = [compiler, "-std=c++20", "-pthread", "-g", "-O1", "-I", str(work),
               "-I", str(ROOT / "zygisk/src/main/jni/include"),
               f'-DHYOS_RUNTIME_SOURCE="{source.as_posix()}"']
    if "g_registered_connections" not in source.read_text(encoding="utf-8"):
        command.append("-DLEGACY_RUNTIME_STATUS")
    executable = work / "hyos_runtime_test"
    command += [str(ROOT / "zygisk/src/testNative/hyos_runtime_test.cpp"), "-ldl", "-o", str(executable)]
    subprocess.run(command, check=True)
    subprocess.run([str(executable)], check=True, timeout=15)
