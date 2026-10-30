#version 150

// Overgrown Vegetation: the mist lying on the floor of large caves, seen as haze (client/CaveMist.java holds the
// explanation). Keep this file ASCII-only. Import it after ov_mist.glsl (it uses ovMistNoise).
//
// The sheets of the cave mist (cave_mist.fsh) only work when seen from above: down in the mist they are seen edge-on
// and their cut edges look like glass. So the mist is also drawn as haze, like the morning mist: the CPU writes a map of
// the layer around the camera (linear filtering), every texel OvCaveA.z blocks wide:
//   r = top of the layer above OvCaveB.z, in quarter blocks
//   g = weight (0 to 1: open cave floor, away from walls and narrow tunnels)
//   b = block light just above the floor (0 to 1), a = sky light there (0 to 1)
// The haze of a fragment is the mist that the ray from the camera crosses: density x length x the average of the
// density profile along the ray (the profile rises over the top OvCaveC.w blocks of the layer and is even below; the
// top is taken as changing linearly between the camera and the fragment). The mist is lit where light reaches the floor.
//
// OvCaveA = (map origin x, map origin z (both relative to the camera), texel size in blocks, map size in texels)
// OvCaveB = (density per block, 0 = off; boost of a camera above the layer; base y relative to the camera; time in s)
// OvCaveC = (colour of the mist in the dark rgb, thickness of the soft top in blocks)
// OvCaveD = (colour added where block light reaches the mist rgb, noise offset x (the camera in noise units, wrapped))
// OvCaveE = (colour added where sky light reaches the mist rgb (already times daylight), noise offset z)
uniform sampler2D OvCaveMistMap;
uniform vec4 OvCaveA;
uniform vec4 OvCaveB;
uniform vec4 OvCaveC;
uniform vec4 OvCaveD;
uniform vec4 OvCaveE;

// Blocks per noise cell of the drifting wisps (the same as the sheets).
const float OV_CAVE_NOISE_SCALE = 7.0;

// (top of the layer relative to the camera, weight, block light, sky light) at the camera-relative position xz.
vec4 ovCaveAt(vec2 xz) {
    vec2 uv = (xz - OvCaveA.xy) / OvCaveA.z;
    float size = OvCaveA.w;
    if (uv.x < 0.5 || uv.y < 0.5 || uv.x > size - 0.5 || uv.y > size - 0.5) {
        return vec4(0.0);
    }
    vec4 m = texture(OvCaveMistMap, uv / size);
    float top = OvCaveB.z + m.r * 63.75;
    float edge = smoothstep(0.5, 4.0, min(min(uv.x, uv.y), min(size - uv.x, size - uv.y)));
    return vec4(top, m.g * edge, m.b, m.a);
}

// Integral of the density profile from 0 to h (h = height above the top of the layer, negative inside).
float ovCaveF(float h) {
    float soft = max(OvCaveC.w, 0.05);
    if (h >= 0.0) {
        return 0.0;
    }
    if (h > -soft) {
        return -h * h / (2.0 * soft);
    }
    return h + soft * 0.5;
}

// (mist colour, haze 0 to 1) of the cave mist at camera-relative position pos (per vertex in the terrain shaders, like the
// morning mist).
vec4 ovCaveMistHaze(vec3 pos) {
    if (OvCaveB.x <= 0.0) {
        return vec4(0.0);
    }
    vec4 cam = ovCaveAt(vec2(0.0));
    vec4 frag = ovCaveAt(pos.xz);
    float h0 = -cam.x;
    float h1 = pos.y - frag.x;
    // A camera above the layer looks down into the mist lying around the fragment.
    float weight = mix(0.5 * (cam.y + frag.y), frag.y, clamp(h0 / 3.0, 0.0, 1.0));
    if (weight <= 0.001) {
        return vec4(0.0);
    }
    float avg;
    if (abs(h1 - h0) > 0.01) {
        avg = (ovCaveF(h1) - ovCaveF(h0)) / (h1 - h0);
    } else {
        avg = clamp(-h0 / max(OvCaveC.w, 0.05), 0.0, 1.0);
    }
    if (avg <= 0.0) {
        return vec4(0.0);
    }
    float len = length(pos);
    vec2 p = pos.xz / OV_CAVE_NOISE_SCALE + vec2(OvCaveD.w, OvCaveE.w) + vec2(OvCaveB.w * 0.04, OvCaveB.w * 0.017);
    float wisps = 0.6 + 0.8 * (ovMistNoise(p) * 0.65 + ovMistNoise(p * 2.3 + 11.0) * 0.35);
    float boost = 1.0 + (OvCaveB.y - 1.0) * smoothstep(0.0, 6.0, h0);
    float tau = OvCaveB.x * boost * weight * len * avg * wisps;
    float haze = 1.0 - exp(-tau);
    vec3 mistColor = min(OvCaveC.rgb + OvCaveD.rgb * (frag.z * frag.z) + OvCaveE.rgb * (frag.w * frag.w), vec3(1.0));
    return vec4(mistColor, haze);
}

// Colour of a fragment at camera-relative position pos seen through the cave mist.
vec3 ovCaveMist(vec3 color, vec3 pos) {
    vec4 m = ovCaveMistHaze(pos);
    return mix(color, m.rgb, m.a);
}

// Vertex stage of the terrain shaders: both mists (the morning mist first, then the cave mist) as one transform
// colour * a + rgb, the same as ovCaveMist(ovMorningMist(colour, pos), pos). Premultiplied, so it interpolates correctly
// between a misty vertex and a clear one.
vec4 ovHazeVertex(vec3 pos) {
    vec4 m = ovMorningMistHaze(pos);
    vec4 c = ovCaveMistHaze(pos);
    return vec4(m.rgb * (m.a * (1.0 - c.a)) + c.rgb * c.a, (1.0 - m.a) * (1.0 - c.a));
}
