package com.base.editor.media.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.base.editor.core.BgMode
import com.base.editor.core.BgRemoval
import com.base.editor.core.ChromaKey
import com.base.editor.media.MaskReader
import java.nio.ByteBuffer

/** Параметры эффекта клипа на текущий кадр: хромакей и/или удаление фона. Читаются каждый кадр — слайдеры работают вживую. */
class FxParams(val chroma: ChromaKey?, val bg: BgRemoval?)

/**
 * Хромакей и удаление фона одним проходом GLSL. Хромакей: расстояние до цвета ключа в плоскости CbCr (не зависит от яркости),
 * порог [ChromaKey.similarity] и плавный край [ChromaKey.smoothness], подавление цветового подсвета по краю.
 * Удаление фона: маска сегментации ([MaskReader], считается заранее в фоне) → заливка цветом или размытие фона + обводка.
 * Время кадра приходит сквозным по последовательности, поэтому локальное время = t − [clipStartMs]; исходное = srcIn + локальное × speed.
 */
@UnstableApi
class ClipFxEffect(
    private val params: () -> FxParams,
    private val mask: MaskReader?,
    private val clipStartMs: Long,
    private val srcInMs: Long,
    private val speed: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        try { FxProgram(params, mask, clipStartMs, srcInMs, speed, useHdr) } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
}

@UnstableApi
private class FxProgram(
    private val params: () -> FxParams, private val mask: MaskReader?,
    private val clipStartMs: Long, private val srcInMs: Long, private val speed: Float, useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {
    private val blit = GlBlit()
    private val program: GlProgram? = try {
        GlProgram(VERTEX_SHADER, FX_FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) { Log.e(TAG, "шейдер эффекта клипа не собрался — кадры идут без изменений", e); null }

    private var maskTex = 0
    private var maskW = 0
    private var maskH = 0
    private var maskBuf: ByteArray? = null
    private var lastMaskKey = Long.MIN_VALUE
    private var w = 1
    private var h = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size { w = inputWidth; h = inputHeight; return Size(inputWidth, inputHeight) }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val p = program
            val fx = params()
            if (p == null || (fx.chroma == null && fx.bg == null)) { blit.draw(inputTexId); return }
            val bg = fx.bg?.takeIf { mask != null }
            val ck = fx.chroma
            if (bg != null) uploadMask(((presentationTimeUs / 1000 - clipStartMs).coerceAtLeast(0L) * speed).toLong() + srcInMs)

            p.use()
            p.setSamplerTexIdUniform("uTex", inputTexId, 0)
            p.setSamplerTexIdUniform("uMask", if (bg != null) maskTex else inputTexId, 1)
            p.setIntUniform("uChroma", if (ck != null) 1 else 0)
            if (ck != null) {
                p.setFloatsUniform("uKey", rgb(ck.color)); p.setFloatUniform("uSim", ck.similarity * 0.6f)
                p.setFloatUniform("uSmooth", ck.smoothness * 0.4f); p.setFloatsUniform("uChromaBg", rgb(ck.bgColor))
            } else { p.setFloatsUniform("uKey", FloatArray(3)); p.setFloatUniform("uSim", 0f); p.setFloatUniform("uSmooth", 0f); p.setFloatsUniform("uChromaBg", FloatArray(3)) }
            p.setIntUniform("uBg", when { bg == null -> 0; bg.mode == BgMode.BLUR -> 2; else -> 1 })
            p.setFloatsUniform("uBgColor", rgb(bg?.bgColor ?: 0xFF000000.toInt()))
            p.setFloatUniform("uBlur", bg?.blur ?: 0f)
            p.setIntUniform("uOutline", if (bg?.outline == true) 1 else 0)
            p.setFloatsUniform("uOutlineColor", rgb(bg?.outlineColor ?: -1)); p.setFloatUniform("uOutlineW", bg?.outlineWidth ?: 0f)
            p.setFloatsUniform("uTexel", floatArrayOf(1f / w, 1f / h))
            p.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    /** Загружает маску на время [srcMs] (две соседние маски смешиваются — без «лесенки» при 12 к/с). */
    private fun uploadMask(srcMs: Long) {
        val m = mask ?: return
        val key = srcMs / 16                                  // перезагрузка не чаще раза за ~16 мс исходного времени
        if (key == lastMaskKey && maskTex != 0) return
        lastMaskKey = key
        if (maskTex == 0 || maskW != m.width || maskH != m.height) {
            if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); maskTex = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            maskW = m.width; maskH = m.height; maskBuf = ByteArray(maskW * maskH)
        }
        val buf = maskBuf ?: return
        m.read(srcMs, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, maskW, maskH, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(buf))
    }

    private fun rgb(argb: Int) = floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f)

    override fun release() {
        super.release()
        runCatching { blit.release(); program?.delete(); if (maskTex != 0) GLES20.glDeleteTextures(1, intArrayOf(maskTex), 0); mask?.close() }
    }

    private companion object { const val TAG = "BaseFx" }
}

private const val FX_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uMask;
uniform int uChroma;
uniform vec3 uKey;
uniform float uSim;
uniform float uSmooth;
uniform vec3 uChromaBg;
uniform int uBg;
uniform vec3 uBgColor;
uniform float uBlur;
uniform int uOutline;
uniform vec3 uOutlineColor;
uniform float uOutlineW;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 outColor;

vec2 cbcr(vec3 c) {
  return vec2(-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b, 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b);
}
float maskAt(vec2 uv) {
  return smoothstep(0.30, 0.70, texture(uMask, vec2(uv.x, 1.0 - uv.y)).r);   // маска хранится сверху вниз
}

void main() {
  vec3 col = texture(uTex, vUv).rgb;
  vec3 result = col;

  if (uBg > 0) {
    float m = maskAt(vUv);
    vec3 back = uBgColor;
    if (uBg == 2) {                                   // размытие фона: два кольца выборок вокруг пикселя
      vec3 acc = col; float wsum = 1.0;
      for (int ring = 1; ring <= 2; ring++) {
        float r = uBlur * 26.0 * float(ring) * 0.5;
        for (int i = 0; i < 8; i++) {
          float a = 6.2831853 * float(i) / 8.0 + float(ring) * 0.4;
          vec2 off = vec2(cos(a), sin(a)) * r * uTexel;
          acc += texture(uTex, clamp(vUv + off, 0.0, 1.0)).rgb; wsum += 1.0;
        }
      }
      back = acc / wsum;
    }
    result = mix(back, col, m);
    if (uOutline == 1) {                              // обводка: фоновые пиксели рядом с силуэтом
      float ring = 0.0;
      float r = (2.0 + uOutlineW * 10.0);
      for (int i = 0; i < 12; i++) {
        float a = 6.2831853 * float(i) / 12.0;
        ring = max(ring, maskAt(vUv + vec2(cos(a), sin(a)) * r * uTexel));
      }
      result = mix(result, uOutlineColor, clamp(ring - m, 0.0, 1.0));
    }
  }

  if (uChroma == 1) {
    float d = distance(cbcr(col), cbcr(uKey));
    float a = smoothstep(uSim, uSim + max(uSmooth, 0.002), d);
    float spill = 1.0 - smoothstep(uSim, uSim + uSmooth + 0.12, d);            // подсвет цвета ключа по краю
    float luma = dot(result, vec3(0.299, 0.587, 0.114));
    vec3 clean = mix(result, vec3(luma), spill * 0.6);
    result = mix(uChromaBg, clean, a);
  }
  outColor = vec4(result, 1.0);
}
"""
