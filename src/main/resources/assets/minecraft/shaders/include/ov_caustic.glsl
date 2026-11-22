#version 150

// Overgrown Vegetation: the moving net of sunlight on the bottom of shallow water (caustics; 10-31 night,
// client/atmosphere/WaterCaustics.java holds the explanation). Keep this file ASCII-only. Include after ov_light.glsl
// (reads ovNoise, ovCanopyShade and OvSunA).
//
// The ruffled water surface focuses the sun into bright curved lines on whatever lies under it. One terrain pass serves both
// views: the bottom seen from above through the translucent water, and the bottom seen from below while diving. The water
// itself (translucent pass) gets nothing.
//
// OvWaterMap: one texel per block column around the camera (origin and size in OvCausticMap):
//   r = top water block of the column + 1, counted from OvCausticMap.w (0 = no water open to the sky in this column)
//   g = height of the water in that block (0..1)
//   b = 1 nothing above the water, 0.5 leaves above it
// OvCaustic    = (camera x and z wrapped to 1024 blocks, time in seconds (wraps at 600), strength; 0 = off)
// OvCausticB   = (direction to the sun under water (refracted): x / y, z / y, depth of the e-fold fade, depth where it is gone)
// OvCausticMap = (map origin x and z relative to the camera, map size in texels, map base y relative to the camera)
// OvCausticC   = (light colour rgb, cells per block; a multiple of 0.25 so the 1024 wrap stays seamless)
// OvCausticD   = (strength under leaves while the canopy map is off, line width, cycles per 600 seconds, -)
// (10-31 night: Snell's window and the net on the underside of the surface were tried in the water shader and dropped by
// the user.)
uniform sampler2D OvWaterMap;
uniform vec4 OvCaustic;
uniform vec4 OvCausticB;
uniform vec4 OvCausticMap;
uniform vec4 OvCausticC;
uniform vec4 OvCausticD;

// Strength of the broad shimmer (11-13, provisional): brightness swings by about plus or minus half of this.
const float OV_SHIMMER = 0.5;

vec2 ovCausticHash2(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

// Distance from the nearest to the second nearest of the moving cell points: 0 on the borders between cells, which become
// the bright lines.
float ovCausticEdge(vec2 p, float phase) {
    vec2 i = floor(p);
    vec2 f = p - i;
    float d1 = 8.0;
    float d2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(float(x), float(y));
            vec2 h = ovCausticHash2(i + g);
            vec2 r = g + 0.5 + 0.42 * sin(phase + 6.2831853 * h) - f;
            float d = dot(r, r);
            if (d < d1) {
                d2 = d1;
                d1 = d;
            } else if (d < d2) {
                d2 = d;
            }
        }
    }
    return sqrt(d2) - sqrt(d1);
}

// Brightness of the net (0 = dark cell, about 1 = line) at world position (x, z) seen at the given depth. Deeper the lines
// are wider and softer and the contrast fades (the focus of the ripples lies near the surface).
float ovCausticPattern(vec2 world, float depth) {
    float s = OvCausticC.w;
    float t = OvCaustic.z * 6.2831853 / 600.0 * OvCausticD.z;
    // Two layers drifting in small circles in different directions (circles keep the 600 s loop seamless); smooth noise bends
    // the straight cell borders into curves.
    vec2 p1 = world * s + vec2(sin(t), cos(t)) * 0.6;
    vec2 p2 = world * (s * 1.5) + vec2(cos(t * 2.0), -sin(t * 2.0)) * 0.6;
    p1 += (vec2(ovNoise(p1 * 0.5), ovNoise(p1 * 0.5 + 23.0)) - 0.5) * 0.8;
    p2 += (vec2(ovNoise(p2 * 0.5 + 41.0), ovNoise(p2 * 0.5 + 7.0)) - 0.5) * 0.8;
    float blur = clamp(depth / max(OvCausticB.w, 0.1), 0.0, 1.0);
    float width = OvCausticD.y * (1.0 + 3.0 * blur);
    float e1 = 1.0 - smoothstep(0.0, width, ovCausticEdge(p1, t * 3.0));
    float e2 = 1.0 - smoothstep(0.0, width * 1.2, ovCausticEdge(p2, 1.7 - t * 4.0));
    float c = e1 * 0.7 + e2 * 0.5;
    c *= c;
    return mix(c, 0.3, blur * 0.6);
}

// Adds the caustic light to a terrain fragment (call after ovCanopyLight). pos is camera-relative, n the world normal,
// light = (block light, sky light) from 0 to 1.
vec3 ovCaustics(vec3 color, vec3 pos, vec3 n, vec2 light) {
    if (OvCaustic.w <= 0.0) {
        return color;
    }
    // Read the column on the water side of the face: a bank wall facing into a pond belongs to the pond's column.
    vec2 cell = floor(pos.xz + n.xz * 0.3 - OvCausticMap.xy);
    if (cell.x < 0.0 || cell.y < 0.0 || cell.x >= OvCausticMap.z || cell.y >= OvCausticMap.z) {
        return color;
    }
    vec4 m = texelFetch(OvWaterMap, ivec2(cell), 0);
    if (m.r <= 0.0) {
        return color;
    }
    float surface = OvCausticMap.w + m.r * 255.0 - 1.0 + m.g;
    float depth = surface - pos.y;
    if (depth <= 0.02 || depth >= OvCausticB.w) {
        return color;
    }
    vec3 toSun = normalize(vec3(OvCausticB.x, 1.0, OvCausticB.y));
    float incidence = smoothstep(0.0, 0.4, dot(n, toSun));
    // Under an overhang or deep in a hole the sky light is low: no sun reaches there.
    float sky = smoothstep(0.35, 0.7, light.y);
    // Sun on the water: the canopy map knows where leaves shade it; without the map, leaves above the column dim it.
    float sun = (1.0 - ovCanopyShade) * (OvSunA.z > 0.0 ? 1.0 : mix(OvCausticD.x, 1.0, step(0.75, m.b)));
    float fade = exp(-depth / max(OvCausticB.z, 0.05)) * smoothstep(0.02, 0.35, depth)
            * (1.0 - smoothstep(OvCausticB.w * 0.7, OvCausticB.w, depth));
    float amount = OvCaustic.w * incidence * sky * sun * fade;
    if (amount <= 0.002) {
        return color;
    }
    // Pixel art (11-12, user: "too smooth for Minecraft; can it be dot art?"): the point is snapped to the 1/16-block texel grid of
    // the face it lies on (pushed a hair into the face so a face on a block boundary snaps steadily), and the net comes out in three
    // flat tones. The snapped depth makes the net shift in texel steps too.
    vec3 inFace = pos - n * 0.002;
    vec2 worldXZ = (floor((OvCaustic.xy + inFace.xz) * 16.0) + 0.5) / 16.0;
    float baseRel = inFace.y - OvCausticMap.w;
    float depthPx = surface - (OvCausticMap.w + (floor(baseRel * 16.0) + 0.5) / 16.0);
    // The net lies where the refracted sun ray through this point crossed the surface (it shifts with depth and sun angle).
    vec2 world = worldXZ + OvCausticB.xy * depthPx;
    // The ripples only move the light around: the lines get much brighter and the cells between them a little darker
    // (the light gathered into a line is missing from its cell). Without the darker cells the net reads as faint speckles.
    float c = ovCausticPattern(world, depthPx);
    c = c > 0.5 ? 1.0 : (c > 0.2 ? 0.45 : 0.0);
    // The broad shimmer (11-13, water item 6; user pick "the whole underwater flickers with light"): the light and shade of the swell
    // on the surface drift slowly over everything below, much larger than the net (several blocks), in four flat tones (pixel art).
    float ts = OvCaustic.z;
    vec2 sp = world * 0.16;
    float sh = ovNoise(sp + vec2(ts * 0.21, ts * 0.13)) * 0.6 + ovNoise(sp * 2.3 + vec2(-ts * 0.17, ts * 0.25) + 31.0) * 0.4;
    sh = floor(sh * 4.0) / 3.0 - 0.5;
    float shimmer = sh * OV_SHIMMER * amount / max(OvCaustic.w, 0.001);
    return color * (1.0 - amount * 0.3 + amount * 1.6 * c * OvCausticC.rgb) * (1.0 + shimmer);
}
