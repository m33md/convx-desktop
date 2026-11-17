/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.data

import org.jetbrains.skia.Color
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import kotlin.random.Random

/**
 * Finishes that catch the light the way printed foil and pressed plastic do: holographic rainbow,
 * pearl, liquid chrome, mesh gradients, glitter, prism foil, smoke and nebula. Each is a small Skia
 * shader worked out per pixel on the GPU or the CPU alike, so a finish costs nothing to keep and is
 * drawn once into the disc's print like any other layer.
 *
 * Every shader is centred on (0, 0) with the disc's radius [r] unless a centre is given, so a finish
 * turns and scales with the layer it's on.
 */
object DiscFinish {
    private const val Common = """
        float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
        float noise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
        }
        float fbm(float2 p) {
            float v = 0.0;
            float a = 0.5;
            for (int i = 0; i < 5; i++) {
                v += a * noise(p);
                p = p * 2.03 + float2(17.0, 9.0);
                a *= 0.5;
            }
            return v;
        }
        half3 spectrum(float h) { return half3(0.5 + 0.5 * cos(6.28318 * (h + float3(0.0, 0.33, 0.67)))); }
        float2 turn(float2 p, float a) { float c = cos(a); float s = sin(a); return float2(c * p.x - s * p.y, s * p.x + c * p.y); }
    """

    /** Ribbons of rainbow flowing over dark metal, with a hard glint or two, like a holographic CD. */
    private val holoEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float angle;
            uniform float seed;
            uniform float waves;
            uniform float strength;
            uniform half3 base;
            uniform half4 tint[4];
            uniform float tints;
            $Common
            half3 pick(float k) {
                half3 c = tint[0].rgb;
                for (int i = 0; i < 4; i++) if (float(i) == k) c = tint[i].rgb;
                return c;
            }
            half3 ribbon(float h) {
                if (tints < 0.5) return spectrum(h);
                float x = fract(h) * tints;
                float fi = floor(x);
                return mix(pick(fi), pick(mod(fi + 1.0, tints)), half(smoothstep(0.0, 1.0, x - fi)));
            }
            half4 main(float2 fc) {
                float2 p = turn((fc - center) / radius, angle);
                float s = seed;
                // Broad diagonal sweeps, bent by a slow warp so they run like poured liquid, not like waves.
                float2 w = float2(fbm(p * 0.9 + s), fbm(p * 0.9 - s + 4.0)) - 0.5;
                float flow = (p.y + p.x * 0.45) * waves
                    + 0.22 * sin(p.x * 1.7 + w.y * 2.5 + s)
                    + 0.10 * sin(p.x * 3.1 - p.y * 1.2 + s * 1.7)
                    + 0.55 * w.x;
                float band = 0.5 + 0.5 * sin(flow * 6.28318);
                float rib = smoothstep(0.05, 0.95, band);
                float h = flow * 0.34 + length(p) * 0.2 + p.x * 0.22 + s * 0.1;
                half3 col = ribbon(h);
                // Fine ribbons inside the broad ones, as the foil's grating catches the lamp.
                float fine = 0.5 + 0.5 * sin(flow * 6.28318 * 3.0 + p.x * 2.0);
                col = mix(col, ribbon(h + 0.2), half(0.25 * fine));
                // Pastel, as the foil lifts the colours towards white.
                col = mix(col, half3(1.0), 0.36);
                // The metal under it: a little lighter where the lamp is.
                half3 metal = base * half(0.8 + 0.25 * smoothstep(1.0, -0.6, p.y + p.x * 0.3));
                half3 c = mix(metal, col * half(0.78 + 0.26 * rib), half(clamp(strength * (0.5 + 0.4 * rib), 0.0, 1.0)));
                // Glints along the ribbons, and long thin streaks across the disc.
                float edge = pow(0.5 + 0.5 * sin(flow * 6.28318 + 1.3), 60.0);
                float2 q = turn(p, 0.62 + s * 0.05);
                float y1 = q.y * 90.0;
                float y2 = (q.y + 0.36) * 160.0;
                float streak = exp(-y1 * y1) * smoothstep(1.0, 0.15, abs(q.x)) * 0.8;
                float streak2 = exp(-y2 * y2) * smoothstep(0.8, 0.0, abs(q.x - 0.2)) * 0.45;
                c += half(strength * (edge * 0.22 + streak + streak2));
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Thin-film pearl: pastel colours that swim into each other, as on a soap bubble or a pearl. */
    private val pearlEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float angle;
            uniform float seed;
            uniform float waves;
            uniform half4 tint[4];
            uniform float tints;
            $Common
            half3 pick(float k) {
                half3 c = tint[0].rgb;
                for (int i = 0; i < 4; i++) if (float(i) == k) c = tint[i].rgb;
                return c;
            }
            half4 main(float2 fc) {
                float2 p = turn((fc - center) / radius, angle);
                float n = fbm(p * (1.2 + waves * 1.6) + seed);
                float n2 = fbm(p * 2.4 - seed * 0.7 + n * 1.5);
                float h = n * 0.7 + n2 * 0.35 + p.y * 0.35 + p.x * 0.2;
                half3 film = spectrum(h);
                half3 c;
                if (tints < 0.5) {
                    c = mix(half3(0.96, 0.94, 0.97), film, 0.2);
                } else {
                    float x = fract(h) * tints;
                    float fi = floor(x);
                    c = mix(pick(fi), pick(mod(fi + 1.0, tints)), half(smoothstep(0.0, 1.0, x - fi)));
                    c = mix(c, film, 0.18);
                }
                // A soft sheen where the light falls.
                float sheen = smoothstep(0.9, 0.0, length(p - float2(-0.35, -0.45)));
                c += half(0.16 * sheen) + half(0.06 * sin(n2 * 20.0));
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Liquid chrome: a mirror bent into soft folds, sky above and dark ground below in each fold. */
    private val chromeEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float angle;
            uniform float seed;
            uniform float waves;
            uniform half3 sky;
            uniform half3 ground;
            $Common
            float height(float2 p) {
                // Long folds bent into each other, as when molten metal settles: smooth everywhere.
                float2 w = float2(sin(p.y * 1.3 + seed), sin(p.x * 1.1 - seed * 0.7)) * 0.9 + float2(noise(p * 0.7 + seed), noise(p * 0.7 - seed)) * 0.8;
                return sin(p.x * 1.6 + w.y * 1.8) * 0.5 + sin(p.y * 1.4 + w.x * 1.6 + 1.3) * 0.35 + sin((p.x + p.y) * 2.3 + w.x) * 0.15;
            }
            half4 main(float2 fc) {
                float2 p = turn((fc - center) / radius, angle) * (1.0 + waves * 2.2);
                float e = 0.004;
                float h = height(p);
                float hx = height(p + float2(e, 0.0)) - h;
                float hy = height(p + float2(0.0, e)) - h;
                float3 n = normalize(float3(-hx / e, -hy / e, 1.1));
                // The mirror reflects a studio: dark floor, a soft horizon, pale sky, and a softbox up and left.
                float3 r = reflect(float3(0.0, 0.0, -1.0), n);
                float up = -r.y + r.x * 0.25;
                half3 skyTone = mix(sky, half3(1.0), half(clamp(up, 0.0, 1.0) * 0.8));
                half3 c = mix(ground, skyTone, half(smoothstep(-0.2, 0.2, up)));
                float hz = (up + 0.03) / 0.09;
                c *= half(1.0 - 0.55 * exp(-hz * hz));
                float spec = pow(max(dot(r, normalize(float3(-0.45, -0.6, 0.66))), 0.0), 24.0);
                float spec2 = pow(max(dot(r, normalize(float3(0.6, 0.5, 0.62))), 0.0), 40.0);
                c += half(spec * 0.9 + spec2 * 0.4);
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Soft pools of colour melting together: a mesh gradient. */
    private val meshEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform half4 tint[6];
            uniform float4 spot[6];
            uniform float tints;
            uniform float seed;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                // A little warp, so the pools aren't perfect circles.
                p += float2(fbm(p * 1.3 + seed) - 0.5, fbm(p * 1.3 - seed + 7.0) - 0.5) * 0.5;
                half3 sum = half3(0.0);
                float total = 0.0;
                for (int i = 0; i < 6; i++) {
                    if (float(i) < tints) {
                        float d = length(p - spot[i].xy);
                        float wgt = exp(-d * d / (spot[i].z * spot[i].z));
                        sum += tint[i].rgb * half(wgt);
                        total += wgt;
                    }
                }
                half3 c = sum / half(max(total, 0.0001));
                return half4(c, 1.0);
            }
            """,
        )
    }

    /** Flakes of glitter, each turned its own way, so a few catch the light and most stay dark. */
    private val glitterEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float cell;
            uniform float seed;
            uniform float sparkle;
            uniform half4 tint[4];
            uniform float tints;
            $Common
            half3 pick(float k) {
                half3 c = tint[0].rgb;
                for (int i = 0; i < 4; i++) if (float(i) == k) c = tint[i].rgb;
                return c;
            }
            // One layer of flakes: a tilted square in each cell, lit by how it faces the lamp.
            float4 flakes(float2 p, float size, float offset) {
                float2 g = p / size + offset;
                float2 id = floor(g);
                float2 f = fract(g) - 0.5;
                float r1 = hash(id + seed + offset);
                float r2 = hash(id * 1.7 + seed + 3.1 + offset);
                float r3 = hash(id * 2.3 - seed + 9.7 + offset);
                float2 q = turn(f - (float2(r1, r2) - 0.5) * 0.25, r1 * 6.28);
                float d = max(abs(q.x), abs(q.y));
                float flake = smoothstep(0.36, 0.3, d);
                float face = pow(r3, mix(7.0, 2.2, sparkle));
                float glint = pow(face, 6.0) * smoothstep(0.3, 0.0, length(q)) * 2.0;
                return float4(flake, face, glint, floor(r2 * 97.0));
            }
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float4 a = flakes(p, cell, 0.0);
                float4 b = flakes(turn(p, 0.7), cell * 0.7, 0.5);
                half3 ca = pick(mod(a.w, max(tints, 1.0)));
                half3 cb = pick(mod(b.w, max(tints, 1.0)));
                // The glue under the flakes: the colours, dark.
                half3 c = (ca + cb) * half(0.12);
                c = mix(c, ca * half(0.3 + 0.9 * a.y), half(a.x));
                c = mix(c, cb * half(0.3 + 0.9 * b.y), half(b.x * 0.85));
                c += half((a.z * a.x + b.z * b.x) * sparkle);
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Broken-glass prism foil: shards, each throwing its own slice of the rainbow. */
    private val prismEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float cell;
            uniform float seed;
            uniform float strength;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float2 g = p / cell;
                float2 id = floor(g);
                float2 f = fract(g);
                // Voronoi: the nearest of the jittered points owns this pixel.
                float best = 9.0;
                float second = 9.0;
                float2 owner = float2(0.0);
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        float2 n = float2(float(x), float(y));
                        float2 pt = n + float2(hash(id + n + seed), hash(id + n + seed + 5.3));
                        float d = length(pt - f);
                        if (d < best) { second = best; best = d; owner = id + n; }
                        else if (d < second) { second = d; }
                    }
                }
                float h = hash(owner + seed * 1.3);
                float facet = hash(owner + 11.0);
                float2 tilt = float2(hash(owner + 2.0), hash(owner + 3.0)) - 0.5;
                half3 c = spectrum(h + dot(f, tilt) * 0.6 + p.x * 0.25 + p.y * 0.15);
                c = mix(half3(0.86, 0.87, 0.9), c, half(strength * (0.25 + 0.5 * facet)));
                c *= half(0.78 + 0.34 * facet + 0.25 * dot(f - 0.5, tilt));
                c += half(step(0.93, facet) * 0.35);
                float crack = smoothstep(0.0, 0.05, second - best);
                c = mix(half3(1.0), c, half(0.35 + 0.65 * crack));
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Smoke in clear plastic: soft wisps of colour over a see-through tint. */
    private val smokeEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float scale;
            uniform half4 ink;
            uniform half4 wisp;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius * scale;
                float2 q = float2(fbm(p + seed), fbm(p + seed + float2(5.2, 1.3)));
                float2 r = float2(fbm(p + 4.0 * q + float2(1.7, 9.2)), fbm(p + 4.0 * q + float2(8.3, 2.8)));
                float v = fbm(p + 3.5 * r);
                float w = smoothstep(0.35, 0.85, v);
                half4 c = mix(ink, wisp, half(w));
                return half4(c.rgb * c.a, c.a);
            }
            """,
        )
    }

    /** A nebula: clouds of colour lit from inside, with a dust of stars. */
    private val nebulaEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float scale;
            uniform half4 tint[4];
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius * scale;
                float2 q = float2(fbm(p + seed), fbm(p - seed + 3.0));
                float a = fbm(p * 1.4 + 2.5 * q);
                float b = fbm(p * 2.2 - 1.5 * q + 7.0);
                half3 c = tint[0].rgb;
                c = mix(c, tint[1].rgb, half(smoothstep(0.35, 0.75, a)));
                c = mix(c, tint[2].rgb, half(smoothstep(0.45, 0.85, b) * 0.85));
                c += tint[3].rgb * half(pow(smoothstep(0.55, 0.95, a * b * 2.2), 2.0) * 0.9);
                float star = step(0.9975, hash(floor(fc * 0.9) + seed));
                float twinkle = hash(floor(fc * 0.9) + seed + 2.0);
                c += half(star * (0.4 + 0.6 * twinkle));
                return half4(clamp(c, half3(0.0), half3(1.0)), 1.0);
            }
            """,
        )
    }

    /** Paint poured and spun: bands of colour twisted round the middle, as on a swirl pressing. */
    private val swirlEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float twist;
            uniform float scale;
            uniform half4 tint[5];
            uniform float tints;
            $Common
            half3 pick(float k) {
                half3 c = tint[0].rgb;
                for (int i = 0; i < 5; i++) if (float(i) == k) c = tint[i].rgb;
                return c;
            }
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float d = length(p);
                float a = atan(p.y, p.x) + twist * (1.25 - d) * 2.4 + (fbm(p * 2.0 + seed) - 0.5) * 1.2;
                float2 q = float2(cos(a), sin(a)) * d * scale;
                float2 w = float2(fbm(q * 1.4 + seed), fbm(q * 1.4 - seed + 4.0));
                float v = fbm(q * 1.8 + w * 2.6 + seed * 0.3);
                float t = fract(v * 1.7 + d * 0.3) * tints;
                float k = floor(t);
                float f = smoothstep(0.3, 0.7, fract(t));
                half3 c = mix(pick(k), pick(mod(k + 1.0, tints)), half(f));
                // Ridges where the paint folded over itself catch a little light.
                float ridge = pow(1.0 - abs(fract(t) - 0.5) * 2.0, 10.0);
                c += half(ridge * 0.12);
                return half4(c, 1.0);
            }
            """,
        )
    }

    /** A watercolour wash: pigment pooling darker at its edges, paper showing where it thins, brushed round the disc. */
    private val watercolorEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float scale;
            uniform float wet;
            uniform half4 pigment;
            uniform half4 paper;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float d = length(p);
                float2 dir = d > 0.0001 ? p / d : float2(1.0, 0.0);
                // Brushed round: the noise stretched along the circle.
                float2 round = float2(d * 18.0 * scale, 0.0) + dir * (1.2 * scale);
                float brush = fbm(round + seed);
                float wash = fbm(p * 2.2 * scale + seed + brush * 0.8);
                float v = clamp(wash * 0.75 + brush * 0.45, 0.0, 1.0);
                float body = smoothstep(0.25, 0.8 - wet * 0.25, v);
                // Tide lines: where a wet edge dried, a darker ring of pigment.
                float tv = (v - 0.55) * 22.0;
                float tide = exp(-tv * tv) * 0.35;
                half3 c = mix(paper.rgb, pigment.rgb, half(clamp(body + tide, 0.0, 1.0)));
                c *= half(0.92 + 0.08 * noise(fc * 0.35));
                return half4(c, 1.0);
            }
            """,
        )
    }

    /** Woven cloth, as a canvas cover or a linen sleeve: threads both ways, mottled, with flecks. */
    private val canvasEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float thread;
            uniform float mottle;
            uniform half4 tone;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float2 g = p / thread;
                float wx = 0.5 + 0.5 * sin(g.x * 3.14159 + noise(float2(g.y * 0.3, g.x)) * 1.5);
                float wy = 0.5 + 0.5 * sin(g.y * 3.14159 + noise(float2(g.x * 0.3, g.y) + 7.0) * 1.5);
                float over = step(0.5, fract((floor(g.x) + floor(g.y)) * 0.5));
                float weave = mix(wx, wy, over);
                float m = fbm(p * 3.0 + seed);
                float fleck = step(0.985, hash(floor(g * 0.7) + seed));
                half3 c = tone.rgb * half(0.8 + 0.22 * weave) * half(1.0 - mottle * 0.45 * (m - 0.35));
                c *= half(1.0 - 0.25 * fleck);
                return half4(c, 1.0);
            }
            """,
        )
    }

    /** Denim: indigo twill, the pale weft showing in diagonal lines, worn lighter in patches. */
    private val denimEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float seed;
            uniform float thread;
            uniform float wear;
            uniform half4 indigo;
            uniform half4 weft;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float2 g = p / thread;
                float twill = 0.5 + 0.5 * sin((g.x + g.y) * 3.14159 * 1.0 + noise(g * 0.5) * 0.8);
                float slub = noise(float2(g.x * 0.15, g.y * 2.0) + seed);
                float worn = smoothstep(0.45, 0.9, fbm(p * 2.5 + seed)) * wear;
                half3 c = mix(indigo.rgb, weft.rgb, half(pow(twill, 3.0) * 0.32 + worn * 0.45 + slub * 0.08));
                c *= half(0.88 + 0.12 * noise(fc * 0.5));
                return half4(c, 1.0);
            }
            """,
        )
    }

    /** A CD's data side: two wedges of spectrum either side of the light, and fine tracks. */
    private val diffractionEffect by lazy {
        compile(
            """
            uniform float2 center;
            uniform float radius;
            uniform float angle;
            uniform float strength;
            $Common
            half4 main(float2 fc) {
                float2 p = (fc - center) / radius;
                float d = length(p);
                float a = atan(p.y, p.x) - angle;
                // Each side of the hole splits the light into a fan of colour, red on the outside.
                float wedge = cos(a * 2.0);
                float fan = smoothstep(0.55, 1.0, wedge);
                float h = d * 0.85 + (1.0 - wedge) * 1.6 + 0.08;
                half3 c = spectrum(h);
                float side = smoothstep(0.2, 0.95, abs(sin(a))) * 0.25;
                float k = (fan * 0.85 + side * smoothstep(0.3, 1.0, d)) * strength;
                // Fine tracks catching the light: rings too fine to see one by one.
                float tracks = 0.5 + 0.5 * sin(d * 900.0);
                c *= half(0.85 + 0.15 * tracks);
                return half4(c * half(k), half(k));
            }
            """,
        )
    }

    private fun compile(source: String): RuntimeEffect = RuntimeEffect.makeForShader(source.trimIndent())

    private fun channels(color: Int) = floatArrayOf(Color.getR(color) / 255f, Color.getG(color) / 255f, Color.getB(color) / 255f, Color.getA(color) / 255f)

    private fun palette(colors: List<Int>, slots: Int): FloatArray {
        val out = FloatArray(slots * 4)
        for (i in 0 until slots) {
            val c = channels(colors.getOrElse(i) { colors.lastOrNull() ?: Color.WHITE })
            System.arraycopy(c, 0, out, i * 4, 4)
        }
        return out
    }

    private inline fun build(effect: RuntimeEffect, setup: RuntimeShaderBuilder.() -> Unit): Shader {
        val builder = RuntimeShaderBuilder(effect)
        builder.setup()
        return builder.makeShader(null)
    }

    private fun seedOf(seed: Int) = (seed.mod(997)) * 0.731f + 1.3f

    /**
     * Holographic foil over [base] metal. With no [ribbons] it's a full rainbow; with two to four,
     * the ribbons cycle through those colours instead. [waves] is how many ribbons cross the disc.
     */
    fun holographic(r: Float, base: Int, ribbons: List<Int> = emptyList(), angle: Float = 0f, seed: Int = 0, waves: Float = 0.5f, strength: Float = 1f, cx: Float = 0f, cy: Float = 0f): Shader =
        build(holoEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("angle", Math.toRadians(angle.toDouble()).toFloat())
            uniform("seed", seedOf(seed))
            uniform("waves", 0.55f + waves.coerceIn(0f, 1f) * 2.2f)
            uniform("strength", strength.coerceIn(0f, 1.2f))
            val b = channels(base)
            uniform("base", b[0], b[1], b[2])
            uniform("tint", palette(ribbons, 4))
            uniform("tints", if (ribbons.size >= 2) ribbons.size.coerceAtMost(4).toFloat() else 0f)
        }

    /** Iridescent pearl; [colors] (up to four) steer its pastels, or none for the bubble's own. */
    fun pearl(r: Float, colors: List<Int> = emptyList(), angle: Float = 0f, seed: Int = 0, waves: Float = 0.5f, cx: Float = 0f, cy: Float = 0f): Shader =
        build(pearlEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("angle", Math.toRadians(angle.toDouble()).toFloat())
            uniform("seed", seedOf(seed))
            uniform("waves", waves.coerceIn(0f, 1f))
            uniform("tint", palette(colors, 4))
            uniform("tints", if (colors.size >= 2) colors.size.coerceAtMost(4).toFloat() else 0f)
        }

    /** Liquid chrome reflecting [sky] above and [ground] below. */
    fun chrome(r: Float, sky: Int = 0xFFBFD8FF.toInt(), ground: Int = 0xFF2A2B33.toInt(), angle: Float = 0f, seed: Int = 0, waves: Float = 0.5f, cx: Float = 0f, cy: Float = 0f): Shader =
        build(chromeEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("angle", Math.toRadians(angle.toDouble()).toFloat())
            uniform("seed", seedOf(seed))
            uniform("waves", waves.coerceIn(0f, 1f))
            val s = channels(sky)
            val g = channels(ground)
            uniform("sky", s[0], s[1], s[2])
            uniform("ground", g[0], g[1], g[2])
        }

    /** A mesh gradient of up to six [colors], their pools placed by [seed]; [spread] is how big each pool is. */
    fun mesh(r: Float, colors: List<Int>, seed: Int = 0, spread: Float = 0.5f, cx: Float = 0f, cy: Float = 0f): Shader {
        val random = Random(seed * 31 + 7)
        val n = colors.size.coerceIn(1, 6)
        val spots = FloatArray(24)
        for (i in 0 until 6) {
            // Round the disc, every pool in its own direction, one near the middle.
            val a = (i * 2.0 * Math.PI / n + random.nextDouble() * 0.9).toFloat()
            val d = if (i == 0) random.nextFloat() * 0.25f else 0.45f + random.nextFloat() * 0.5f
            spots[i * 4] = kotlin.math.cos(a) * d
            spots[i * 4 + 1] = kotlin.math.sin(a) * d
            spots[i * 4 + 2] = 0.22f + spread.coerceIn(0f, 1f) * 0.5f + random.nextFloat() * 0.12f
        }
        return build(meshEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("tint", palette(colors, 6))
            uniform("spot", spots)
            uniform("tints", n.toFloat())
            uniform("seed", seedOf(seed))
        }
    }

    /** Glitter flakes in [colors]; [size] sets the flakes, [sparkle] how many flash. See-through between flakes. */
    fun glitter(r: Float, colors: List<Int>, size: Float = 0.5f, sparkle: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(glitterEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("cell", 0.014f + size.coerceIn(0f, 1f) * 0.045f)
            uniform("seed", seedOf(seed))
            uniform("sparkle", sparkle.coerceIn(0f, 1f))
            uniform("tint", palette(colors.ifEmpty { listOf(Color.WHITE) }, 4))
            uniform("tints", colors.size.coerceIn(1, 4).toFloat())
        }

    /** Prism foil in shards [size] big. */
    fun prism(r: Float, size: Float = 0.5f, strength: Float = 0.8f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(prismEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("cell", 0.06f + size.coerceIn(0f, 1f) * 0.3f)
            uniform("seed", seedOf(seed))
            uniform("strength", strength.coerceIn(0f, 1f))
        }

    /** Smoke: [wisp] curling through [ink], both may be see-through. */
    fun smoke(r: Float, ink: Int, wisp: Int, size: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(smokeEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("scale", 3.2f - size.coerceIn(0f, 1f) * 2.4f)
            val i = channels(ink)
            val w = channels(wisp)
            uniform("ink", i[0], i[1], i[2], i[3])
            uniform("wisp", w[0], w[1], w[2], w[3])
        }

    /** A nebula in four colours: the dark of space, two clouds and the light inside them. */
    fun nebula(r: Float, colors: List<Int>, size: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(nebulaEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("scale", 2.6f - size.coerceIn(0f, 1f) * 1.8f)
            uniform("tint", palette(colors.ifEmpty { listOf(0xFF05040F.toInt(), 0xFF5B2A86.toInt(), 0xFF1F6FB2.toInt(), 0xFFFFB3E6.toInt()) }, 4))
        }

    /** Spun paint in up to five [colors]; [twist] is how far it was spun, [size] how big its bands are. */
    fun swirl(r: Float, colors: List<Int>, twist: Float = 0.5f, size: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(swirlEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("twist", 0.2f + twist.coerceIn(0f, 1f) * 1.6f)
            uniform("scale", 2.2f - size.coerceIn(0f, 1f) * 1.6f)
            val list = colors.ifEmpty { listOf(0xFF3B2412.toInt(), 0xFFE0A33A.toInt(), 0xFFF3E2B3.toInt()) }
            uniform("tint", palette(list, 5))
            uniform("tints", list.size.coerceIn(1, 5).toFloat())
        }

    /** A watercolour wash of [pigment] on [paper]; [wet] lets it spread further. */
    fun watercolor(r: Float, pigment: Int, paper: Int, size: Float = 0.5f, wet: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(watercolorEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("scale", 1.8f - size.coerceIn(0f, 1f) * 1.2f)
            uniform("wet", wet.coerceIn(0f, 1f))
            val a = channels(pigment)
            val b = channels(paper)
            uniform("pigment", a[0], a[1], a[2], a[3])
            uniform("paper", b[0], b[1], b[2], b[3])
        }

    /** Woven canvas in [tone]; [size] is the threads' size, [mottle] how patchy it is. */
    fun canvas(r: Float, tone: Int, size: Float = 0.5f, mottle: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(canvasEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("thread", 0.006f + size.coerceIn(0f, 1f) * 0.02f)
            uniform("mottle", mottle.coerceIn(0f, 1f))
            val t = channels(tone)
            uniform("tone", t[0], t[1], t[2], t[3])
        }

    /** Denim in [indigo] with a [weft] of pale thread; [wear] fades it in patches. */
    fun denim(r: Float, indigo: Int, weft: Int, size: Float = 0.5f, wear: Float = 0.5f, seed: Int = 0, cx: Float = 0f, cy: Float = 0f): Shader =
        build(denimEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("seed", seedOf(seed))
            uniform("thread", 0.005f + size.coerceIn(0f, 1f) * 0.016f)
            uniform("wear", wear.coerceIn(0f, 1f))
            val a = channels(indigo)
            val b = channels(weft)
            uniform("indigo", a[0], a[1], a[2], a[3])
            uniform("weft", b[0], b[1], b[2], b[3])
        }

    /** A bare CD's rainbow: premultiplied colour to add over the disc, as strong as [strength]. */
    fun diffraction(cx: Float, cy: Float, r: Float, angle: Float, strength: Float): Shader =
        build(diffractionEffect) {
            uniform("center", cx, cy)
            uniform("radius", r)
            uniform("angle", Math.toRadians(angle.toDouble()).toFloat())
            uniform("strength", strength.coerceIn(0f, 1.5f))
        }
}
