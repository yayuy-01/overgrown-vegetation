#version 150

// Overgrown Vegetation: the spore aurora (client/worldevent/WorldEventSky.java holds the explanation). Keep this file ASCII-only.
// A quad over the whole screen, added on top of the night sky (after the stars, before the clouds).
// Every pixel walks its view ray through a high layer (Layer.x .. Layer.y, heights above the camera) in 12 steps and sums the curtains.
// Curtains = thin lines along the contours of a large noise, pinned to world coordinates (Field.xy = camera position in the
// pattern, wrapped). Spot0..3 = brighter places (the corrupted forest): xz relative to the camera, radius, weight.

uniform vec4 Aurora;    // x = strength, y = time in seconds (wrapped), z = shimmer speed, w = pattern scale in blocks
uniform vec4 Layer;     // x = bottom above camera, y = top above camera, z = base brightness, w = forest boost
uniform vec4 Field;     // xy = camera position in the pattern (wrapped), zw = drift offset
uniform vec4 ColorLow;
uniform vec4 ColorHigh;
uniform vec4 Spot0;
uniform vec4 Spot1;
uniform vec4 Spot2;
uniform vec4 Spot3;

in vec3 ray;

out vec4 fragColor;

float ovHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

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

float ovSpot(vec2 rel, vec4 spot) {
    vec2 d = rel - spot.xy;
    return spot.w * exp(-dot(d, d) / max(spot.z * spot.z, 1.0));
}

void main() {
    vec3 dir = normalize(ray);
    // Nothing near and below the horizon: the lowest ring of the sky stays the fog colour (no seam against the terrain).
    float horizon = smoothstep(0.05, 0.30, dir.y);
    if (horizon <= 0.0 || Aurora.x <= 0.0) {
        discard;
    }
    float scale = Aurora.w;
    float t = Aurora.y;
    // A little per-pixel offset along the ray hides the steps.
    float jitter = ovHash(gl_FragCoord.xy + vec2(17.0, 3.0));
    const int STEPS = 12;
    // What changes slowly across the sky is worked out once per pixel, at a third of the way up the layer: the bending of the
    // curtains, the big patches, the slow pulse and the brighter places over the forest (11-16: the loop was too heavy otherwise).
    vec2 relM = dir.xz * (mix(Layer.x, Layer.y, 0.3) / dir.y);
    vec2 pM = (relM + Field.xy + Field.zw) / scale;
    vec2 warp = (vec2(ovNoise(pM * 0.7 + vec2(3.1, t * 0.03)), ovNoise(pM * 0.7 + vec2(-7.3, 11.0 - t * 0.024))) - 0.5) * 0.9;
    float patch = mix(0.3, 1.0, smoothstep(0.25, 0.65, ovNoise(pM * 0.45 + vec2(41.0, t * 0.012))));
    float pulse = 0.75 + 0.25 * ovNoise(vec2(pM.x * 3.0 - t * 0.12 * Aurora.z, pM.y * 3.0));
    float forest = Layer.z + Layer.w * min(1.0, ovSpot(relM, Spot0) + ovSpot(relM, Spot1) + ovSpot(relM, Spot2) + ovSpot(relM, Spot3));
    vec3 sum = vec3(0.0);
    for (int i = 0; i < STEPS; i++) {
        float u = (float(i) + jitter) / float(STEPS);
        float h = mix(Layer.x, Layer.y, u);
        vec2 q = (dir.xz * (h / dir.y) + Field.xy + Field.zw) / scale + warp;
        // Bands: stripes across one direction of the pattern, bent by the noise, so some always cross the sky overhead.
        float n = 1.5 * ovNoise(q * 0.9 + 5.0);
        float s1 = q.y * 2.2 + n * 1.5;
        float s2 = q.y * 3.7 - q.x * 1.3 + n * 2.1 + 0.37;
        float b1 = 1.0 - abs(sin(3.14159 * s1));
        float b2 = 1.0 - abs(sin(3.14159 * s2));
        // A sharp bright core and a soft body around it.
        float line = pow(b1, 12.0) + 0.22 * pow(b1, 3.0) + 0.55 * pow(b2, 18.0) + 0.10 * pow(b2, 4.0);
        // Vertical streaks: depends on the place only, so it reads as rays standing in the curtain; they flicker slowly.
        float streak = 0.55 + 0.45 * ovNoise(q * 34.0 + vec2(t * 0.45 * Aurora.z, t * 0.2 * Aurora.z));
        // Height profile: brightest just above the lower edge, fading upwards.
        float prof = smoothstep(0.0, 0.07, u) * exp(-u * 2.6);
        sum += mix(ColorLow.rgb, ColorHigh.rgb, smoothstep(0.0, 0.75, u)) * (line * streak * prof);
    }
    sum *= patch * pulse * forest;
    // Far away (low in the sky) the layer is seen edge-on and would pile up: thin it with the distance.
    float thin = 1.0 - smoothstep(4.0, 11.0, 1.0 / dir.y);
    vec3 c = sum * (8.5 / float(STEPS)) * Aurora.x * horizon * thin;
    fragColor = vec4(c, 1.0);
}
