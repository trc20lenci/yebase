package com.base.editor.media.gl

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Вписывает кадр наложения в холст [canvasW]×[canvasH] с ПРОЗРАЧНЫМИ полями (альфа 0), в отличие от Presentation,
 * который заливает поля чёрным. Кадр получается размером с холст, поэтому масштаб/сдвиг/поворот наложения в
 * компоновщике Media3 считаются от целого холста, а прозрачные поля не закрывают основное видео.
 */
@UnstableApi
class FitAlphaEffect(private val canvasW: Int, private val canvasH: Int) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { FitAlphaProgram(canvasW, canvasH, useHdr) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class FitAlphaProgram(private val cw: Int, private val ch: Int, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram? = try {
        GlProgram(VERTEX_SHADER, FIT_FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) { FxDiagnostics.report("Наложение: шейдер не собрался (${e.message?.take(80)})"); null }
    private var fx = 1f; private var fy = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val av = inputWidth.toFloat() / inputHeight; val ac = cw.toFloat() / ch
        if (av >= ac) { fx = 1f; fy = ac / av } else { fx = av / ac; fy = 1f }
        return Size(cw, ch)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = program ?: return
        try {
            p.use()
            p.setSamplerTexIdUniform("uTex", inputTexId, 0)
            p.setFloatsUniform("uFit", floatArrayOf(fx, fy))
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    override fun release() { super.release(); runCatching { program?.delete() } }
}

private const val FIT_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uFit;
in vec2 vUv;
out vec4 outColor;
void main() {
  vec2 f = (vUv * 2.0 - 1.0) / uFit;
  bool inside = abs(f.x) <= 1.0 && abs(f.y) <= 1.0;
  vec4 c = texture(uTex, clamp(f * 0.5 + 0.5, 0.0, 1.0));
  outColor = inside ? c : vec4(0.0);
}
"""
