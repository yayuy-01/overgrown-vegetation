#version 150

// Overgrown Vegetation: morning mist (spore/MorningMist.java and client/MorningMistView.java hold the explanation).
// Keep this file ASCII-only.
//
// At dawn a layer of mist lies in the low ground in and around the overgrown area. The CPU writes a small map of the
// layer around the camera (linear filtering), every texel OvMistA.z blocks wide:
//   r = bottom of the layer above OvMistB.z, in half blocks (the top is that plus the depth OvMistC.w)
//   b = how near the overgrown area is (0 to 1; the mist thins out away from it)
//   a = 1 known, 0 unknown
// The haze of a fragment is the mist that the ray from the camera crosses: density x length x the average of the
// density profile along the ray (the profile rises over the top OV_MIST_SOFT blocks of the layer and is even below;
// the top is taken as changing linearly between the camera and the fragment). A camera above the layer sees it thicker
// (OvMistB.y): from a hill the forest looks hazy, while down in the mist the near world stays almost clear.
//
// OvMistA = (map origin x, map origin z (both relative to the camera), texel size in blocks, map size in texels)
// OvMistB = (density per block, 0 = off; boost of a camera above the layer; base y relative to the camera; time in s)
// OvMistC = (lit mist colour rgb, depth of the layer in blocks)
// OvMistD = (sun direction xyz, warm glow toward the sun)
// OvMistE = (noise offset x, noise offset z (the camera in noise units, wrapped to 64), unused, unused)
uniform sampler2D OvMistMap;
uniform vec4 OvMistA;
uniform vec4 OvMistB;
uniform vec4 OvMistC;
uniform vec4 OvMistD;
uniform vec4 OvMistE;

const float OV_MIST_SOFT = 3.0;
// Blocks per noise cell of the drifting wisps.
const float OV_MIST_NOISE_SCALE = 24.0;

float ovMistHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float ovMistNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = ovMistHash(i);
    float b = ovMistHash(i + vec2(1.0, 0.0));
    float c = ovMistHash(i + vec2(0.0, 1.0));
    float d = ovMistHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// (top of the layer relative to the camera, weight) at the camera-relative position xz.
vec2 ovMistAt(vec2 xz) {
    vec2 uv = (xz - OvMistA.xy) / OvMistA.z;
    float size = OvMistA.w;
    if (uv.x < 0.5 || uv.y < 0.5 || uv.x > size - 0.5 || uv.y > size - 0.5) {
        return vec2(0.0, 0.0);
    }
    vec4 m = texture(OvMistMap, uv / size);
    float top = OvMistB.z + m.r * 127.5 + OvMistC.w;
    float edge = smoothstep(0.5, 4.0, min(min(uv.x, uv.y), min(size - uv.x, size - uv.y)));
    return vec2(top, m.b * m.a * edge);
}

// Integral of the density profile from 0 to h (h = height above the top of the layer, negative inside).
float ovMistF(float h) {
    if (h >= 0.0) {
        return 0.0;
    }
    if (h > -OV_MIST_SOFT) {
        return -h * h / (2.0 * OV_MIST_SOFT);
    }
    return h + OV_MIST_SOFT * 0.5;
}

// (mist colour, haze 0 to 1) at camera-relative position pos: the fragment colour becomes mix(colour, mist colour, haze).
// The haze changes slowly (the layer map, the 24-block wisps, the length of the ray), so the terrain shaders compute it per vertex
// (2026-10-23, it was the heaviest effect per pixel: leaves are discarded, so early-Z cannot skip the hidden layers).
vec4 ovMorningMistHaze(vec3 pos) {
    if (OvMistB.x <= 0.0) {
        return vec4(0.0);
    }
    vec2 cam = ovMistAt(vec2(0.0));
    vec2 frag = ovMistAt(pos.xz);
    float h0 = -cam.x;
    float h1 = pos.y - frag.x;
    // A camera above the layer looks down into the mist lying around the fragment.
    float weight = mix(0.5 * (cam.y + frag.y), frag.y, clamp(h0 / 4.0, 0.0, 1.0));
    if (weight <= 0.001) {
        return vec4(0.0);
    }
    float avg;
    if (abs(h1 - h0) > 0.01) {
        avg = (ovMistF(h1) - ovMistF(h0)) / (h1 - h0);
    } else {
        avg = clamp(-h0 / OV_MIST_SOFT, 0.0, 1.0);
    }
    if (avg <= 0.0) {
        return vec4(0.0);
    }
    float len = length(pos);
    vec2 p = pos.xz / OV_MIST_NOISE_SCALE + OvMistE.xy + vec2(OvMistB.w * 0.02, OvMistB.w * 0.007);
    float wisps = 0.7 + 0.6 * (ovMistNoise(p) * 0.65 + ovMistNoise(p * 2.3 + 11.0) * 0.35);
    float tau = OvMistB.x * OvMistB.y * weight * len * avg * wisps;
    float haze = 1.0 - exp(-tau);
    vec3 dir = pos / max(len, 0.001);
    float lobe = pow(max(dot(dir, OvMistD.xyz), 0.0), 6.0) * OvMistD.w;
    vec3 mistColor = mix(OvMistC.rgb, min(OvMistC.rgb * vec3(1.3, 1.08, 0.8) + vec3(0.1, 0.06, 0.0), vec3(1.0)), lobe);
    return vec4(mistColor, haze);
}

// Colour of a fragment at camera-relative position pos seen through the mist.
vec3 ovMorningMist(vec3 color, vec3 pos) {
    vec4 m = ovMorningMistHaze(pos);
    return mix(color, m.rgb, m.a);
}

// Two-colour fog (aerial perspective; client/AerialFog.java holds the explanation): the fog colour of a terrain fragment
// leans toward a warm sunlit colour the nearer it is (the near haze is lit by the sun) and stays the plain fog colour far
// away (the blue-green of the forest, the same as the sky behind it, so the end of the fog keeps no seam). Stronger toward
// the sun (light scatters forward).
// OvAerialA = (warm colour rgb, strength; 0 = off)
// OvAerialB = (sun direction xyz, how much the sun side is favoured)
uniform vec4 OvAerialA;
uniform vec4 OvAerialB;

vec4 ovAerialFog(vec4 fogColor, vec3 pos, float dist, float fogStart, float fogEnd) {
    if (OvAerialA.w <= 0.0) {
        return fogColor;
    }
    // How far into the fog band (fog start to fog end) the fragment is: the first part of the haze is warm, the end is the fog colour.
    float t = clamp((dist - fogStart) / max(fogEnd - fogStart, 1.0), 0.0, 1.0);
    float near = 1.0 - smoothstep(0.0, 0.85, t);
    float toSun = max(dot(pos / max(length(pos), 0.001), OvAerialB.xyz), 0.0);
    float w = OvAerialA.w * near * mix(1.0, 0.6 + 1.2 * toSun * toSun, OvAerialB.w);
    return vec4(mix(fogColor.rgb, OvAerialA.rgb, clamp(w, 0.0, 1.0)), fogColor.a);
}
