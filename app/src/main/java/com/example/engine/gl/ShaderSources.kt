package com.example.engine.gl

object ShaderSources {

    const val VERTEX_QUAD_ES100 = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    const val PASS0_OES_TO_RGBA_ES100 = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uOesTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uOesTexture, vTexCoord);
        }
    """

    const val VERTEX_QUAD_ES300 = """#version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        out vec2 vTexCoord;
        out vec2 vScreenUv;
        void main() {
            gl_Position = aPosition;
            vScreenUv = aTexCoord;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    // Pass 1: Edge-Preserving Bilateral Denoise & Deblock + Photo Scratch/Grain Restoration
    const val PASS1_BILATERAL_DENOISE_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        uniform vec2 uTexelSize;
        uniform float uDenoise;   // 0.0 .. 1.0
        uniform int uTurboMode;   // 1..4
        uniform float uIsPhoto;   // 1.0 for photo, 0.0 for video
        in vec2 vTexCoord;
        out vec4 fragColor;

        float luma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        vec3 healVintageScratchAndDust(sampler2D tex, vec2 uv, vec2 texel, vec3 center, float strength) {
            vec3 n  = texture(tex, clamp(uv + vec2(0.0, -texel.y), 0.001, 0.999)).rgb;
            vec3 s  = texture(tex, clamp(uv + vec2(0.0,  texel.y), 0.001, 0.999)).rgb;
            vec3 e  = texture(tex, clamp(uv + vec2( texel.x, 0.0), 0.001, 0.999)).rgb;
            vec3 w  = texture(tex, clamp(uv + vec2(-texel.x, 0.0), 0.001, 0.999)).rgb;
            vec3 nw = texture(tex, clamp(uv + vec2(-texel.x, -texel.y), 0.001, 0.999)).rgb;
            vec3 ne = texture(tex, clamp(uv + vec2( texel.x, -texel.y), 0.001, 0.999)).rgb;
            vec3 sw = texture(tex, clamp(uv + vec2(-texel.x,  texel.y), 0.001, 0.999)).rgb;
            vec3 se = texture(tex, clamp(uv + vec2( texel.x,  texel.y), 0.001, 0.999)).rgb;

            vec3 crossMin = min(min(n, s), min(e, w));
            vec3 crossMax = max(max(n, s), max(e, w));
            vec3 diagMin  = min(min(nw, ne), min(sw, se));
            vec3 diagMax  = max(max(nw, ne), max(sw, se));

            vec3 localMin = min(crossMin, diagMin);
            vec3 localMax = max(crossMax, diagMax);
            vec3 ringAvg  = (n + s + e + w + nw + ne + sw + se) * 0.125;

            float lCenter = luma(center);
            float lMin = luma(localMin);
            float lMax = luma(localMax);
            float outlier = max(lCenter - lMax, lMin - lCenter);

            float hDiff = abs(lCenter - 0.5 * (luma(e) + luma(w)));
            float vDiff = abs(lCenter - 0.5 * (luma(n) + luma(s)));
            float scratchScore = max(outlier, min(hDiff, vDiff) * 0.65);

            float healMask = smoothstep(0.06, 0.22, scratchScore) * strength;
            vec3 clamped = clamp(center, localMin, localMax);
            vec3 repaired = mix(clamped, ringAvg, 0.45);
            return mix(center, repaired, clamp(healMask, 0.0, 1.0));
        }

        void main() {
            vec3 center = texture(uInputTex, vTexCoord).rgb;

            if (uIsPhoto > 0.5 && uDenoise > 0.05) {
                center = healVintageScratchAndDust(
                    uInputTex,
                    vTexCoord,
                    uTexelSize,
                    center,
                    clamp(uDenoise * 1.15, 0.0, 1.0)
                );
            }

            if (uDenoise <= 0.005) {
                fragColor = vec4(center, 1.0);
                return;
            }

            float centerLuma = luma(center);
            float sigmaS = mix(0.8, 2.2, uDenoise);
            float sigmaR = mix(0.025, 0.17, uDenoise);
            float invTwoSigmaS2 = 1.0 / (2.0 * sigmaS * sigmaS);
            float invTwoSigmaR2 = 1.0 / (2.0 * sigmaR * sigmaR);

            vec3 sumColor = center;
            float sumWeight = 1.0;

            if (uTurboMode >= 2) {
                vec2 offsets[8];
                offsets[0] = vec2(-1.0,  0.0);
                offsets[1] = vec2( 1.0,  0.0);
                offsets[2] = vec2( 0.0, -1.0);
                offsets[3] = vec2( 0.0,  1.0);
                offsets[4] = vec2(-2.0,  0.0);
                offsets[5] = vec2( 2.0,  0.0);
                offsets[6] = vec2( 0.0, -2.0);
                offsets[7] = vec2( 0.0,  2.0);

                for (int i = 0; i < 8; i++) {
                    vec2 off = offsets[i];
                    vec3 sampleRgb = texture(uInputTex, clamp(vTexCoord + off * uTexelSize, 0.001, 0.999)).rgb;
                    float dLuma = luma(sampleRgb) - centerLuma;
                    vec3 dChroma = sampleRgb - center;
                    float dist2 = dot(off, off);
                    float rangeDist2 = dLuma * dLuma + 0.25 * dot(dChroma, dChroma);
                    float w = exp(-dist2 * invTwoSigmaS2 - rangeDist2 * invTwoSigmaR2);
                    sumColor += sampleRgb * w;
                    sumWeight += w;
                }
            } else {
                sumColor = vec3(0.0);
                sumWeight = 0.0;
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        vec2 offset = vec2(float(x), float(y)) * uTexelSize;
                        vec3 sampleRgb = texture(uInputTex, clamp(vTexCoord + offset, 0.001, 0.999)).rgb;
                        float dLuma = luma(sampleRgb) - centerLuma;
                        vec3 dChroma = sampleRgb - center;
                        float dist2 = float(x * x + y * y);
                        float rangeDist2 = dLuma * dLuma + 0.25 * dot(dChroma, dChroma);
                        float w = exp(-dist2 * invTwoSigmaS2 - rangeDist2 * invTwoSigmaR2);
                        sumColor += sampleRgb * w;
                        sumWeight += w;
                    }
                }
            }

            vec3 denoised = sumColor / max(sumWeight, 0.0001);
            vec3 result = mix(center, denoised, clamp(uDenoise * 1.1, 0.0, 1.0));
            fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
        }
    """

    // Pass 1.5: Dedicated 16-Tap Bicubic / Lanczos-3 Directional Upscaling to Target High Resolution
    const val PASS1_5_BICUBIC_UPSCALE_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        uniform vec2 uSrcTexelSize;
        in vec2 vTexCoord;
        out vec4 fragColor;

        float cubicWeight(float x) {
            float ax = abs(x);
            if (ax < 1.0) {
                return (1.5 * ax - 2.5) * ax * ax + 1.0;
            } else if (ax < 2.0) {
                return ((-0.5 * ax + 2.5) * ax - 4.0) * ax + 2.0;
            }
            return 0.0;
        }

        void main() {
            vec2 srcSize = 1.0 / max(uSrcTexelSize, vec2(1e-5));
            vec2 pos = vTexCoord * srcSize - 0.5;
            vec2 f = fract(pos);
            vec2 base = floor(pos);

            vec3 sum = vec3(0.0);
            float totalWeight = 0.0;

            for (int j = -1; j <= 2; j++) {
                float wy = cubicWeight(float(j) - f.y);
                for (int i = -1; i <= 2; i++) {
                    float wx = cubicWeight(float(i) - f.x);
                    float w = wx * wy;
                    vec2 sampleUv = (base + vec2(float(i), float(j)) + 0.5) * uSrcTexelSize;
                    vec3 tap = texture(uInputTex, clamp(sampleUv, 0.001, 0.999)).rgb;
                    sum += tap * w;
                    totalWeight += w;
                }
            }

            vec3 upscaled = sum / max(totalWeight, 1e-4);

            vec3 t00 = texture(uInputTex, clamp((base + vec2(0.5, 0.5)) * uSrcTexelSize, 0.001, 0.999)).rgb;
            vec3 t10 = texture(uInputTex, clamp((base + vec2(1.5, 0.5)) * uSrcTexelSize, 0.001, 0.999)).rgb;
            vec3 t01 = texture(uInputTex, clamp((base + vec2(0.5, 1.5)) * uSrcTexelSize, 0.001, 0.999)).rgb;
            vec3 t11 = texture(uInputTex, clamp((base + vec2(1.5, 1.5)) * uSrcTexelSize, 0.001, 0.999)).rgb;
            vec3 minB = min(min(t00, t10), min(t01, t11));
            vec3 maxB = max(max(t00, t10), max(t01, t11));

            fragColor = vec4(clamp(upscaled, minB - 0.02, maxB + 0.02), 1.0);
        }
    """

    // Pass 2: High-Res Multi-Scale De-blur & AMD FidelityFX CAS strictly at Target Pixel Density
    const val PASS2_EDGE_DEBLUR_CAS_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        uniform vec2 uDstTexelSize;
        uniform float uDeblur;    // 0.0 .. 1.0
        uniform float uSharpness; // 0.0 .. 1.0
        in vec2 vTexCoord;
        out vec4 fragColor;

        float luma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        void main() {
            vec2 stepUv = uDstTexelSize;
            vec3 c  = texture(uInputTex, vTexCoord).rgb;
            vec3 n  = texture(uInputTex, clamp(vTexCoord + vec2(0.0, -stepUv.y), 0.001, 0.999)).rgb;
            vec3 s  = texture(uInputTex, clamp(vTexCoord + vec2(0.0,  stepUv.y), 0.001, 0.999)).rgb;
            vec3 e  = texture(uInputTex, clamp(vTexCoord + vec2( stepUv.x, 0.0), 0.001, 0.999)).rgb;
            vec3 w  = texture(uInputTex, clamp(vTexCoord + vec2(-stepUv.x, 0.0), 0.001, 0.999)).rgb;
            vec3 nw = texture(uInputTex, clamp(vTexCoord + vec2(-stepUv.x, -stepUv.y), 0.001, 0.999)).rgb;
            vec3 ne = texture(uInputTex, clamp(vTexCoord + vec2( stepUv.x, -stepUv.y), 0.001, 0.999)).rgb;
            vec3 sw = texture(uInputTex, clamp(vTexCoord + vec2(-stepUv.x,  stepUv.y), 0.001, 0.999)).rgb;
            vec3 se = texture(uInputTex, clamp(vTexCoord + vec2( stepUv.x,  stepUv.y), 0.001, 0.999)).rgb;

            float lNW = luma(nw); float lN = luma(n); float lNE = luma(ne);
            float lW  = luma(w);  float lC = luma(c); float lE  = luma(e);
            float lSW = luma(sw); float lS = luma(s); float lSE = luma(se);

            float gx = (lNE + 2.0 * lE + lSE) - (lNW + 2.0 * lW + lSW);
            float gy = (lSW + 2.0 * lS + lSE) - (lNW + 2.0 * lN + lNE);
            float edgeMag = clamp(sqrt(gx * gx + gy * gy), 0.0, 1.0);

            if (uDeblur > 0.005) {
                vec3 blurFine = (c * 4.0 + (n + s + e + w) * 2.0 + (nw + ne + sw + se)) / 16.0;
                vec2 wideUv = stepUv * 2.5;
                vec3 w1 = texture(uInputTex, clamp(vTexCoord + vec2( wideUv.x, 0.0), 0.001, 0.999)).rgb;
                vec3 w2 = texture(uInputTex, clamp(vTexCoord + vec2(-wideUv.x, 0.0), 0.001, 0.999)).rgb;
                vec3 w3 = texture(uInputTex, clamp(vTexCoord + vec2(0.0,  wideUv.y), 0.001, 0.999)).rgb;
                vec3 w4 = texture(uInputTex, clamp(vTexCoord + vec2(0.0, -wideUv.y), 0.001, 0.999)).rgb;
                vec3 blurWide = (blurFine * 2.0 + w1 + w2 + w3 + w4) / 6.0;

                float edgeGate = smoothstep(0.012, 0.13, edgeMag);
                vec3 highFreqFine = c - blurFine;
                vec3 highFreqMed = blurFine - blurWide;
                float deblurAmt = uDeblur * (0.7 + 0.9 * edgeGate);
                c = clamp(c + highFreqFine * (1.35 * deblurAmt) + highFreqMed * (0.65 * deblurAmt), 0.0, 1.0);
            }

            if (uSharpness > 0.005) {
                vec3 minRGB = min(min(min(n, s), min(e, w)), c);
                vec3 maxRGB = max(max(max(n, s), max(e, w)), c);
                vec3 minRGB2 = min(minRGB, min(min(nw, ne), min(sw, se)));
                vec3 maxRGB2 = max(maxRGB, max(max(nw, ne), max(sw, se)));
                minRGB = (minRGB + minRGB2) * 0.5;
                maxRGB = (maxRGB + maxRGB2) * 0.5;

                vec3 rcpMax = 1.0 / max(maxRGB, vec3(0.0001));
                vec3 amp = clamp(min(minRGB, 1.0 - maxRGB) * rcpMax, 0.0, 1.0);
                amp = sqrt(amp);

                float peak = mix(0.0, -0.195, uSharpness);
                vec3 weight = amp * peak;
                vec3 casColor = (c + weight * (n + s + e + w)) / max(1.0 + 4.0 * weight, vec3(0.0001));
                c = clamp(casColor, minRGB - 0.04, maxRGB + 0.04);
            }

            fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
        }
    """

    // Pass 3: Micro-Contrast Clarity, Dehaze, Shadow/Highlight Recovery & Vibrance Tone Mapping
    const val PASS3_CLARITY_TONE_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        uniform vec2 uTexelSize;
        uniform float uClarity;        // 0.0 .. 1.0
        uniform float uShadowRecovery; // -1.0 .. 1.0
        uniform float uVibrance;       // -1.0 .. 1.0
        in vec2 vTexCoord;
        out vec4 fragColor;

        float luma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        void main() {
            vec3 color = texture(uInputTex, vTexCoord).rgb;

            if (uClarity > 0.005) {
                vec2 r1 = uTexelSize * 4.5;
                vec2 r2 = uTexelSize * 9.0;
                vec3 localAvg = color * 2.0;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2( r1.x, 0.0), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2(-r1.x, 0.0), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2(0.0,  r1.y), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2(0.0, -r1.y), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2( r2.x,  r2.y), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2(-r2.x,  r2.y), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2( r2.x, -r2.y), 0.001, 0.999)).rgb;
                localAvg += texture(uInputTex, clamp(vTexCoord + vec2(-r2.x, -r2.y), 0.001, 0.999)).rgb;
                localAvg /= 10.0;

                float darkChannel = min(min(localAvg.r, localAvg.g), localAvg.b);
                float hazeVeil = max(darkChannel - 0.08, 0.0) * (0.28 * uClarity);
                color = clamp((color - hazeVeil) / max(1.0 - hazeVeil, 0.65), 0.0, 1.0);

                float lum = luma(color);
                float midWeight = 1.0 - abs(lum * 2.0 - 1.0);
                vec3 detail = color - localAvg;
                color = clamp(color + detail * (uClarity * (0.45 + 0.35 * midWeight)), 0.0, 1.0);
            }

            if (abs(uShadowRecovery) > 0.005) {
                float lum = luma(color);
                float shadowMask = pow(clamp(1.0 - lum, 0.0, 1.0), 2.1);
                float highlightMask = pow(clamp(lum, 0.0, 1.0), 2.4);

                if (uShadowRecovery > 0.0) {
                    float lift = uShadowRecovery * 0.55 * shadowMask;
                    float hlProtect = uShadowRecovery * 0.14 * highlightMask;
                    color = clamp(color + vec3(lift - hlProtect), 0.0, 1.0);
                } else {
                    float deepen = (-uShadowRecovery) * 0.42 * shadowMask;
                    color = clamp(color - vec3(deepen), 0.0, 1.0);
                }
            }

            if (abs(uVibrance) > 0.005) {
                float lum = luma(color);
                float maxC = max(max(color.r, color.g), color.b);
                float minC = min(min(color.r, color.g), color.b);
                float sat = maxC - minC;

                float skinMask = clamp(1.0 - abs(color.r - color.g - 0.15) * 3.0, 0.0, 1.0) *
                                 step(color.g, color.r) * step(color.b, color.g);
                float vibranceGain = uVibrance * (1.0 - sat * 0.75) * (1.0 - 0.35 * skinMask);
                color = mix(vec3(lum), color, clamp(1.0 + vibranceGain, 0.0, 2.0));

                if (uVibrance > 0.0) {
                    float warmth = uVibrance * 0.045 * (0.4 + 0.6 * skinMask);
                    color.r = clamp(color.r + warmth, 0.0, 1.0);
                    color.b = clamp(color.b - warmth * 0.65, 0.0, 1.0);
                }
            }

            float sAmount = 0.08 * uClarity;
            vec3 sCurve = color * color * (3.0 - 2.0 * color);
            color = mix(color, sCurve, sAmount);

            fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
        }
    """

    // Fused High-Speed Single-Pass Restoration & Upscale Shader for 3x/4x Turbo Mode
    // Combines 5-tap cross bilateral denoise, 5-tap fast Catmull-Rom upscale, CAS sharpen & Deblur, and Clarity/Tone Mapping
    const val PASS_TURBO_FUSED_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        uniform vec2 uSrcTexelSize;
        uniform vec2 uDstTexelSize;
        uniform float uDenoise;
        uniform float uDeblur;
        uniform float uSharpness;
        uniform float uClarity;
        uniform float uShadowRecovery;
        uniform float uVibrance;
        in vec2 vTexCoord;
        out vec4 fragColor;

        float luma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        // Fast 5-tap Catmull-Rom upsampling
        vec3 sampleFastCatmullRom(sampler2D tex, vec2 uv, vec2 invTexSize) {
            vec2 texSize = 1.0 / max(invTexSize, vec2(1e-5));
            vec2 pos = uv * texSize - 0.5;
            vec2 f = fract(pos);
            vec2 base = floor(pos);

            vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
            vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
            vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
            vec2 w3 = f * f * (-0.5 + 0.5 * f);

            vec2 w12 = w1 + w2;
            vec2 tc0 = (base - 0.5) * invTexSize;
            vec2 tc3 = (base + 2.5) * invTexSize;
            vec2 tc12 = (base + 0.5 + w2 / max(w12, vec2(1e-5))) * invTexSize;

            vec3 col = vec3(0.0);
            col += texture(tex, clamp(vec2(tc12.x, tc0.y), 0.001, 0.999)).rgb * (w12.x * w0.y);
            col += texture(tex, clamp(vec2(tc0.x, tc12.y), 0.001, 0.999)).rgb * (w0.x * w12.y);
            col += texture(tex, clamp(vec2(tc12.x, tc12.y), 0.001, 0.999)).rgb * (w12.x * w12.y);
            col += texture(tex, clamp(vec2(tc3.x, tc12.y), 0.001, 0.999)).rgb * (w3.x * w12.y);
            col += texture(tex, clamp(vec2(tc12.x, tc3.y), 0.001, 0.999)).rgb * (w12.x * w3.y);

            float totalW = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
            return clamp(col / max(totalW, 1e-4), 0.0, 1.0);
        }

        void main() {
            // 1. Fast Catmull-Rom Upscale + 4-neighbor cross sample
            vec3 c = sampleFastCatmullRom(uInputTex, vTexCoord, uSrcTexelSize);
            vec2 stepUv = max(uDstTexelSize, uSrcTexelSize * 0.75);
            vec3 n = texture(uInputTex, clamp(vTexCoord + vec2(0.0, -stepUv.y), 0.001, 0.999)).rgb;
            vec3 s = texture(uInputTex, clamp(vTexCoord + vec2(0.0,  stepUv.y), 0.001, 0.999)).rgb;
            vec3 e = texture(uInputTex, clamp(vTexCoord + vec2( stepUv.x, 0.0), 0.001, 0.999)).rgb;
            vec3 w = texture(uInputTex, clamp(vTexCoord + vec2(-stepUv.x, 0.0), 0.001, 0.999)).rgb;

            // 2. Fast 5-tap Cross Bilateral Denoise
            if (uDenoise > 0.005) {
                float cLum = luma(c);
                float invSigma = 1.0 / mix(0.015, 0.14, uDenoise);
                float wn = clamp(1.0 - abs(luma(n) - cLum) * invSigma, 0.0, 1.0);
                float ws = clamp(1.0 - abs(luma(s) - cLum) * invSigma, 0.0, 1.0);
                float we = clamp(1.0 - abs(luma(e) - cLum) * invSigma, 0.0, 1.0);
                float ww = clamp(1.0 - abs(luma(w) - cLum) * invSigma, 0.0, 1.0);
                vec3 smoothC = (c + n * wn + s * ws + e * we + w * ww) / (1.0 + wn + ws + we + ww);
                c = mix(c, smoothC, uDenoise * 0.85);
            }

            // 3. Fused Deblur & FidelityFX CAS Sharpening
            vec3 avgCross = (n + s + e + w) * 0.25;
            if (uDeblur > 0.005) {
                vec3 highFreq = c - avgCross;
                c = clamp(c + highFreq * (uDeblur * 1.15), 0.0, 1.0);
            }
            if (uSharpness > 0.005) {
                vec3 minRGB = min(min(min(n, s), min(e, w)), c);
                vec3 maxRGB = max(max(max(n, s), max(e, w)), c);
                vec3 amp = sqrt(clamp(min(minRGB, 1.0 - maxRGB) / max(maxRGB, vec3(0.0001)), 0.0, 1.0));
                vec3 weight = amp * mix(0.0, -0.18, uSharpness);
                c = clamp((c + weight * (n + s + e + w)) / max(1.0 + 4.0 * weight, vec3(0.0001)), minRGB - 0.03, maxRGB + 0.03);
            }

            // 4. Clarity, Dehaze, Shadow Lift & Vibrance
            if (uClarity > 0.005) {
                float darkCh = min(min(avgCross.r, avgCross.g), avgCross.b);
                float haze = max(darkCh - 0.08, 0.0) * (0.24 * uClarity);
                c = clamp((c - haze) / max(1.0 - haze, 0.65), 0.0, 1.0);
                c = clamp(c + (c - avgCross) * (uClarity * 0.55), 0.0, 1.0);
            }
            if (abs(uShadowRecovery) > 0.005) {
                float lum = luma(c);
                float shMask = (1.0 - lum) * (1.0 - lum);
                c = clamp(c + vec3(uShadowRecovery * 0.5 * shMask), 0.0, 1.0);
            }
            if (abs(uVibrance) > 0.005) {
                float lum = luma(c);
                float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
                float gain = uVibrance * (1.0 - sat * 0.7);
                c = clamp(mix(vec3(lum), c, clamp(1.0 + gain, 0.0, 2.0)), 0.0, 1.0);
            }

            fragColor = vec4(c, 1.0);
        }
    """

    // Temporal Motion Frame Blending Pass
    const val FRAME_BLEND_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uFrameA;
        uniform sampler2D uFrameB;
        uniform float uBlendFactor;
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            vec4 a = texture(uFrameA, vTexCoord);
            vec4 b = texture(uFrameB, vTexCoord);
            fragColor = mix(a, b, clamp(uBlendFactor, 0.0, 1.0));
        }
    """

    // Interactive Split-Screen Viewport Composite with Zoom & Pan + Aspect-Fit
    const val SPLIT_VIEWPORT_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uOriginalTex;
        uniform sampler2D uEnhancedTex;
        uniform float uSplitPos;     // 0.0 .. 1.0 in viewport X
        uniform float uZoom;         // 1.0, 2.0, 4.0
        uniform vec2 uPan;           // normalized UV pan offset
        uniform vec2 uViewportAspectScale;
        in vec2 vScreenUv;
        out vec4 fragColor;

        void main() {
            vec2 centered = (vScreenUv - 0.5) * uViewportAspectScale;
            vec2 zoomed = centered / max(uZoom, 1.0) - uPan;
            vec2 mediaUv = zoomed + 0.5;

            if (mediaUv.x < 0.0 || mediaUv.x > 1.0 || mediaUv.y < 0.0 || mediaUv.y > 1.0) {
                fragColor = vec4(0.031, 0.031, 0.039, 1.0);
                return;
            }

            if (vScreenUv.x < uSplitPos) {
                fragColor = texture(uOriginalTex, mediaUv);
            } else {
                fragColor = texture(uEnhancedTex, mediaUv);
            }
        }
    """

    const val PASSTHROUGH_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uInputTex;
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uInputTex, vTexCoord);
        }
    """
}
