package com.base.editor.media.gl

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Сообщения об ошибках GL-эффектов в интерфейс (тост), чтобы сбой был виден, а не «картинка просто стоит». */
object FxDiagnostics {
    @Volatile var listener: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    fun report(msg: String) { Log.e("BaseFx", msg); main.post { listener?.invoke(msg) } }
}

/**
 * Вписывание кадра в холст + положение кадра + фон полей — одним проходом. Заменяет Presentation и MatrixTransformation,
 * когда выбран фон не «чёрный»: поля заливаются цветом или размытой копией того же видео, а фон при перемещении,
 * масштабе и повороте клипа остаётся на месте (двигается только кадр).
 * [transform] — матрица положения в нормализованных координатах холста (та же, что у MatrixTransformation).
 */
@UnstableApi
class BackgroundFitEffect(
    private val canvasW: Int, private val canvasH: Int,
    private val bgArgb: Int?,                         // null — размытое видео
    private val transform: (Long) -> Matrix,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { BgFitProgram(canvasW, canvasH, bgArgb, transform, useHdr) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class BgFitProgram(
    private val cw: Int, private val ch: Int, private val bgArgb: Int?, private val transform: (Long) -> Matrix, useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram? = try {
        GlProgram(VERTEX_SHADER, BG_FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) { FxDiagnostics.report("Фон: шейдер не собрался (${e.message?.take(80)})"); null }
    private val inv = Matrix()
    private val v = FloatArray(9)
    private var fx = 1f; private var fy = 1f; private var cover0 = 1f; private var cover1 = 1f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val av = inputWidth.toFloat() / inputHeight; val ac = cw.toFloat() / ch
        if (av >= ac) { fx = 1f; fy = ac / av; cover0 = ac / av; cover1 = 1f } else { fx = av / ac; fy = 1f; cover0 = 1f; cover1 = av / ac }
        return Size(cw, ch)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val p = program ?: return
        try {
            transform(presentationTimeUs).invert(inv)
            inv.getValues(v)                                   // x' = a·x + b·y + c ; y' = d·x + e·y + f
            p.use()
            p.setSamplerTexIdUniform("uTex", inputTexId, 0)
            p.setFloatsUniform("uInv0", floatArrayOf(v[Matrix.MSCALE_X], v[Matrix.MSKEW_X], v[Matrix.MTRANS_X]))
            p.setFloatsUniform("uInv1", floatArrayOf(v[Matrix.MSKEW_Y], v[Matrix.MSCALE_Y], v[Matrix.MTRANS_Y]))
            p.setFloatsUniform("uFit", floatArrayOf(fx, fy))
            p.setFloatsUniform("uCover", floatArrayOf(cover0, cover1))
            p.setIntUniform("uBlurBg", if (bgArgb == null) 1 else 0)
            val c = bgArgb ?: 0xFF000000.toInt()
            p.setFloatsUniform("uBgColor", floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f))
            p.setFloatUniform("uEdge", 0.5f * minOf(cw, ch))      // ширина сглаживания края кадра ≈ 1 пиксель
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun release() { super.release(); runCatching { program?.delete() } }
}

private const val BG_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec3 uInv0;
uniform vec3 uInv1;
uniform vec2 uFit;
uniform vec2 uCover;
uniform int uBlurBg;
uniform vec3 uBgColor;
uniform float uEdge;
in vec2 vUv;
out vec4 outColor;

void main() {
  vec2 q = vUv * 2.0 - 1.0;                                   // позиция на холсте (NDC)
  vec2 p = vec2(dot(uInv0.xy, q) + uInv0.z, dot(uInv1.xy, q) + uInv1.z);   // обратное положение клипа → «вписанный» холст
  vec2 f = p / uFit;                                          // −1..1 внутри кадра
  float inside = clamp(min(1.0 - abs(f.x), 1.0 - abs(f.y)) * uEdge, 0.0, 1.0);

  vec3 bg = uBgColor;
  if (uBlurBg == 1) {                                          // размытая копия видео, растянутая на весь холст
    vec2 c = (vUv - 0.5) * uCover + 0.5;
    vec3 acc = texture(uTex, c).rgb; float w = 1.0;
    for (int ring = 1; ring <= 3; ring++) {
      float r = 0.025 * float(ring);
      for (int i = 0; i < 8; i++) {
        float a = 6.2831853 * float(i) / 8.0 + float(ring) * 0.5;
        acc += texture(uTex, clamp(c + vec2(cos(a), sin(a)) * r, 0.0, 1.0)).rgb; w += 1.0;
      }
    }
    bg = (acc / w) * 0.8;
  }
  vec3 fg = texture(uTex, clamp(f * 0.5 + 0.5, 0.0, 1.0)).rgb;
  outColor = vec4(mix(bg, fg, inside), 1.0);
}
"""
