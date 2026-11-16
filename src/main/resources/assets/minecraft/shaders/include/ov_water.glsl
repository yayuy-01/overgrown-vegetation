#version 150

// Overgrown Vegetation: the sun and the moon mirrored on open water (11-06, "light of the vanilla areas" 4;
// client/atmosphere/WaterGlint.java holds the explanation and sets the uniforms). Keep this file ASCII-only.
//
// Vanilla water is a flat sheet of the sky colour: no sunset and no moon on it. Here an up-facing face of the translucent pass gets a
// Blinn highlight between the view and the sun (by day) or the moon (at night), on a normal tilted by a few slow waves so the highlight
// breaks into a glittering path. Only where the face is open to the sky (vertex sky light 15 of 15: not under leaves or a roof) and
// not underground (the cave water has its own mirror of the point lights, ov_cave.glsl). The translucent pass also holds glass and ice;
// they get the same highlight on their top faces (provisional).
//
// OvWaterSpec  = (direction to the sun or the moon xyz, strength (0 = off; already scaled by daylight, moon phase, rain))
// OvWaterSpecB = (colour of the light rgb (gold to peach at sunset, blue-white at night), shininess)
// OvWaterSpecC = (wave slope (rain makes it rougher), time in seconds wrapped to 600, camera x and z wrapped to 1024)
uniform vec4 OvWaterSpec;
uniform vec4 OvWaterSpecB;
uniform vec4 OvWaterSpecC;

// Slope of the slow waves at camera-relative position pos (world x, z of the waves wrap every 1024 blocks and every 600 seconds,
// so every wave has a whole number of cycles in both: no seam). Wavelengths about 0.5 to 4.7 blocks, periods 0.67 to 3.8 seconds
// (nothing under half a second).
// Pixel art (11-12, user: "too smooth for Minecraft; can it be dot art?"): a camera-relative point on an up-facing water face, snapped
// to the centre of its 1/16-block texel on the world grid (through the wrapped camera position OvWaterSpecC.zw, a whole number of
// texels). The glint, the mirrored sky, the shallows and the foam all read the snapped point, so each texel takes one flat value.
vec3 ovWaterPixel(vec3 pos) {
    vec2 w = (floor((OvWaterSpecC.zw + pos.xz) * 16.0) + 0.5) / 16.0;
    return vec3(w.x - OvWaterSpecC.z, pos.y, w.y - OvWaterSpecC.w);
}

vec2 ovWaterSlope(vec3 pos) {
    vec2 w = OvWaterSpecC.zw + pos.xz;
    float t = OvWaterSpecC.y;
    const float TAU = 6.2831853;
    vec2 k1 = vec2(230.0, 70.0) * (TAU / 1024.0);
    vec2 k2 = vec2(-90.0, 200.0) * (TAU / 1024.0);
    vec2 k3 = vec2(150.0, -260.0) * (TAU / 1024.0);
    vec2 k4 = vec2(560.0, 450.0) * (TAU / 1024.0);
    float p1 = dot(k1, w) - t * (160.0 * TAU / 600.0);
    float p2 = dot(k2, w) - t * (200.0 * TAU / 600.0);
    float p3 = dot(k3, w) - t * (250.0 * TAU / 600.0);
    float p4 = dot(k4, w) - t * (500.0 * TAU / 600.0);
    // Two fine ripples (wavelength about 0.5 to 0.6 blocks, periods 0.67 and 0.86 seconds) break the path into glitter.
    vec2 k5 = vec2(1400.0, -900.0) * (TAU / 1024.0);
    vec2 k6 = vec2(-1200.0, -1700.0) * (TAU / 1024.0);
    float p5 = dot(k5, w) - t * (700.0 * TAU / 600.0);
    float p6 = dot(k6, w) - t * (900.0 * TAU / 600.0);
    vec2 s = normalize(k1) * cos(p1) + normalize(k2) * cos(p2) + normalize(k3) * cos(p3) * 0.8 + normalize(k4) * cos(p4) * 0.5
            + normalize(k5) * cos(p5) * 0.6 + normalize(k6) * cos(p6) * 0.5;
    return s / 4.4;
}

// Shallows and depths, and the foam along the shore (11-12, user pick "the colour of the shallows and the deep, foam on the shore";
// client/atmosphere/WaterDepth.java builds the map and sets the uniforms). Vanilla water had one colour and one see-through everywhere,
// so the shallows by the shore and the open sea read as the same sheet.
// OvDepthMap: one texel per block column around the camera: r = depth below the surface * 8 / 255 (blocks), g = distance to the shore
//   * 32 / 255 (blocks; 1 = farther than 8), b = surface block - OvDepthC.x + 1 (/ 255), a = (128 + share of water columns in the 33 x 33 around * 127) / 255 where the column has water, 0 elsewhere.
// OvDepthA = (map origin x, z relative to the camera, map size, 1 = on)
// OvDepthB = (depth of the e-fold clearing, alpha of the shallows, alpha of the deep, deep colour factor)
// OvDepthC = (map base y relative to the camera, clarity outside the map, time in seconds (wraps at 600), foam width in blocks)
// OvFoam   = (foam colour rgb, strength)
uniform sampler2D OvDepthMap;
uniform vec4 OvDepthA;
uniform vec4 OvDepthB;
uniform vec4 OvDepthC;
uniform vec4 OvFoam;
// OvFoamB = (share of water around where the foam starts, where it is full, -, -). Needs ov_wave.glsl imported before this file.
uniform vec4 OvFoamB;

float ovWaterHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

float ovWaterNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(ovWaterHash(i), ovWaterHash(i + vec2(1.0, 0.0)), f.x), mix(ovWaterHash(i + vec2(0.0, 1.0)), ovWaterHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// (depth, shore distance) of the map texel t; land and columns without water read as depth 0 (so the shallows grade into the shore).
// Shore distance of land is 0, except for land too small to be a shore (a trunk standing in the water, 11-13; the map marks it with
// alpha 40 / 255 and g = far), so that no ring of foam forms around it.
vec2 ovDepthTexel(ivec2 t) {
    vec4 m = texelFetch(OvDepthMap, clamp(t, ivec2(0), ivec2(int(OvDepthA.z) - 1)), 0);
    return m.a > 0.4 ? vec2(m.r * 255.0 / 8.0, m.g * 255.0 / 32.0) : vec2(0.0, m.a > 0.08 ? m.g * 255.0 / 32.0 : 0.0);
}

// color = the water colour so far, lightK = how lit the face is (vertex colour over its tint, 0..1). Up-facing water faces only.
vec4 ovWaterDepth(vec4 color, vec3 pos, vec3 n, float lightK) {
    if (OvDepthA.w <= 0.0 || n.y < 0.5) {
        return color;
    }
    pos = ovWaterPixel(pos);
    float clarity = OvDepthC.y;
    float foam = 0.0;
    vec2 uv = pos.xz - OvDepthA.xy;
    float size = OvDepthA.z;
    if (uv.x >= 0.0 && uv.y >= 0.0 && uv.x < size && uv.y < size) {
        vec4 here = texelFetch(OvDepthMap, ivec2(floor(uv)), 0);
        // The map's surface block of this column, compared with the face (the top of a source block sits 8/9 up the block).
        float surfaceTop = OvDepthC.x + here.b * 255.0 - 1.0 + 0.889;
        float same = here.a > 0.4 ? 1.0 - smoothstep(0.5, 1.0, abs(pos.y - surfaceTop)) : 0.0;
        // Bilinear between the four nearest columns.
        vec2 c = uv - 0.5;
        ivec2 i0 = ivec2(floor(c));
        vec2 f = c - floor(c);
        vec2 v = mix(mix(ovDepthTexel(i0), ovDepthTexel(i0 + ivec2(1, 0)), f.x), mix(ovDepthTexel(i0 + ivec2(0, 1)), ovDepthTexel(i0 + ivec2(1, 1)), f.x), f.y);
        float edge = smoothstep(0.0, 12.0, min(min(uv.x, uv.y), min(size - uv.x, size - uv.y)));
        float k = same * edge;
        clarity = mix(clarity, exp(-v.x / OvDepthB.x), k);
        // The foam (11-12 night, user: "it shows on the edge of every water and stands out a lot" -> "wide water only" and "only when
        // a wave comes in"): only on the shore of open water (the share of water columns around, map alpha; a sea or a big lake, not a
        // pond or a stream), and only where the crest of the swell (ov_wave.glsl, the same swell that moves the surface) reaches the
        // shore; it runs up the shore with the crest and is gone in the trough. Broken into flecks. World position from the glint's
        // wrapped camera (ov_water.glsl OvWaterSpecC).
        float open = clamp((here.a * 255.0 - 128.0) / 127.0, 0.0, 1.0);
        float openK = smoothstep(OvFoamB.x, OvFoamB.y, open);
        float crest = OvWave.w > 0.0 ? ovWaveHeight(pos) / OvWave.w : 0.0;
        float surge = smoothstep(0.1, 0.55, crest) * openK;
        vec2 w = OvWaterSpecC.zw + pos.xz;
        float t = OvDepthC.z;
        float fleck = ovWaterNoise(w * 2.3 + vec2(t * 0.12, -t * 0.09)) * 0.6 + ovWaterNoise(w * 5.1 - t * 0.2) * 0.4;
        float reach = 1.0 - smoothstep(0.0, OvDepthC.w * (0.3 + 0.9 * surge), v.y);
        // Pixel art: foam is there or not in each texel (two tones: thin and thick).
        float fm = (fleck + reach * 0.55) * reach * k * surge * step(v.y, 7.5);
        foam = fm > 0.62 ? 1.0 : (fm > 0.5 ? 0.5 : 0.0);
    }
    // Pixel art: the shallows grade into the deep in a few flat steps, like the bands of a hand-drawn texture.
    clarity = floor(clarity * 5.0 + 0.5) / 5.0;
    color.rgb = mix(color.rgb * OvDepthB.w, color.rgb * 1.08, clarity);
    color.a = mix(OvDepthB.z, OvDepthB.y, clarity);
    float fk = clamp(foam * OvFoam.w, 0.0, 1.0);
    color.rgb = mix(color.rgb, OvFoam.rgb * clamp(lightK, 0.05, 1.0), fk);
    color.a = mix(color.a, 1.0, fk * 0.9);
    return color;
}

// Rain on the water (11-12, water item 4; user pick "rain on the water: fine rings all over the surface, a little darker and rougher",
// replacing the few small ring sprites of client/atmosphere/WaterSigns). client/atmosphere/WaterGlint.java sets the uniform.
// Every block cell (two layers, the second offset by about half a block) gets drops at its own times; a hit cell grows a ring one
// 1/16-block texel wide out to half a block and lets it go (pixel art like the rest of the water), with a bright texel where it hit.
// Only where the rain reaches (sky light 14 to 15, not underground).
// OvRain = (rain strength 0..1 (0 = off; 0 in snowy or dry biomes), drops per cell per second at full rain, ring speed in blocks per
//          second, darkening of the surface at full rain)
uniform vec4 OvRain;

vec4 ovWaterRain(vec4 color, vec3 pos, vec3 n, float sky, float under) {
    if (OvRain.x <= 0.0 || n.y < 0.5) {
        return color;
    }
    float open = smoothstep(13.5 / 15.0, 14.6 / 15.0, sky) * (1.0 - clamp(under, 0.0, 1.0));
    if (open <= 0.0) {
        return color;
    }
    pos = ovWaterPixel(pos);
    vec2 w = OvWaterSpecC.zw + pos.xz;
    float t = OvWaterSpecC.y;
    float period = 1.0 / max(OvRain.y, 0.01);
    float ring = 0.0;
    float splash = 0.0;
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = w + (layer == 1 ? vec2(0.5, 0.37) : vec2(0.0));
        vec2 cell = floor(q);
        float phase = t / period + ovWaterHash(cell + float(layer) * 17.0);
        float cycle = floor(phase);
        float age = fract(phase) * period;
        // Only a share of the drops by the rain strength.
        if (ovWaterHash(cell * 1.7 + vec2(cycle * 0.31, float(layer) * 3.1)) > OvRain.x) {
            continue;
        }
        vec2 hit = cell + 0.2 + 0.6 * vec2(ovWaterHash(cell + cycle), ovWaterHash(cell.yx + cycle + 5.0));
        float r = age * OvRain.z;
        float d = length(q - hit);
        float life = 1.0 - smoothstep(0.25, 0.5, r);
        ring = max(ring, step(abs(d - r), 0.5 / 16.0 + 0.001) * life);
        splash = max(splash, step(d, 0.75 / 16.0) * (1.0 - step(0.08, age)));
    }
    color.rgb *= 1.0 - OvRain.w * OvRain.x * open;
    color.rgb = mix(color.rgb, color.rgb * 1.45 + 0.05, ring * 0.65 * open);
    color.rgb = mix(color.rgb, vec3(0.85, 0.9, 0.95) * max(max(color.r, color.g), color.b) * 1.6, splash * 0.7 * open);
    return color;
}

// The sky and the clouds mirrored on open water (11-12, user pick "the sky and the clouds in the water"; client/atmosphere/WaterGlint.java
// sets the uniforms). Vanilla water is a flat sheet of its biome colour: seen at a slant a real lake mirrors the sky, and that is what
// made it look out of place under the voxel clouds. The view is mirrored on the (slightly wavy) surface: the mirrored ray takes the
// sky colour (horizon to zenith) and, where it meets the cloud layer, the cloud shadow map (ov_cloud.glsl, so this file is imported
// after it) gives the clouds' outline. A Fresnel term: looking down you see into the water, at a slant mostly the sky.
// Only where the face is open to the sky and not underground; under the canopy (Sodium's sun map) it fades.
//
// OvWaterSky  = (zenith colour rgb, strength 0..1; 0 = off)
// OvWaterSkyB = (horizon colour rgb, cloud strength 0..1)
// OvWaterSkyC = (colour of the cloud undersides rgb, -)
uniform vec4 OvWaterSky;
uniform vec4 OvWaterSkyB;
uniform vec4 OvWaterSkyC;

const float OV_REFLECT_CLOUD_LOW = 0.35;

vec4 ovWaterReflect(vec4 color, vec3 pos, vec3 n, float sky, float under, float shade) {
    if (OvWaterSky.w <= 0.0 || n.y < 0.5) {
        return color;
    }
    float open = smoothstep(0.6, 0.95, sky) * (1.0 - clamp(under, 0.0, 1.0)) * (1.0 - 0.7 * clamp(shade, 0.0, 1.0));
    if (open <= 0.0) {
        return color;
    }
    pos = ovWaterPixel(pos);
    float dist = length(pos);
    vec3 v = pos / max(dist, 1.0e-4);
    // Half the glint's slope: the mirrored sky wobbles gently, the clouds do not break into noise.
    vec2 s = ovWaterSlope(pos) * OvWaterSpecC.x * 0.5;
    vec3 wn = normalize(vec3(-s.x, 1.0, -s.y));
    vec3 r = reflect(v, wn);
    r.y = max(r.y, 0.0);
    vec3 skyCol = mix(OvWaterSkyB.rgb, OvWaterSky.rgb, smoothstep(0.0, 0.55, r.y));
    if (OvWaterSkyB.w > 0.0 && OvCloudA.w > 0.0 && r.y > 0.03) {
        // The mirrored clouds are pulled down to OV_REFLECT_CLOUD_LOW of their height (a painter's cheat): at their true height the
        // mirrored ray of all but the nearest water reaches them thousands of blocks away, beyond the shadow map, and none showed.
        float t = (OvCloudA.z * OV_REFLECT_CLOUD_LOW - pos.y) / r.y;
        vec2 q = pos.xz + r.xz * t - OvCloudB.xy;
        if (t > 0.0 && q.x > 0.0 && q.y > 0.0 && q.x < OvCloudB.z && q.y < OvCloudB.z) {
            float edge = smoothstep(0.0, OvCloudB.w * 2.0, min(min(q.x, q.y), min(OvCloudB.z - q.x, OvCloudB.z - q.y)));
            float cloud = smoothstep(0.2, 0.6, texture(OvCloudMap, q / OvCloudB.z).r) * edge * (1.0 - smoothstep(900.0, 1800.0, t));
            // Pixel art: a mirrored cloud is there or not (a soft edge of one texel's worth only).
            cloud = cloud > 0.5 ? 1.0 : (cloud > 0.25 ? 0.5 : 0.0);
            skyCol = mix(skyCol, OvWaterSkyC.rgb, cloud * OvWaterSkyB.w);
        }
    }
    // Schlick-like Fresnel with a floor (a little of the sky shows even looking straight down). Softer than real water (exponent 5,
    // floor 0.02): at the usual view down onto a lake the real one left the water a flat dark sheet.
    float c = clamp(dot(wn, -v), 0.0, 1.0);
    float fresnel = mix(0.16, 1.0, pow(1.0 - c, 2.5));
    float k = clamp(fresnel * OvWaterSky.w * open, 0.0, 1.0);
    color.rgb = mix(color.rgb, skyCol, k);
    color.a = mix(color.a, 1.0, k * 0.7);
    return color;
}

// The highlight to add to a water face (before the fog). n = face normal turned toward the camera, sky = vertex sky light 0..1,
// under = how much the fragment is underground (ov_cave.glsl), shade = 1 where the canopy map knows the face is in shade (Sodium only;
// 0 elsewhere). Returns (colour rgb, extra opacity).
vec4 ovWaterGlint(vec3 pos, vec3 n, float sky, float under, float shade) {
    if (OvWaterSpec.w <= 0.0 || n.y < 0.5) {
        return vec4(0.0);
    }
    // Sky light 15 only: Sodium reads level 14 as 14.5 / 15 (its 8..248 light coordinate over 240), so the edge sits above that.
    float open = smoothstep(14.6 / 15.0, 15.0 / 15.0, sky) * (1.0 - clamp(under, 0.0, 1.0)) * (1.0 - clamp(shade, 0.0, 1.0));
    if (open <= 0.0) {
        return vec4(0.0);
    }
    pos = ovWaterPixel(pos);
    float dist = length(pos);
    vec3 v = -pos / max(dist, 1.0e-4);
    vec2 s = ovWaterSlope(pos) * OvWaterSpecC.x;
    vec3 wn = normalize(vec3(-s.x, 1.0, -s.y));
    vec3 h = normalize(OvWaterSpec.xyz + v);
    float spec = pow(max(dot(wn, h), 0.0), OvWaterSpecB.w);
    // More of the light is mirrored at grazing angles (a mild Fresnel; looking straight down still shows the sun, a little dimmer).
    float fresnel = mix(0.35, 1.0, pow(1.0 - clamp(dot(wn, v), 0.0, 1.0), 3.0));
    float k = spec * fresnel * OvWaterSpec.w * open;
    // Pixel art: the sparkles come in three flat tones.
    k = k > 0.6 ? 1.0 : (k > 0.25 ? 0.5 : (k > 0.08 ? 0.2 : 0.0));
    return vec4(OvWaterSpecB.rgb * k, clamp(k, 0.0, 1.0));
}

// The glowing tide (11-16, world event "glowing tide"; user: at night the sea, the rivers and the lakes glow blue-green, and light
// runs along the shore and where something swam). client/worldevent/GlowingTide.java sets the uniforms. Setting: tiny living things
// that fed on the spores light up when the water moves. Up-facing water faces only, not underground. Pixel art like the rest of the
// water: every 1/16-block texel takes one of a few flat levels.
// OvTide  = (colour rgb, strength (0 = off))
// OvTideB = (shore glow, sparks, time in seconds (wraps at 600), wake glow)
// OvTideP0..5 = (x, z relative to the camera, radius in blocks, strength 0..1): where something moved through the water just now.
uniform vec4 OvTide;
uniform vec4 OvTideB;
uniform vec4 OvTideP0;
uniform vec4 OvTideP1;
uniform vec4 OvTideP2;
uniform vec4 OvTideP3;
uniform vec4 OvTideP4;
uniform vec4 OvTideP5;

float ovTideWake(vec2 p, vec4 w) {
    vec2 d = p - w.xy;
    return w.w * exp(-dot(d, d) / max(w.z * w.z, 0.01));
}

// Returns (light to add rgb, extra opacity).
vec4 ovWaterTide(vec3 pos, vec3 n, float under) {
    if (OvTide.w <= 0.0 || n.y < 0.5) {
        return vec4(0.0);
    }
    float open = 1.0 - clamp(under, 0.0, 1.0);
    if (open <= 0.0) {
        return vec4(0.0);
    }
    pos = ovWaterPixel(pos);
    vec2 w = OvWaterSpecC.zw + pos.xz;
    float t = OvTideB.z;
    // Calm water: a faint glow in slow drifting patches.
    float glow = 0.07 + 0.16 * ovWaterNoise(w * 0.09 + vec2(t * 0.02, -t * 0.015)) * ovWaterNoise(w * 0.31 - vec2(t * 0.03, t * 0.01) + 7.0);
    // Sparks: single texels flare up and die, each at its own time.
    vec2 cell = floor(w * 16.0);
    float ph = t * 0.6 + ovWaterHash(cell * 0.37) * 40.0;
    float spark = step(0.992, ovWaterHash(cell + floor(ph) * 3.1)) * (1.0 - fract(ph));
    glow += spark * OvTideB.y;
    // The shore: light runs in towards the land, wave after wave (the shore distance of the depth map).
    vec2 uv = pos.xz - OvDepthA.xy;
    float size = OvDepthA.z;
    if (OvDepthA.w > 0.0 && uv.x >= 0.0 && uv.y >= 0.0 && uv.x < size && uv.y < size) {
        vec4 here = texelFetch(OvDepthMap, ivec2(floor(uv)), 0);
        if (here.a > 0.4) {
            float dist = here.g * 255.0 / 32.0;
            float nearK = 1.0 - smoothstep(0.4, 4.5, dist);
            float run = pow(0.5 + 0.5 * sin(dist * 2.4 + t * 1.7 + ovWaterNoise(w * 0.2) * 3.0), 3.0);
            float fleck = 0.55 + 0.45 * ovWaterNoise(w * 3.1 + vec2(t * 0.3, 0.0));
            glow += nearK * (0.25 + 0.75 * run) * fleck * OvTideB.x;
        }
    }
    // Where something moved through the water.
    float wake = ovTideWake(pos.xz, OvTideP0) + ovTideWake(pos.xz, OvTideP1) + ovTideWake(pos.xz, OvTideP2)
            + ovTideWake(pos.xz, OvTideP3) + ovTideWake(pos.xz, OvTideP4) + ovTideWake(pos.xz, OvTideP5);
    glow += min(wake, 1.5) * (0.6 + 0.4 * ovWaterNoise(w * 4.3 - t * 0.4)) * OvTideB.w;
    // Flat levels.
    float k = floor(clamp(glow, 0.0, 1.6) * 6.0 + 0.5) / 6.0;
    k *= OvTide.w * open;
    return vec4(OvTide.rgb * k, clamp(k * 0.6, 0.0, 1.0));
}
