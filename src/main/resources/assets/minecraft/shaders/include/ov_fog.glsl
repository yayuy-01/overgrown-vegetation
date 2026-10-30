#version 150

// Overgrown Vegetation: the height fog of the overgrown forest (client/atmosphere/MistFog.java, MistMap.java and MistLayer.java hold
// the explanation). Keep this file ASCII-only.
//
// A layer of mist lies over the overgrown area. Below the top of the layer the density is even; above it the density falls off as
// exp(-k * height above the top) (a soft ceiling). The top comes from a map around the camera (OvFogMap, linear filtering, one texel
// per OvFogA.z blocks):
//   r = bottom of the valley layer above the base OvFogB.z (blocks; the valley top is that plus the depth OvFogB.w)
//   g = floor of the cloud sea over the canopy above the base (the sea top is that plus OvFogG.x, mixed in by OvFogC.w)
//   b = how near the overgrown area is (0 to 1), a = 1 known, 0 unknown
// The mist a ray crosses is integrated in closed form over two straight pieces (camera to midpoint, midpoint to vertex), each with the
// height above the top changing linearly. A camera above the layer sees it thicker (OvFogG.y, reached OvFogG.z blocks above the top of
// each point): from a hill the valleys look misty, while down in the mist the near world stays clear enough. The colour goes from the warm
// sunlit near colour to the blue-green far colour with distance, brighter toward the sun. Computed per vertex (terrain faces are at most a block).
//
// OvFogA = (map origin x, map origin z (both relative to the camera), texel size in blocks, map size in texels)
// OvFogB = (density per block, 0 = off; falloff k above the top; base y relative to the camera; depth of the valley layer)
// OvFogC = (near colour rgb, how much the cloud sea is on)
// OvFogD = (far colour rgb, colour distance in blocks)
// OvFogE = (sun direction xyz, glow toward the sun)
// OvFogF = (noise offset x, z (noise cells, wrapped to 64), noise amount (0 = no mottling), noise cell size in blocks)
// OvFogG = (cloud sea above its floor, boost above the layer, height of full boost, sky light at the camera 0 to 1)
// OvFogH = (darkest mist colour under the canopy, distance where the near ray march (MistMarch, quality HIGH) starts handing over,
//           distance where it has handed over (0 = no march: integrate from the camera), fine near mottling for quality MEDIUM)
// OvFogI = (far desaturation of the terrain (fragment shaders), from, to (blocks), glow of the mist toward the sun)
uniform sampler2D OvFogMap;
uniform vec4 OvFogA;
uniform vec4 OvFogB;
uniform vec4 OvFogC;
uniform vec4 OvFogD;
uniform vec4 OvFogE;
uniform vec4 OvFogF;
uniform vec4 OvFogG;
uniform vec4 OvFogH;
uniform vec4 OvFogI;

float ovFogHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float ovFogNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = ovFogHash(i);
    float b = ovFogHash(i + vec2(1.0, 0.0));
    float c = ovFogHash(i + vec2(0.0, 1.0));
    float d = ovFogHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// (top of the layer relative to the camera, weight) at the camera-relative position xz.
vec2 ovFogTop(vec2 xz) {
    vec2 uv = (xz - OvFogA.xy) / OvFogA.z;
    float size = OvFogA.w;
    if (uv.x < 0.5 || uv.y < 0.5 || uv.x > size - 0.5 || uv.y > size - 0.5) {
        return vec2(-10000.0, 0.0);
    }
    vec4 m = textureLod(OvFogMap, uv / size, 0.0);
    float top = OvFogB.z + m.r * 255.0 + OvFogB.w;
    float sea = OvFogB.z + m.g * 255.0 + OvFogG.x;
    top = mix(top, max(top, sea), OvFogC.w);
    float edge = smoothstep(0.5, 4.0, min(min(uv.x, uv.y), min(size - uv.x, size - uv.y)));
    return vec2(top, m.b * m.a * edge);
}

float ovFogF(float h, float k) {
    return h <= 0.0 ? h - 1.0 / k : -exp(-k * h) / k;
}

// The density profile integrated over a piece of length len along which the height above the top goes from h0 to h1.
float ovFogSegment(float h0, float h1, float len, float k) {
    float dh = h1 - h0;
    if (abs(dh) < 0.05) {
        float h = 0.5 * (h0 + h1);
        return len * (h <= 0.0 ? 1.0 : exp(-k * h));
    }
    return len * (ovFogF(h1, k) - ovFogF(h0, k)) / dh;
}

// How much thicker a camera above the top of a point sees the mist there.
float ovFogBoost(float top) {
    return 1.0 + (OvFogG.y - 1.0) * clamp(-top / OvFogG.z, 0.0, 1.0);
}

// The mist (density times length, before OvFogB.x) the ray from the camera to the camera-relative position pos crosses.
float ovFogTau(vec3 pos) {
    vec2 c = ovFogTop(vec2(0.0));
    vec2 m = ovFogTop(pos.xz * 0.5);
    vec2 f = ovFogTop(pos.xz);
    if (c.y + m.y + f.y < 0.003) {
        return 0.0;
    }
    float len = length(pos);
    float k = OvFogB.y;
    float h0 = -c.x;
    float hm = pos.y * 0.5 - m.x;
    float h1 = pos.y - f.x;
    return ovFogSegment(h0, hm, 0.5 * len, k) * 0.5 * (c.y + m.y) * ovFogBoost(m.x)
            + ovFogSegment(hm, h1, 0.5 * len, k) * 0.5 * (m.y + f.y) * ovFogBoost(f.x);
}

// (mist colour, haze 0 to 1) at the camera-relative position pos: the colour becomes mix(colour, mist colour, haze).
vec4 ovFogHaze(vec3 pos) {
    if (OvFogB.x <= 0.0) {
        return vec4(0.0);
    }
    float len = length(pos);
    if (OvFogH.z > 0.0 && len <= OvFogH.y) {
        return vec4(0.0);
    }
    float tau = ovFogTau(pos);
    if (OvFogH.z > 0.0) {
        // The near part belongs to the ray march; hand over linearly between OvFogH.y and OvFogH.z (exact for an even density there).
        float t0 = ovFogTau(pos * min(OvFogH.y / len, 1.0));
        float t1 = len > OvFogH.z ? ovFogTau(pos * (OvFogH.z / len)) : tau;
        tau -= 0.5 * (t0 + t1);
    }
    vec2 f = ovFogTop(pos.xz);
    if (OvFogF.z > 0.0) {
        // Mottling: noise where the ray crosses the middle of the layer of the far end (soft cloud-like pools).
        float t = abs(pos.y) > 0.01 ? clamp((f.x - 0.5 * OvFogB.w) / pos.y, 0.3, 1.0) : 1.0;
        vec2 p = pos.xz * t / OvFogF.w + OvFogF.xy;
        float n = ovFogNoise(p) * 0.65 + ovFogNoise(p * 2.3 + 11.0) * 0.35;
        tau *= mix(1.0, 0.35 + 1.3 * n, OvFogF.z);
    }
    if (OvFogH.w > 0.0) {
        // Quality MEDIUM has no ray march: finer mottling near the camera (half the cell; 2 x the wrapped offset keeps the 64 wrap seamless).
        vec2 p2 = pos.xz / (OvFogF.w * 0.5) + OvFogF.xy * 2.0;
        float near = 1.0 - smoothstep(12.0, 40.0, len);
        tau *= mix(1.0, 0.4 + 1.2 * ovFogNoise(p2), OvFogH.w * near);
    }
    vec3 dir = pos / max(len, 0.001);
    // The mist toward the sun glows: light scattered forward (the foot of a light shaft shines white).
    float glow = pow(max(dot(dir, OvFogE.xyz), 0.0), 8.0) * OvFogI.w * clamp((OvFogE.y + 0.1) / 0.2, 0.0, 1.0);
    float haze = 1.0 - exp(-OvFogB.x * max(tau, 0.0) * (1.0 + 1.5 * glow));
    vec3 color = mix(OvFogC.rgb, OvFogD.rgb, 1.0 - exp(-len / OvFogD.w));
    float lobe = clamp(pow(max(dot(dir, OvFogE.xyz), 0.0), 6.0) * OvFogE.w + glow, 0.0, 1.0);
    color = mix(color, min(OvFogC.rgb * vec3(1.15, 1.08, 0.95) + vec3(0.06, 0.05, 0.02), vec3(1.0)), lobe);
    return vec4(color, haze);
}

// ovFogHaze with the colour darkened in the shade of the canopy: by the darker of the sky light at the vertex and at the camera,
// down to OvFogH.x (the mist under the leaves is not lit; the leaves do not glow).
vec4 ovFogShaded(vec3 pos, float vertexSky) {
    vec4 fog = ovFogHaze(pos);
    fog.rgb *= mix(OvFogH.x, 1.0, min(vertexSky, OvFogG.w));
    return fog;
}
