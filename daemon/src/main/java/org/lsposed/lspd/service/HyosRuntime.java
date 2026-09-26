package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.ServiceManager.TAG;
import static org.lsposed.lspd.service.ServiceManager.toGlobalNamespace;

import android.os.Build;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Publishes the module state a process spawned by the HyperOS Rust Runtime's spawner
 * (<code>/system_ext/bin/hyos_spawner</code>) reads to find the native libraries in its scope.
 *
 * Such a process has no JVM, no JNIEnv and no binder of its own, so the binder-based module list
 * every ART process fetches is unavailable there. A file is the only channel there is: the reader
 * lives in the Zygisk Next module (<code>zygisk/src/main/jni/src/hyos_runtime.cpp</code>), which is
 * the only consumer of everything this class writes, and the two sets of constants have to agree or
 * the feature quietly does nothing.
 *
 * Nothing here does anything on a device without that runtime, and nothing happens for a module
 * that declares no native libraries — there is no <code>native_init</code> to call and so nothing
 * to load.
 */
public class HyosRuntime {

    /**
     * The properties the HyperOS Rust Runtime advertises itself with.
     *
     * Read rather than looking for <code>/system_ext/bin/hyos_spawner</code>: the runtime can be
     * switched off on a device that still ships that binary, and an index nothing will ever read is
     * work done for nothing. These are the same two properties LSPosed 2.2.0 reads for the same
     * decision, in its daemon's <code>ILSPManagerService</code> transaction 67.
     */
    private static final String HYOS_ACTIVE_PROPERTY = "rust.runtime_active";
    private static final String HYOS_VERSION_PROPERTY = "rust.runtime_version";

    /**
     * The pointer file the Zygisk Next companion reads to find the real directory, because there is
     * nothing to list. It sits directly in /data/misc, which belongs to the system uid with mode
     * 0771: nothing running as an application can create an entry there, and the file itself is
     * mode 0600, so only root — which is what the companion runs as — can read it. An application
     * therefore cannot learn where the index is, and so cannot ask whether it is being hooked.
     */
    private static final String HYOS_POINTER_PATH = "/data/misc/lspd.hyos";

    /** The index directory inside the daemon's random directory, and its marker file. */
    private static final String HYOS_INDEX_DIR = "hyos";
    private static final String HYOS_INDEX_MARKER = ".version";
    private static final String HYOS_INDEX_MAGIC = "lspd-hyos 1";

    /**
     * The subdirectory of the misc root holding copies of module libraries that a HyperOS process
     * cannot map out of the module's APK: it cannot be told a search path the way an injected ART
     * process is, so it is handed absolute paths in the index and needs the libraries to exist at
     * those paths. The APK itself would do — /data/app is readable and mappable by any app domain —
     * but the entry has to be STORED inside the zip for the loader to open it, and a module that
     * compressed its libraries would silently lose its native part. A copy has neither problem.
     */
    private static final String HYOS_LIBRARY_DIR = "libhyos";

    /** One module a scope names, reduced to what publishing an index needs. */
    public static final class ModuleRef {
        public final String packageName;
        public final String apkPath;
        public final List<String> libraryNames;

        public ModuleRef(String packageName, String apkPath, List<String> libraryNames) {
            this.packageName = packageName;
            this.apkPath = apkPath;
            this.libraryNames = libraryNames;
        }
    }

    /** One native library the index names for one process. */
    private static final class LibraryRef {
        final String modulePackage;
        final String libraryName;
        final String libraryPath;

        LibraryRef(String modulePackage, String libraryName, String libraryPath) {
            this.modulePackage = modulePackage;
            this.libraryName = libraryName;
            this.libraryPath = libraryPath;
        }
    }

    /** Whether this device runs applications on the HyperOS Rust Runtime at all. */
    public static boolean hasHyosRuntime() {
        return SystemProperties.getBoolean(HYOS_ACTIVE_PROPERTY, false)
                && !SystemProperties.get(HYOS_VERSION_PROPERTY, "").isEmpty();
    }

    /**
     * Stages every module native library the scopes name and publishes the per-process index.
     *
     * Each module is extracted once rather than once per process it is scoped to: extraction is the
     * expensive half of this, and every reader gets the same file names from the same copy. The
     * index is keyed by process name only — the reader carries no uid, so a multi-user device with
     * the same process name in several users serves whichever scope was written last — and a
     * process with no entry is in nobody's scope.
     */
    public static void publishHyosRuntimeIndex(Path miscPath, Map<String, List<ModuleRef>> scopes) {
        if (!hasHyosRuntime() || miscPath == null) return;

        try {
            // A module that ships nothing this ABI can load stages nothing — an ordinary answer,
            // and one worth remembering so the next scope does not ask again.
            Map<String, String> staged = new HashMap<>();
            for (List<ModuleRef> modules : scopes.values()) {
                for (ModuleRef module : modules) {
                    if (module.libraryNames == null || module.libraryNames.isEmpty()) continue;
                    if (staged.containsKey(module.packageName)) continue;
                    String dir = stageHyosNativeLibraries(miscPath, module.packageName,
                            module.apkPath, new HashSet<>(module.libraryNames));
                    if (dir != null) staged.put(module.packageName, dir);
                }
            }

            Map<String, List<LibraryRef>> index = new HashMap<>();
            scopes.forEach((processName, modules) -> {
                List<LibraryRef> libraries = new ArrayList<>();
                for (ModuleRef module : modules) {
                    String dir = staged.get(module.packageName);
                    if (dir == null) continue;
                    for (String name : module.libraryNames) {
                        Path path = Paths.get(dir, name);
                        if (Files.isReadable(path)) {
                            libraries.add(new LibraryRef(module.packageName, name, path.toString()));
                        } else {
                            // The module named a library its APK does not carry for this ABI. It
                            // still loads everywhere else; only this half has nothing to load.
                            Log.w(TAG, "Module " + module.packageName + " declares " + name
                                    + ", which is not in " + dir);
                        }
                    }
                }
                if (!libraries.isEmpty()) index.put(processName, libraries);
            });

            pruneHyosNativeLibraries(miscPath, staged.keySet());
            publishHyosIndex(miscPath, index);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to publish the HyperOS Runtime index", t);
        }
    }

    /**
     * Stages a module's native libraries for the HyperOS Runtime's reader.
     *
     * Re-extracts only when the APK behind the copy changed. Getting this wrong in the lenient
     * direction would leave a HyperOS process running a module's superseded native code.
     *
     * @return the directory the libraries were staged into, or null when the module ships nothing
     * for any of this device's ABIs or the copy failed — the module still loads elsewhere and only
     * its native part is missing, exactly as it does today.
     */
    private static String stageHyosNativeLibraries(Path miscPath, String packageName,
                                                   String apkPath, Set<String> libraryNames) {
        if (apkPath == null) return null;
        try {
            Path dir = miscPath.resolve(HYOS_LIBRARY_DIR).resolve(packageName);
            Files.createDirectories(dir);

            File apk = toGlobalNamespace(apkPath);
            String sourceMarker = apkPath + "@" + apk.lastModified();
            Path marker = dir.resolve(".source");
            if (Files.exists(marker)
                    && sourceMarker.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
                return dir.toString();
            }

            try (ZipFile apkFile = new ZipFile(apk)) {
                boolean copied = false;
                // The reader process is a child of the spawner and has the device's primary ABI;
                // the first ABI of this device that carries the library wins.
                for (String abi : Build.SUPPORTED_ABIS) {
                    for (String name : libraryNames) {
                        ZipEntry entry = apkFile.getEntry("lib/" + abi + "/" + name);
                        if (entry == null) continue;
                        Path target = dir.resolve(name);
                        try (var in = apkFile.getInputStream(entry)) {
                            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                        chmod(target.toString(), 0644);
                        copied = true;
                    }
                    if (copied) break;
                }
                if (!copied) return null;
            }

            Files.writeString(marker, sourceMarker, StandardCharsets.UTF_8);
            chmod(dir.toString(), 0711);
            chmod(miscPath.resolve(HYOS_LIBRARY_DIR).toString(), 0711);
            return dir.toString();
        } catch (IOException e) {
            Log.w(TAG, "Failed to stage native libraries of " + packageName, e);
            return null;
        }
    }

    /** Drops HyperOS-staged libraries of modules that are in no scope any more. */
    private static void pruneHyosNativeLibraries(Path miscPath, Set<String> keep) {
        try {
            Path libRoot = miscPath.resolve(HYOS_LIBRARY_DIR);
            if (!Files.isDirectory(libRoot)) return;
            try (var stream = Files.list(libRoot)) {
                stream.filter(p -> !keep.contains(p.getFileName().toString()))
                        .forEach(HyosRuntime::deleteFolderIfExists);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to prune staged HyperOS native libraries", e);
        }
    }

    private static void deleteFolderIfExists(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(HyosRuntime::deleteFolderIfExists);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Log.w(TAG, "Failed to delete " + path, e);
        }
    }

    /**
     * Publishes the index a process spawned by the HyperOS Rust Runtime reads to find the modules
     * in its scope.
     *
     * The whole file lives inside the daemon's random directory and is readable by path to anything
     * that knows the path, which is deliberate: the reader is a process with no binder and no JVM,
     * so a file is the only channel there is. What keeps the list private is that the path is not
     * discoverable — see {@link #HYOS_POINTER_PATH} for the half of that which the companion reads.
     */
    private static void publishHyosIndex(Path miscPath, Map<String, List<LibraryRef>> index)
            throws IOException {
        Path dir = miscPath.resolve(HYOS_INDEX_DIR);
        Files.createDirectories(dir);

        Set<String> written = new HashSet<>();
        index.forEach((processName, libraries) -> {
            // A process name is a manifest string that becomes a file name here. Android's own
            // validation is not something to lean on for a write performed as root, so anything
            // that is not a plain name is refused rather than resolved.
            if (processName.isEmpty() || processName.contains("/")
                    || processName.equals(".") || processName.equals("..")) {
                Log.w(TAG, "Refusing to publish an index under the name '" + processName + "'");
                return;
            }
            try {
                StringBuilder text = new StringBuilder();
                text.append(HYOS_INDEX_MAGIC).append('\n');
                for (LibraryRef library : libraries) {
                    text.append(library.modulePackage).append('\t')
                            .append(library.libraryName).append('\t')
                            .append(library.libraryPath).append('\n');
                }
                Path target = dir.resolve(processName);
                Files.writeString(target, text.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                chmod(target.toString(), 0644);
                written.add(processName);
            } catch (IOException e) {
                Log.w(TAG, "Failed to write the index of " + processName, e);
            }
        });

        Path marker = dir.resolve(HYOS_INDEX_MARKER);
        Files.writeString(marker, HYOS_INDEX_MAGIC + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        chmod(marker.toString(), 0644);

        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return !name.equals(HYOS_INDEX_MARKER) && !written.contains(name);
            }).forEach(HyosRuntime::deleteFolderIfExists);
        }

        // The daemon runs with a zero umask, so every mode here is set rather than inherited. The
        // directory is searchable but not listable, the way the rest of the staged tree is, and the
        // label is what lets a process running as an application read it at all.
        chmod(dir.toString(), 0711);
        setSelinuxContextRecursive(dir, "u:object_r:lsposed_file:s0");

        Path pointer = Path.of(HYOS_POINTER_PATH);
        Files.writeString(pointer, "misc=" + miscPath + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        chmod(pointer.toString(), 0600);
    }

    private static void setSelinuxContextRecursive(Path dir, String context) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    SELinux.setFileContext(d.toString(), context);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    SELinux.setFileContext(file.toString(), context);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Log.w(TAG, "Failed to label " + dir, e);
        }
    }

    private static void chmod(String path, int mode) {
        try {
            Os.chmod(path, mode);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to chmod " + path, t);
        }
    }
}
