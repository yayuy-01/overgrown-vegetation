#version 150

// Overgrown Vegetation: plants swaying in the wind (client/atmosphere/LeafSway.java and SwayModels.java hold the explanation).
// Keep this file ASCII-only.
//
// Which vertices move: client/atmosphere/SwayModels.java writes a marker into the alpha of the baked quads of the swaying blocks.
// code = 255 - alpha byte. 1..32: sway freely with weight (code - 1) / 31. 33..63: sway only along the face plane
// (wall vines) with weight (code - 33) / 30; 61, 62, 63 = along the face plane with weight 1 and the face normal on the
// x, y, z axis (what SwayModels writes now). Other codes (alpha 255) never move. Marked vertices get their alpha back to 1.
//
// OvBase  = (calm wind, calm wind dir x, calm wind dir z, sway amount; 0 = off)
// OvPhase = phase offsets that carry the camera position (computed in double precision on the CPU)
// OvOsc   = (sin, cos) of the 0.25 Hz and 0.61 Hz branch sway, OvOsc2.xy = (sin, cos) of the 3.1 Hz flutter
// OvGustA = (centre x, centre z relative to the camera, dir x, dir z), OvGustB = (strength, length ahead, length behind, half width)
// OvSwayK = (push per wind strength, branch sway in calm air, extra branch sway per wind strength, flutter per wind strength)
uniform vec4 OvBase;
uniform vec4 OvPhase;
uniform vec4 OvOsc;
uniform vec4 OvOsc2;
uniform vec4 OvSwayK;
uniform vec4 OvGustA0;
uniform vec4 OvGustA1;
uniform vec4 OvGustA2;
uniform vec4 OvGustA3;
uniform vec4 OvGustB0;
uniform vec4 OvGustB1;
uniform vec4 OvGustB2;
uniform vec4 OvGustB3;

float ovGust(vec2 p, vec4 a, vec4 b, inout vec2 push) {
    if (b.x <= 0.0) {
        return 0.0;
    }
    vec2 r = p - a.xy;
    float along = dot(r, a.zw);
    float side = -r.x * a.w + r.y * a.z;
    float len = along > 0.0 ? b.y : b.z;
    float e = (along / len) * (along / len) + (side / b.w) * (side / b.w);
    if (e > 12.0) {
        return 0.0;
    }
    float g = b.x * exp(-e);
    push += a.zw * g;
    return g;
}

vec3 ovSway(vec3 pos) {
    vec2 p = pos.xz;
    float calm = OvBase.x * (1.0 + 0.25 * sin(p.x * 0.13 + OvPhase.x) * sin(p.y * 0.11 + OvPhase.y));
    vec2 push = OvBase.yz * calm;
    float w = calm;
    w += ovGust(p, OvGustA0, OvGustB0, push);
    w += ovGust(p, OvGustA1, OvGustB1, push);
    w += ovGust(p, OvGustA2, OvGustB2, push);
    w += ovGust(p, OvGustA3, OvGustB3, push);
    w = min(w, 1.2);
    float pl = length(push);
    vec2 d = pl > 0.0001 ? push / pl : OvBase.yz;
    // Branch sway: neighbouring plants move together (smooth in space, so neighbouring blocks never crack apart).
    float phi = p.x * 0.21 + p.y * 0.17 + pos.y * 0.05 + OvPhase.z;
    float s1 = 0.7 * (OvOsc.x * cos(phi) + OvOsc.y * sin(phi))
             + 0.3 * (OvOsc.z * cos(1.7 * phi) + OvOsc.w * sin(1.7 * phi));
    // Flutter: a faster, smaller shiver across the wind, strongest in gusts.
    float psi = p.x * 0.9 + p.y * 0.7 + pos.y * 0.8 + OvPhase.w;
    float f = OvOsc2.x * cos(psi) + OvOsc2.y * sin(psi);
    float along = OvSwayK.x * w + (OvSwayK.y + OvSwayK.z * w) * s1;
    float across = OvSwayK.w * w * f;
    vec2 h = d * along + vec2(-d.y, d.x) * across;
    float up = (0.004 + 0.02 * w) * 0.5 * s1 + 0.6 * across;
    return vec3(h.x, up, h.y) * OvBase.w;
}

// Displacement of a chunk vertex; alpha is set back to 1 for marked vertices.
vec3 ovDisplace(vec3 pos, vec3 normal, inout float alpha) {
    float code = floor((1.0 - alpha) * 255.0 + 0.5);
    if (code < 1.0 || code > 63.0) {
        return vec3(0.0);
    }
    alpha = 1.0;
    if (OvBase.w <= 0.0) {
        return vec3(0.0);
    }
    bool planar = code >= 33.0;
    // 61..63: planar with weight 1 and the face axis in the code (x, y, z), so that Sodium (no vertex normal) can use it too.
    bool axis = code >= 61.0;
    float weight = axis ? 1.0 : (planar ? (code - 33.0) / 30.0 : (code - 1.0) / 31.0);
    if (weight <= 0.0) {
        return vec3(0.0);
    }
    vec3 d = ovSway(pos) * weight;
    if (planar) {
        vec3 n = axis ? (code < 61.5 ? vec3(1.0, 0.0, 0.0) : (code < 62.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0))) : normal;
        d -= dot(d, n) * n;
    }
    return d;
}
