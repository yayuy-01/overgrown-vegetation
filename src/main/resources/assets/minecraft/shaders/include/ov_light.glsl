#version 150

// Overgrown Vegetation: canopy shade and sunflecks on every surface (wind plan C; client/CanopySunMap.java holds the
// explanation). Keep this file ASCII-only.
//
// The CPU traces the sun direction through the world and writes a small "sun-space" map: every texel is one line along
// the sun direction (all points on it share u = x - y * sun.x / sun.y and v = z - y * sun.z / sun.y).
//   r = top of the highest solid block crossed by the line (blocks above OvMapA.w, 0 = none)
//   g = top of the highest leaf-like block crossed by the line (same units, 0 = none)
//   b = number of leaf-like blocks crossed (x 17, up to 15)
//   a = 1 traced in an overgrown area, 0.5 traced outside (never shaded), 0 not traced yet
// A fragment is in shade when a solid block of its line is more than 1.5 blocks above it (less makes stripes on the sides
// of a trunk: lines that just graze the trunk), a leaf more than one block above it, or (solid and cutout_mipped layers)
// when it faces away from the sun.
// Under thin foliage (1 to 3 leaves, no solid block) small sunflecks break through on everything but the top of the
// ground (the ground has its own spots drawn by client/CanopyDapple.java). The fleck pattern lives in sun space, so a
// fleck lands on a fern frond and a trunk behind it along the same beam, and is stretched along the sun on slopes.
//
// OvSunA     = (sun.x / sun.y, sun.z / sun.y, shade strength; 0 = map off, how much the map decides sun or shade (daylight))
// OvSunDir   = (sun direction, strength of the facing-away shade)
// OvMapA     = (map origin u, map origin v (both relative to the camera), map size in texels, map base y relative to the camera)
// OvShadeColor = grey brightness multiplier of full shade (rgb all the same)
// OvTint     = (blue-green hue multiplier with brightness 1, forest-wide strength). The hue goes on everything out of the sun:
//              where the map knows sun and shade (day, inside the map, overgrown lines) only on the shade, elsewhere (outside the
//              map, low sun, night) on every face while the camera is in the overgrown forest. Faces lit by torches keep their
//              amber light (block light above OV_TORCH_FROM).
// OvFleck    = (camera u and v times 1.7 wrapped to 64 plus the wind shift of the flecks, time in seconds, fleck strength;
//              0 = off). The pattern repeats every 64 / 1.7 blocks; the wrap keeps it fixed in the world while the camera moves.
uniform sampler2D OvSunMap;
uniform vec4 OvSunA;
uniform vec4 OvSunDir;
uniform vec4 OvMapA;
uniform vec4 OvShadeColor;
uniform vec4 OvTint;
uniform vec4 OvFleck;
// OvCave     = (teal added to dark faces underground, as a multiple of the face colour; strength, 0 = off). See client/CaveLight.java.
uniform vec4 OvCave;

// How much ovCanopyLight found this fragment in canopy shade (0 to 1); the cloud shadow (ov_cloud.glsl) does not shade it again.
float ovCanopyShade = 0.0;

// Block light (0 to 1) from which a face counts as torch-lit and keeps its warm colour (fully at OV_TORCH_FULL).
const float OV_TORCH_FROM = 0.45;
const float OV_TORCH_FULL = 0.9;

float ovShadowAt(ivec2 cell, float h) {
    vec4 m = texelFetch(OvSunMap, cell, 0);
    if (m.a < 0.75) {
        return 0.0;
    }
    float solidTop = m.r * 255.0;
    float leafTop = m.g * 255.0;
    if (solidTop > 0.5 && h < solidTop - 1.5) {
        return 1.0;
    }
    if (leafTop > 0.5 && m.b > 0.01 && h < leafTop - 1.0) {
        return 1.0;
    }
    return 0.0;
}

float ovHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float ovNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = ovHash(i);
    float b = ovHash(i + vec2(1.0, 0.0));
    float c = ovHash(i + vec2(0.0, 1.0));
    float d = ovHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Underground in the overgrown area, faces lit by neither block light nor sky light get a little teal light (the dark is kept,
// only lifted). albedo = texture colour x vertex tint, without the lightmap.
vec3 ovCaveFloor(vec3 color, vec3 albedo, vec2 light) {
    if (OvCave.w <= 0.0) {
        return color;
    }
    float dark = 1.0 - smoothstep(0.0, 0.4, max(light.x, light.y));
    return color + albedo * OvCave.rgb * (OvCave.w * dark);
}

// Colour of a terrain fragment at camera-relative position pos with world normal n. facing > 0 also darkens faces that
// look away from the sun (not for the cutout layer: grass and ferns are thin and lit through). light = (block light,
// sky light) from 0 to 1.
vec3 ovCanopyLight(vec3 color, vec3 pos, vec3 n, float facing, vec2 light) {
    if (OvSunA.z <= 0.0 && OvTint.w <= 0.0) {
        return color;
    }
    float shade = 0.0;
    float fleck = 0.0;
    // How much the map knows whether this fragment is in the sun (0 = unknown: outside the map, map off), and whether the
    // map traced it outside the overgrown area (no hue there).
    float known = 0.0;
    float outside = 0.0;
    vec2 uvRel = vec2(pos.x - pos.y * OvSunA.x, pos.z - pos.y * OvSunA.y);
    vec2 uv = uvRel - OvMapA.xy - 0.5;
    float size = OvMapA.z;
    // Never read the map while it is off (its sampler slot may hold another texture then).
    if (OvSunA.z > 0.0 && uv.x >= 0.0 && uv.y >= 0.0 && uv.x < size - 1.0 && uv.y < size - 1.0) {
        ivec2 c = ivec2(floor(uv));
        vec2 f = uv - floor(uv);
        float h = pos.y - OvMapA.w;
        float s00 = ovShadowAt(c, h);
        float s10 = ovShadowAt(c + ivec2(1, 0), h);
        float s01 = ovShadowAt(c + ivec2(0, 1), h);
        float s11 = ovShadowAt(c + ivec2(1, 1), h);
        shade = mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
        // Fade out over the last 6 texels of the map (no hard edge where the map ends).
        float edge = smoothstep(0.0, 6.0, min(min(uv.x, uv.y), min(size - 1.0 - uv.x, size - 1.0 - uv.y)));
        shade *= edge;
        vec4 m = texelFetch(OvSunMap, ivec2(floor(uv + 0.5)), 0);
        bool inside = m.a > 0.75;
        known = inside ? edge * OvSunA.w : 0.0;
        outside = (m.a > 0.25 && m.a < 0.75) ? edge : 0.0;
        // Sunflecks under thin foliage (1 to 3 leaves, nothing solid above), not on the top of the ground.
        float leaves = m.b * 15.0;
        bool underLeaves = m.g * 255.0 > 0.5 && h < m.g * 255.0 - 1.0;
        bool underSolid = m.r * 255.0 > 0.5 && h < m.r * 255.0 - 1.5;
        if (OvFleck.w > 0.0 && inside && underLeaves && !underSolid && leaves > 0.5 && leaves < 3.5 && n.y < 0.7) {
            vec2 p = uvRel * 1.7 + OvFleck.xy;
            float noise = ovNoise(p) * 0.7 + ovNoise(p * 2.0 + 17.0) * 0.3;
            float threshold = leaves < 1.5 ? 0.6 : (leaves < 2.5 ? 0.68 : 0.75);
            float spot = smoothstep(threshold, threshold + 0.06, noise);
            // Each fleck flickers on its own (leaves fluttering in front of it).
            float flicker = 0.7 + 0.3 * sin(OvFleck.z * 4.0 + ovHash(floor(p)) * 6.2832);
            float bright = leaves < 1.5 ? 1.0 : (leaves < 2.5 ? 0.8 : 0.55);
            // Solid faces and leaves only catch a fleck when they face the sun (a wall under a noon sun only gets grazing light);
            // grass and fronds are thin and glow through whatever their angle.
            float incidence = facing > 0.0 ? smoothstep(0.05, 0.45, dot(n, OvSunDir.xyz)) : 1.0;
            fleck = spot * bright * flicker * incidence * OvFleck.w * edge;
            shade *= 1.0 - fleck;
        }
        // Faces looking away from the sun are in their own shade, but only inside the traced overgrown area.
        if (facing > 0.0 && inside) {
            float nl = dot(n, OvSunDir.xyz);
            shade = max(shade, facing * OvSunDir.w * (1.0 - smoothstep(-0.25, -0.02, nl)) * edge);
        }
    }
    ovCanopyShade = clamp(shade, 0.0, 1.0);
    float shadeAmount = OvSunA.z > 0.0 ? shade * OvSunA.z : 0.0;
    // Hue: on the shade where the map knows the sun, on everything where it does not (while in the forest); never where the
    // map traced outside the overgrown area, and less on torch-lit faces.
    float notLit = mix(1.0, shade, known);
    float hue = max(OvTint.w * notLit * (1.0 - outside), shadeAmount);
    hue *= 1.0 - smoothstep(OV_TORCH_FROM, OV_TORCH_FULL, light.x);
    vec3 lit = color * mix(vec3(1.0), OvShadeColor.rgb, shadeAmount) * mix(vec3(1.0), OvTint.rgb, hue);
    // A fleck is warm sunlight: a little brighter and golden (grass and fronds glow yellow-green where it lands).
    return lit * (1.0 + fleck * max(OvSunA.z, 0.0) * vec3(0.3, 0.26, 0.08));
}
