# Vendored: compose-miuix-ui blur framework

The files in this package are vendored from the `miuix-blur` module of
[compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/miuix)
(artifact `top.yukonga.miuix.kmp:miuix-blur:0.9.1`), Apache License 2.0.
They are kept under their original package so that the liquid-glass
navigation bar ported from KernelSU Manager keeps working unmodified.

Local adaptations (everything else is byte-identical to the upstream
sources jar):

* `Platform.kt`, `RuntimeShader.kt`, `internal/RenderEffectCompat.kt`,
  `sensor/DeviceTilt.kt` — expect/actual declarations collapsed into the
  Android implementations (this module only targets Android), and the
  `org.intellij.lang.annotations.Language` annotation dropped.
* `RuntimeShaderCache.kt` — `kotlinx.coroutines.internal.SynchronizedObject`
  replaced with a plain `Any` monitor.
* `internal/LayerRecorder.kt` — Kotlin `context(...)` parameter replaced
  with an explicit `node` parameter (project compiles with Kotlin 2.2
  default language settings); call sites in `DrawBackdropModifier.kt` and
  `LayerBackdropModifier.kt` updated accordingly.
