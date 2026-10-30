#version 150

// Overgrown Vegetation: colour variation and young leaves of the overgrown leaves (client/atmosphere/LeafTones.java holds the
// explanation). Keep this file ASCII-only. All colours stay close to the drawn leaf (user 10-17: young leaves are
// yellow-green, grown leaves only slightly more blue-green).
//
// LeafTones writes a code into the tint ratios: R/G = 1 - 0.2 j, B/G = 1 - 0.2 k (G is always the largest channel; face
// shade and ambient occlusion scale all three channels alike, so the ratios survive).
// j = 0: mature leaf, k = tone (0 as drawn, 1 slightly blue-green, 2 a little more blue-green).
// j = 1..3: young (flush) leaf, yellow-green, with amount 0.45 / 0.75 / 1.0.
// Only vertices that carry the sway marker (code 1..63 in the alpha, client/atmosphere/SwayModels.java) are ours; others keep their tint.
//
// OvLeafTone = (tone strength, young strength, -, -)
uniform vec4 OvLeafTone;

// Vertex stage. Returns (tone, young amount, young colour, 1 when decoded) and replaces the tint by the plain shade.
vec4 ovLeafDecode(vec4 color, inout vec3 tint) {
    float mark = floor((1.0 - color.a) * 255.0 + 0.5);
    if (mark < 1.0 || mark > 63.0 || color.g < 0.004) {
        return vec4(0.0);
    }
    float j = clamp(floor((1.0 - color.r / color.g) / 0.2 + 0.5), 0.0, 3.0);
    float k = clamp(floor((1.0 - color.b / color.g) / 0.2 + 0.5), 0.0, 2.0);
    tint = vec3(color.g);
    if (j > 0.5) {
        float amount = j < 1.5 ? 0.45 : (j < 2.5 ? 0.75 : 1.0);
        return vec4(0.0, amount, 0.0, 1.0);
    }
    return vec4(0.5 * k, 0.0, 0.0, 1.0);
}

// Fragment stage. Repaints the texel towards the coded colour, keeping its brightness pattern (relative to the mean
// luminance 0.19 of overgrown_leaves.png). Brightness stays within about 0.95x..1.15x of the drawn leaf (leaves never glow).
vec3 ovLeafRecolor(vec3 t, vec4 leaf) {
    if (leaf.w < 0.5) {
        return t;
    }
    float tone = leaf.x * OvLeafTone.x;
    float young = leaf.y * OvLeafTone.y;
    if (tone < 0.001 && young < 0.001) {
        return t;
    }
    float rel = dot(t, vec3(0.2126, 0.7152, 0.0722)) / 0.19;
    // drawn leaf: mean (0.116, 0.228, 0.023)
    t = mix(t, vec3(0.090, 0.222, 0.062) * rel, tone);
    if (young > 0.001) {
        t = mix(t, vec3(0.160, 0.262, 0.028) * rel, young);
    }
    return t;
}
