#version 150

#moj_import <ov_fog.glsl>

// Overgrown Vegetation: the near mist of quality HIGH (client/atmosphere/MistMarch.java holds the explanation). Keep this file ASCII-only.
//
// Drawn at a fraction of the screen size. For every pixel the ray from the camera to the terrain (the depth buffer; the sky counts as far)
// is marched over the first MarchA.y blocks: the same height layer as ov_fog.glsl (map OvFogMap, uniforms OvFog*), times a drifting 3D
// noise, handing over to the closed form of the terrain shaders between MarchA.z and MarchA.y. Where the canopy sun map (SunMap, the same
// map as ov_light.glsl) says a step is in the sun, the mist scatters sunlight forward (Henyey-Greenstein): shafts of light in the mist.
// Output: rgb = light scattered toward the camera (premultiplied), a = transmittance. MistMarch blends it with (ONE, SRC_ALPHA).
//
// MarchA = (steps, march distance, hand-over start, noise amount)
// MarchB = (noise cell size in blocks, sun scatter strength, scatter g, unused)
// MarchC = (noise offset xyz in noise cells (camera minus wind drift, wrapped to 64), unused)
// MarchD = (sun strength 0 to 1, sun map on 0 or 1, unused, unused)
// SunA = (sun.x / sun.y, sun.z / sun.y, unused, unused)
// MapA = (sun map origin u, v relative to the camera, size in texels, base y relative to the camera)
uniform sampler2D DepthTex;
uniform sampler2D SunMap;
uniform mat4 InvViewProj;
uniform vec4 MarchA;
uniform vec4 MarchB;
uniform vec4 MarchC;
uniform vec4 MarchD;
uniform vec4 SunA;
uniform vec4 MapA;

in vec2 texCoord;

out vec4 fragColor;

float ovHash3(vec3 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
}

float ovNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(ovHash3(i), ovHash3(i + vec3(1.0, 0.0, 0.0)), f.x);
    float b = mix(ovHash3(i + vec3(0.0, 1.0, 0.0)), ovHash3(i + vec3(1.0, 1.0, 0.0)), f.x);
    float c = mix(ovHash3(i + vec3(0.0, 0.0, 1.0)), ovHash3(i + vec3(1.0, 0.0, 1.0)), f.x);
    float d = mix(ovHash3(i + vec3(0.0, 1.0, 1.0)), ovHash3(i + vec3(1.0, 1.0, 1.0)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// 1 in the sun, 0 in canopy shade, 0.5 unknown (the map of ov_light.glsl: r = top of the highest solid block, g = top of the highest
// leaf on the sun line, b = leaves crossed, a = 1 traced in the overgrown area).
float ovSunlit(vec3 pos) {
    if (MarchD.y <= 0.0) {
        return 0.5;
    }
    vec2 uv = vec2(pos.x - pos.y * SunA.x, pos.z - pos.y * SunA.y) - MapA.xy - 0.5;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x >= MapA.z - 1.0 || uv.y >= MapA.z - 1.0) {
        return 0.5;
    }
    vec4 m = texelFetch(SunMap, ivec2(floor(uv + 0.5)), 0);
    if (m.a < 0.75) {
        return 0.5;
    }
    float h = pos.y - MapA.w;
    if (m.r > 0.002 && h < m.r * 255.0 - 1.5) {
        return 0.0;
    }
    if (m.g > 0.002 && m.b > 0.01 && h < m.g * 255.0 - 1.0) {
        return 0.0;
    }
    return 1.0;
}

void main() {
    if (OvFogB.x <= 0.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float depth = texture(DepthTex, texCoord).r;
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 end = p.xyz / p.w;
    float dist = depth >= 1.0 ? 1.0e6 : length(end);
    vec3 dir = normalize(end);
    float maxD = min(dist, MarchA.y);
    int steps = int(MarchA.x);
    float dt = maxD / float(steps);
    // Interleaved gradient noise: a different start in every pixel, so few steps leave no bands.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float k = OvFogB.y;
    float cosSun = dot(dir, OvFogE.xyz);
    float g = MarchB.z;
    float hg = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * cosSun, 1.0e-4), 1.5);
    float shade = mix(OvFogH.x, 1.0, OvFogG.w);
    vec3 sunColor = min(OvFogC.rgb * vec3(1.2, 1.1, 0.95) + vec3(0.05, 0.04, 0.0), vec3(1.0));
    float T = 1.0;
    vec3 light = vec3(0.0);
    for (int i = 0; i < 32; i++) {
        if (i >= steps) {
            break;
        }
        float s = (float(i) + jitter) * dt;
        vec3 pos = dir * s;
        vec2 top = ovFogTop(pos.xz);
        float h = pos.y - top.x;
        float dens = OvFogB.x * (h <= 0.0 ? 1.0 : exp(-k * h)) * top.y * ovFogBoost(top.x);
        // Hand over to the closed form between MarchA.z and MarchA.y (ov_fog.glsl takes the other half).
        dens *= clamp((MarchA.y - s) / max(MarchA.y - MarchA.z, 0.001), 0.0, 1.0);
        float n = ovNoise3(pos / MarchB.x + MarchC.xyz) * 0.6 + ovNoise3(pos / MarchB.x * 2.7 + MarchC.xyz * 1.3 + 7.0) * 0.4;
        dens *= mix(1.0, 0.15 + 1.7 * n, MarchA.w);
        if (dens <= 0.0) {
            continue;
        }
        vec3 base = mix(OvFogC.rgb, OvFogD.rgb, 1.0 - exp(-s / OvFogD.w)) * shade;
        vec3 col = base + sunColor * (MarchD.x * MarchB.y * 0.25 * hg * ovSunlit(pos));
        float tr = exp(-dens * dt);
        light += T * (1.0 - tr) * col;
        T *= tr;
    }
    fragColor = vec4(light, T);
}
