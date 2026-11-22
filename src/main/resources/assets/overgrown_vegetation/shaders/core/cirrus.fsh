#version 150

// Overgrown Vegetation: cirrus (client/Cirrus.java holds the explanation). Keep this file ASCII-only.
// Field = (camera position in the cirrus field coordinates modulo 65536, wind direction x, z). Cirrus = (amount of the day,
// strength setting, inside-a-cloud fade, rough weather). SunFlat = horizontal sun direction (x, z).

uniform vec4 ColorModulator;
uniform vec4 Split;
uniform vec4 Field;
uniform vec4 CloudColor;
uniform vec4 SunDir;
uniform vec4 SunColor;
uniform vec4 SkyColor;
uniform vec4 SunFlat;
uniform vec4 Glow;
uniform vec4 Cirrus;

in vec3 ovPos;

out vec4 fragColor;

float ovHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// Smooth value noise, 0..1.
float ovNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = ovHash(i);
    float b = ovHash(i + vec2(1.0, 0.0));
    float c = ovHash(i + vec2(0.0, 1.0));
    float e = ovHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, e, f.x), f.y);
}

float ovFbm(vec2 p) {
    return (0.5 * ovNoise(p) + 0.25 * ovNoise(p * 2.03 + 17.0) + 0.125 * ovNoise(p * 4.01 + 31.0) + 0.0625 * ovNoise(p * 8.0 + 7.0)) / 0.9375;
}

void main() {
    float dist = length(ovPos);
    // The far pass reaches 24 blocks into the near range (the near pass draws over it), so no gap shows at the split.
    if (Split.y > 0.5 ? dist < Split.x - 24.0 : (Split.y < -0.5 && dist >= Split.x)) {
        discard;
    }
    float d = length(ovPos.xz);
    vec2 p = ovPos.xz + Field.xy;
    vec2 dir = Field.zw;
    vec2 perp = vec2(-dir.y, dir.x);
    float along = dot(p, dir);
    float across = dot(p, perp);
    // Cirrus zones: about half of the land (very large patches) has none at all, so it shows here and not there.
    float zone = smoothstep(0.47, 0.6, ovFbm(p / 14000.0 + vec2(71.0, -23.0)));
    if (zone <= 0.001) {
        discard;
    }
    // Where the cirrus is inside a zone: large patches, wider on days with more of it.
    float patch = ovFbm(p / 2600.0);
    float cover = zone * smoothstep(0.66 - 0.4 * Cirrus.x, 0.8 - 0.4 * Cirrus.x, patch);
    if (cover <= 0.001) {
        discard;
    }
    // Fibres: long along the wind, thin across it, slowly bending (the across coordinate is pushed around by a large noise).
    float warp = (ovFbm(vec2(along / 1800.0, across / 900.0) + 5.0) - 0.5) * 520.0;
    float ac = across + warp;
    float f = 0.55 * ovNoise(vec2(along / 900.0, ac / 30.0)) + 0.3 * ovNoise(vec2(along / 330.0, ac / 11.0) + 9.0)
            + 0.15 * ovNoise(vec2(along / 120.0, ac / 5.0) + 3.0);
    float fibre = smoothstep(0.42, 0.82, f);
    // Mares' tails: each streak fades towards one end.
    float tail = smoothstep(0.2, 0.8, ovNoise(vec2(along / 1400.0 + 40.0, ac / 160.0)));
    float a = cover * fibre * mix(0.45, 1.0, tail);
    // Painted look: pulled towards two flat levels.
    a = mix(a, 0.55 * smoothstep(0.22, 0.3, a) + 0.45 * smoothstep(0.55, 0.63, a), 0.5);
    a *= 0.62 * Cirrus.y;
    // Gone far away (towards the horizon) and inside a cloud (the fog hides it).
    a *= (1.0 - smoothstep(6500.0, 12500.0, d)) * (1.0 - Cirrus.z);
    if (a <= 0.003) {
        discard;
    }
    vec3 c = CloudColor.rgb;
    // Light scattered forwards: brighter towards the sun.
    vec3 v = normalize(ovPos);
    float fwd = pow(max(dot(v, SunDir.xyz), 0.0), 8.0) * SunDir.w;
    c += SunColor.rgb * fwd * 0.5;
    a = min(1.0, a * (1.0 + 0.6 * fwd));
    // Sunrise and sunset: the highest clouds take the colour strongly (and keep it longest).
    if (Glow.a > 0.001) {
        vec2 lh = normalize(SunFlat.xy + vec2(0.0001, 0.0));
        vec2 dh = normalize(ovPos.xz + vec2(0.0001, 0.0));
        float toward = max(dot(dh, lh), 0.0);
        float g = clamp(Glow.a * (0.55 + 0.45 * toward), 0.0, 1.0);
        c = mix(c, Glow.rgb * 1.05 + vec3(0.08, 0.0, 0.06), g);
    }
    // Rough weather: greyer.
    c = mix(c, c * vec3(0.7, 0.75, 0.82), Cirrus.w);
    // Blue haze: far cirrus melts into the sky colour.
    c = mix(c, SkyColor.rgb, 0.55 * (1.0 - exp(-d / 7000.0)));
    fragColor = vec4(min(c, vec3(1.0)), clamp(a, 0.0, 1.0)) * ColorModulator;
}
