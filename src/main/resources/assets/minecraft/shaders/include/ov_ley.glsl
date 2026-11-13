// Overgrown Vegetation: the veins of the ley on cave rock (11-14, shader polish F; client/atmosphere/LeyVeins.java holds the
// explanation). Keep this file ASCII-only.
//
// Near a ley line the rock of a cave shows thin glowing veins (user: "faint light-blue veins of the ley on the rock face"): light blue
// in the plain world, the colour of the ley roots in the polluted land, blending smoothly where the two meet. Solid rock only
// (grey texels), underground only. The veins are fixed in the world, one decision per texel of the block's texture.
//
// OvLeyMap = around the camera, one texel per 4 blocks: r = distance to the nearest ley line (x 63.75 blocks), g = polluted (0..1).
// OvLey    = (map origin x, z relative to the camera, map size in blocks, strength; 0 = off)
// OvLeyB   = (camera position mod 256, time in seconds)
// OvLeyC   = (colour in the plain world, distance from the line where the veins end)
// OvLeyD   = (colour in the polluted land, -)
uniform sampler2D OvLeyMap;
uniform vec4 OvLey;
uniform vec4 OvLeyB;
uniform vec4 OvLeyC;
uniform vec4 OvLeyD;

float ovLeyHash(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.x + p.y) * p.z);
}

// Value noise with a lattice of `cells` per block, periodic in the 256-block wrap of OvLeyB.
float ovLeyNoise(vec3 w, float cells) {
    float period = 256.0 * cells;
    vec3 p = w * cells;
    vec3 i = floor(p);
    vec3 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    vec3 a = mod(i, period);
    vec3 b = mod(i + 1.0, period);
    return mix(mix(mix(ovLeyHash(vec3(a.x, a.y, a.z)), ovLeyHash(vec3(b.x, a.y, a.z)), f.x),
                   mix(ovLeyHash(vec3(a.x, b.y, a.z)), ovLeyHash(vec3(b.x, b.y, a.z)), f.x), f.y),
               mix(mix(ovLeyHash(vec3(a.x, a.y, b.z)), ovLeyHash(vec3(b.x, a.y, b.z)), f.x),
                   mix(ovLeyHash(vec3(a.x, b.y, b.z)), ovLeyHash(vec3(b.x, b.y, b.z)), f.x), f.y), f.z);
}

// albedo = the texel without light, pos = camera-relative position, n = face normal, under = how much the face is underground.
vec3 ovLeyVeins(vec3 color, vec3 albedo, vec3 pos, vec3 n, float under) {
    if (OvLey.w <= 0.0 || under <= 0.0) {
        return color;
    }
    vec2 m = (pos.xz - OvLey.xy) / OvLey.z;
    if (m.x <= 0.0 || m.y <= 0.0 || m.x >= 1.0 || m.y >= 1.0) {
        return color;
    }
    vec4 t = texture(OvLeyMap, m);
    float near = 1.0 - smoothstep(OvLeyC.w * 0.3, OvLeyC.w, t.r * 63.75);
    if (near <= 0.0) {
        return color;
    }
    // Rock: grey texels (stone, deepslate, tuff...), not dirt, wood or moss.
    float chroma = max(albedo.r, max(albedo.g, albedo.b)) - min(albedo.r, min(albedo.g, albedo.b));
    float rock = 1.0 - smoothstep(0.07, 0.18, chroma);
    if (rock <= 0.0) {
        return color;
    }
    vec3 w = (floor((pos - n * 0.01 + OvLeyB.xyz) * 16.0) + 0.5) / 16.0;
    // The veins run where two slow noises cross (thin lines; a single noise near its middle value made wide blobs), wobbled by a finer
    // one; nearer the ley line they are a little wider.
    float ridge = abs(ovLeyNoise(w, 1.0) - ovLeyNoise(w + 97.25, 1.0) + 0.2 * (ovLeyNoise(w + 31.5, 2.0) - 0.5));
    float width = mix(0.008, 0.020, near);
    float vein = 1.0 - smoothstep(width * 0.5, width, ridge);
    if (vein <= 0.0) {
        return color;
    }
    // A slow pulse runs along the veins.
    float pulse = 0.75 + 0.25 * sin(OvLeyB.w * 1.2 + (w.x + w.y + w.z) * 0.8);
    vec3 tint = mix(OvLeyC.rgb, OvLeyD.rgb, smoothstep(0.15, 0.85, t.g));
    return color + tint * vein * near * rock * pulse * OvLey.w * under;
}
