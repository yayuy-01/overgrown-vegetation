#version 150

// Overgrown Vegetation: virga (client/Virga.java holds the explanation). Keep this file ASCII-only.
// Haze = (haze colour, blue haze setting) like the voxel clouds. SunDir = (direction to the light, strength).
// Fade = (inside-a-cloud fade, -, -, -).

uniform vec4 ColorModulator;
uniform vec4 Split;
uniform vec4 Haze;
uniform vec4 SunDir;
uniform vec4 SunColor;
uniform vec4 Fade;

in vec3 ovPos;
in vec2 ovUv;
in vec4 ovColor;

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

void main() {
    float dist = length(ovPos);
    // The far pass reaches 24 blocks into the near range (the near pass draws over it), so no gap shows at the split.
    if (Split.y > 0.5 ? dist < Split.x - 24.0 : (Split.y < -0.5 && dist >= Split.x)) {
        discard;
    }
    float seed = floor(ovUv.x);
    float across = fract(ovUv.x);
    float along = ovUv.y;
    // Soft sides.
    float edge = smoothstep(0.0, 0.28, across) * (1.0 - smoothstep(0.72, 1.0, across));
    // Fibres: the streak splits into thin falling threads, more of them lower down.
    float n1 = ovNoise(vec2(across * 6.0 + seed * 13.1, along * 1.3 + seed * 0.7));
    float n2 = ovNoise(vec2(across * 17.0 + seed * 3.7, along * 2.6 + 5.0));
    float fibre = smoothstep(0.32, 0.72, mix(n1, 0.6 * n1 + 0.4 * n2, along));
    // Each thread evaporates at its own length (none reaches the end of the strip).
    float reach = 0.5 + 0.5 * ovNoise(vec2(across * 4.0 + seed * 7.0, seed * 1.9));
    float fall = smoothstep(0.0, 0.06, along) * (1.0 - smoothstep(reach * 0.45, reach, along));
    float a = ovColor.a * edge * fibre * fall * (1.0 - Fade.x);
    // Seen from straight below, a streak is foreshortened into a bright scratch on the cloud base: fade it out.
    vec3 look = normalize(ovPos);
    a *= smoothstep(0.05, 0.5, 1.0 - abs(look.y));
    if (a <= 0.003) {
        discard;
    }
    vec3 c = ovColor.rgb;
    // Light scattered forwards: brighter (and a little denser) towards the sun, like rain lit from behind.
    vec3 v = normalize(ovPos);
    float fwd = pow(max(dot(v, SunDir.xyz), 0.0), 6.0) * SunDir.w;
    c += SunColor.rgb * fwd * 0.35;
    a = min(1.0, a * (1.0 + 0.5 * fwd));
    // Blue haze, the same as the voxel clouds.
    c = mix(c, Haze.rgb, Haze.w * (0.45 * (1.0 - exp(-dist / 2000.0)) + 0.3 * smoothstep(2500.0, 7000.0, dist)));
    fragColor = vec4(min(c, vec3(1.0)), clamp(a, 0.0, 1.0)) * ColorModulator;
}
