// Overgrown Vegetation: the sparkle of ore (11-13, shader polish E; client/atmosphere/OreSparkle.java holds the explanation and
// builds the mask). Keep this file ASCII-only.
//
// In a dark cave, when a point light or a light in someone's hand is near, single pixels of ore glint for a moment: a hint for the
// one who is looking, and a small reward. Only the grains of ore (not the rock around them), only where such a light reaches.
//
// OvOreMask = a texture laid out like the block atlas: 1 on the texels of ore sprites that are grains of ore, 0 elsewhere.
// OvOre     = (strength; 0 = off, time in seconds (wrapped), atlas width, atlas height)
// OvOreB    = (camera position mod 64 (so the glints stay fixed in the world), share of the grains glinting at any moment)
uniform sampler2D OvOreMask;
uniform vec4 OvOre;
uniform vec4 OvOreB;

float ovOreHash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

// uv = block atlas coordinates, pos = camera-relative position, n = face normal, light = the point light on the fragment
// (ovCaveLightNow of ov_cave.glsl).
vec3 ovOreSparkle(vec3 color, vec2 uv, vec3 pos, vec3 n, vec3 light) {
    if (OvOre.x <= 0.0) {
        return color;
    }
    float l = max(light.r, max(light.g, light.b));
    if (l <= 0.02) {
        return color;
    }
    if (texelFetch(OvOreMask, ivec2(uv * OvOre.zw), 0).r < 0.5) {
        return color;
    }
    // One decision per texel of the block's texture (16 per block), fixed in the world.
    vec3 w = floor((pos - n * 0.01 + OvOreB.xyz) * 16.0);
    float h = ovOreHash(w);
    float h2 = ovOreHash(w + 17.0);
    // A grain glints when its own facet turns toward the eye: the phase moves with the view direction (walking past, turning the
    // head) and slowly with time (a flame flickers).
    // 11-14 (user photo: the glints showed as slanted shards): the view direction of the texel centre, so a whole texel glints or not.
    vec3 view = normalize((w + 0.5) / 16.0 - OvOreB.xyz);
    float phase = h * 6.2832 + dot(view, vec3(h2 - 0.5, h - 0.5, 0.37)) * 60.0 + OvOre.y * (0.6 + h2);
    // 11-14 (too bright next to a torch): a triangle wave, even over 0..1, so that "share" is the real share of grains glinting
    // (the sine spent more time near its top: 8% lit about 18%).
    float s = 1.0 - abs(2.0 * fract(phase / 6.2832) - 1.0);
    float share = clamp(OvOreB.w, 0.01, 1.0);
    float glint = smoothstep(1.0 - share, 1.0 - share * 0.4, s);
    // The colour of the light, pushed toward white; stronger light, stronger glint (full from a modest light on).
    vec3 tint = mix(light / max(l, 0.001), vec3(1.0), 0.6);
    // 11-14: less on a face that is already bright (an ore wall next to a torch washed out to white with the bloom on top).
    float room = 1.0 - smoothstep(0.45, 0.95, max(color.r, max(color.g, color.b)));
    return color + tint * glint * OvOre.x * min(l * 2.5, 1.0) * (0.25 + 0.75 * room);
}
