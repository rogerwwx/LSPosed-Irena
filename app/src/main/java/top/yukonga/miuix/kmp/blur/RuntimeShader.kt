// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/**
 * Creates a platform [RuntimeShader] from the given AGSL/SkSL shader string.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader(shaderString: String): RuntimeShader =
    AndroidRuntimeShader(android.graphics.RuntimeShader(shaderString))

/**
 * Converts this [RuntimeShader] to a Compose [Shader] for use with Paint.
 */
fun RuntimeShader.asComposeShader(): Shader = asAndroidRuntimeShader()

/**
 * Converts this [RuntimeShader] to a [ShaderBrush] suitable for drawing.
 *
 * The underlying Android shader is mutable, so a cached [ShaderBrush] is returned.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader.asBrush(): ShaderBrush = (this as AndroidRuntimeShader).brush

internal fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader =
    (this as AndroidRuntimeShader).shader

/**
 * Cross-platform interface for setting uniforms on a runtime shader.
 */
interface RuntimeShader {

    fun setFloatUniform(name: String, value: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)
    fun setFloatUniform(name: String, values: FloatArray)

    fun setIntUniform(name: String, value: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)
    fun setIntUniform(name: String, values: IntArray)
    fun setColorUniform(name: String, color: Color)
    fun setInputShader(name: String, shader: Shader)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidRuntimeShader(val shader: android.graphics.RuntimeShader) : RuntimeShader {

    val brush: ShaderBrush = ShaderBrush(shader)

    override fun setFloatUniform(name: String, value: Float) {
        shader.setFloatUniform(name, value)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float) {
        shader.setFloatUniform(name, value1, value2)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) {
        shader.setFloatUniform(name, value1, value2, value3)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) {
        shader.setFloatUniform(name, value1, value2, value3, value4)
    }

    override fun setFloatUniform(name: String, values: FloatArray) {
        shader.setFloatUniform(name, values)
    }

    override fun setIntUniform(name: String, value: Int) {
        shader.setIntUniform(name, value)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int) {
        shader.setIntUniform(name, value1, value2)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) {
        shader.setIntUniform(name, value1, value2, value3)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) {
        shader.setIntUniform(name, value1, value2, value3, value4)
    }

    override fun setIntUniform(name: String, values: IntArray) {
        shader.setIntUniform(name, values)
    }

    override fun setColorUniform(name: String, color: Color) {
        shader.setColorUniform(name, color.toArgb())
    }

    override fun setInputShader(name: String, shader: Shader) {
        this.shader.setInputShader(name, shader)
    }
}
