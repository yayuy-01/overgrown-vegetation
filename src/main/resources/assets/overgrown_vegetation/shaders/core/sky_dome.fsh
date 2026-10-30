#version 150

// Overgrown Vegetation: light of the sky (client/SkyDome.java holds the explanation). Keep this file ASCII-only.
// Mode: 0 = multiply pass (deep blue towards the zenith), 1 = additive pass (sun halo, sunrise band, blue hour, stars above the clouds),
// 2 = glare (additive, drawn over clouds and terrain).
// SunDir = (direction of the sun, halo or glare strength). Glow = vanilla sunrise colour (a = strength). Light = (zenith blue,
// blue hour, star brightness, -). StarMat turns a view direction into the rotating star space.

uniform vec4 Mode;
uniform vec4 SunDir;
uniform vec4 SkyColor;
uniform vec4 Glow;
uniform vec4 Light;
uniform mat4 StarMat;
// High = (how high the camera is, 0 at y 450 .. 1 at y 3200): the zenith blue gets deeper and starts lower.
uniform vec4 High;
// Mode 3 (painted horizon above the clouds, alpha blended): Band = (view y of the cloud sea horizon, depth below it, height of the storm heads,
// strength), BandColor = (cloud colour, haze amount).
uniform vec4 Band;
uniform vec4 BandColor;
// How cloudy the far regions are in 16 directions around the horizon (index 0 = angle -pi, as atan(z, x)); clear regions show little of the band.
uniform vec4 BandCover0;
uniform vec4 BandCover1;
uniform vec4 BandCover2;
uniform vec4 BandCover3;
// BandLine.x: 0 = the painted sea goes up close to eye level (low above the clouds), 1 = it stops at the curved horizon (high up, where the
// strip between the horizon and eye level grew into a wide flat white band; the sky shows there instead).
uniform vec4 BandLine;

in vec3 ovDir;

out vec4 fragColor;

float ovHash3(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float ovNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(ovHash3(i), ovHash3(i + vec3(1.0, 0.0, 0.0)), f.x), mix(ovHash3(i + vec3(0.0, 1.0, 0.0)), ovHash3(i + vec3(1.0, 1.0, 0.0)), f.x), f.y);
    float b = mix(mix(ovHash3(i + vec3(0.0, 0.0, 1.0)), ovHash3(i + vec3(1.0, 0.0, 1.0)), f.x), mix(ovHash3(i + vec3(0.0, 1.0, 1.0)), ovHash3(i + vec3(1.0, 1.0, 1.0)), f.x), f.y);
    return mix(a, b, f.z);
}

// Small stars on a grid of cells in star space (one star at most per cell, random place and brightness).
float ovStars(vec3 d, float scale, float density) {
    vec3 p = d * scale;
    vec3 cell = floor(p);
    float h = ovHash3(cell);
    if (h > density) {
        return 0.0;
    }
    vec3 centre = cell + 0.5 + (vec3(ovHash3(cell + 7.0), ovHash3(cell + 13.0), ovHash3(cell + 29.0)) - 0.5) * 0.7;
    float r = length(p - centre);
    float bright = pow(ovHash3(cell + 3.0), 3.0);
    return bright * (1.0 - smoothstep(0.03, 0.11, r));
}

float ovCoverAt(int i) {
    vec4 c = i < 4 ? BandCover0 : (i < 8 ? BandCover1 : (i < 12 ? BandCover2 : BandCover3));
    int k = i - (i / 4) * 4;
    return k == 0 ? c.x : (k == 1 ? c.y : (k == 2 ? c.z : c.w));
}

float ovBandCover(vec2 hz) {
    float f = (atan(hz.y, hz.x) / 6.2831853 + 0.5) * 16.0;
    float base = floor(f);
    int i0 = int(base) - (int(base) / 16) * 16;
    int i1 = i0 + 1 - ((i0 + 1) / 16) * 16;
    return mix(ovCoverAt(i0), ovCoverAt(i1), f - base);
}

void main() {
    vec3 v = normalize(ovDir);
    vec3 s = normalize(SunDir.xyz);
    float toSun = dot(v, s);
    float elev = v.y;
    if (Mode.x > 2.5) {
        // Painted horizon: the far cloud sea up to its own horizon line (Band.x) and the heads of distant storms rising above it
        // (the voxel clouds are drawn over it, so it shows past their far edge and through their far gaps).
        vec2 hz = normalize(v.xz + vec2(0.0001, 0.0));
        float big = ovNoise3(vec3(hz * 2.6, 1.7));
        float mid = ovNoise3(vec3(hz * 8.0, 4.1));
        float fine = ovNoise3(vec3(hz * 24.0, 8.3));
        float bc = ovBandCover(hz);
        float heads = smoothstep(0.5, 0.8, big) * (0.55 + 0.45 * mid) * bc;
        float rise = Band.z * (0.12 * mid + 0.06 * fine + heads * (0.75 + 0.25 * fine));
        // The painted sea goes on from the horizon of the voxel sea (Band.x) up close to eye level, where the far sea of a flat world would meet the sky;
        // the storm heads rise above that line.
        float line = mix(Band.x * 0.2, Band.x + 0.004, BandLine.x);
        float top = line + rise;
        float soft = 0.0015 + Band.z * 0.04;
        float cover = (1.0 - smoothstep(top - soft, top + soft, elev)) * smoothstep(Band.x - Band.y, Band.x - Band.y * 0.35, elev)
                * mix(0.12, 1.0, bc);
        if (cover <= 0.002) {
            discard;
        }
        // Two cel tones: the sea below the line is the lit top of the clouds; the heads are lit above and shaded below (the step wavers).
        float tone = elev < line ? 1.0 : step(0.4, (elev - line) / max(rise, 0.0005) + 0.3 * (fine - 0.5));
        vec2 sh = normalize(SunDir.xz + vec2(0.0001, 0.0));
        float toward = max(dot(hz, sh), 0.0);
        vec3 col = mix(BandColor.rgb * vec3(0.8, 0.84, 0.96), BandColor.rgb, tone) * (0.93 + 0.1 * toward);
        // Sunrise and sunset: the heads on the sun side take the red light.
        col = mix(col, col * 0.5 + Glow.rgb * 0.75, clamp(Glow.a * (0.3 + heads) * toward * tone, 0.0, 1.0) * 0.7);
        vec3 haze = min(SkyColor.rgb * vec3(0.9, 0.97, 1.1), vec3(1.0));
        col = mix(col, haze, BandColor.a + (1.0 - BandColor.a) * 0.45 * smoothstep(Band.x, line, elev));
        fragColor = vec4(col, cover * Band.w);
        return;
    }
    if (Mode.x < 0.5) {
        // Multiply: deeper ultramarine towards the zenith; left alone near the horizon and around the sun.
        float g = Light.x * smoothstep(mix(0.04, -0.06, High.x), mix(0.85, 0.5, High.x), elev) * (1.0 - smoothstep(0.965, 0.985, toSun));
        vec3 m = mix(vec3(1.0), mix(vec3(0.56, 0.69, 0.97), vec3(0.4, 0.52, 0.92), High.x), g);
        // Where the multiplier would not change an 8-bit colour (darkens by less than half a step), skip the blend (2026-10-24, the dome
        // covered the whole screen twice before the terrain was drawn over it).
        vec3 dm = 1.0 - m;
        if (max(dm.r, max(dm.g, dm.b)) < 0.5 / 255.0) {
            discard;
        }
        fragColor = vec4(m, 1.0);
        return;
    }
    if (Mode.x > 1.5) {
        // Glare over everything when the sun is in view.
        float k = max(toSun, 0.0);
        vec3 glare = vec3(1.0, 0.95, 0.85) * (0.16 * pow(k, 30.0) + 0.06 * pow(k, 5.0));
        // Less than half an 8-bit step away from the sun: nothing to add.
        if (glare.r * SunDir.w < 0.5 / 255.0) {
            discard;
        }
        fragColor = vec4(glare * SunDir.w, 1.0);
        return;
    }
    vec3 c = vec3(0.0);
    // Sun halo: a strong glow close to the sun and a wide faint one.
    float k = max(toSun, 0.0);
    c += vec3(1.0, 0.96, 0.86) * SunDir.w * (0.4 * pow(k, 900.0) + 0.12 * pow(k, 60.0) + 0.045 * pow(k, 8.0));
    // Sunrise and sunset band: the sky near the horizon goes orange, widest towards the sun, with thin violet above.
    if (Glow.a > 0.001) {
        vec2 sh = normalize(s.xz + vec2(0.0001, 0.0));
        vec2 vh = normalize(v.xz + vec2(0.0001, 0.0));
        float toward = max(dot(vh, sh), 0.0);
        float band = exp(-max(elev, 0.0) / mix(0.1, 0.24, toward)) * (0.35 + 0.65 * toward * toward);
        c += Glow.rgb * Glow.a * band * 0.32;
        float violet = exp(-pow((elev - 0.32) / 0.16, 2.0)) * (0.4 + 0.6 * toward);
        c += vec3(0.22, 0.09, 0.3) * Glow.a * violet * 0.35;
    }
    // Blue hour: a deep blue over the whole sky, deeper overhead.
    c += vec3(0.03, 0.07, 0.19) * Light.y * (0.6 + 0.4 * clamp(elev, 0.0, 1.0));
    // Stars above the clouds: many small stars and a faint milky way band (in star space, turning with the sky).
    if (Light.z > 0.001 && elev > -0.1) {
        vec3 d = normalize((StarMat * vec4(v, 0.0)).xyz);
        vec3 bandAxis = normalize(vec3(0.35, 0.82, 0.45));
        float across = dot(d, bandAxis);
        float milky = exp(-across * across / 0.03) * (0.55 + 0.45 * ovNoise3(d * 6.0)) * (0.6 + 0.4 * ovNoise3(d * 17.0 + 5.0));
        float star = ovStars(d, 220.0, 0.05 + 0.12 * milky) + 0.6 * ovStars(d, 420.0, 0.04 + 0.1 * milky);
        float twinkle = 0.8 + 0.2 * ovNoise3(d * 400.0 + vec3(Light.w));
        float horizon = smoothstep(-0.1, 0.15, elev);
        c += (vec3(0.9, 0.93, 1.0) * star * twinkle + vec3(0.11, 0.12, 0.17) * milky) * Light.z * horizon;
    }
    // Adding less than half an 8-bit step changes nothing: skip the blend.
    if (max(c.r, max(c.g, c.b)) < 0.5 / 255.0) {
        discard;
    }
    fragColor = vec4(c, 1.0);
}
