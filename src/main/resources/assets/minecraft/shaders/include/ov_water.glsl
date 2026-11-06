#version 150

// Overgrown Vegetation: the sun and the moon mirrored on open water (11-06, "light of the vanilla areas" 4;
// client/atmosphere/WaterGlint.java holds the explanation and sets the uniforms). Keep this file ASCII-only.
//
// Vanilla water is a flat sheet of the sky colour: no sunset and no moon on it. Here an up-facing face of the translucent pass gets a
// Blinn highlight between the view and the sun (by day) or the moon (at night), on a normal tilted by a few slow waves so the highlight
// breaks into a glittering path. Only where the face is open to the sky (vertex sky light 15 of 15: not under leaves or a roof) and
// not underground (the cave water has its own mirror of the point lights, ov_cave.glsl). The translucent pass also holds glass and ice;
// they get the same highlight on their top faces (provisional).
//
// OvWaterSpec  = (direction to the sun or the moon xyz, strength (0 = off; already scaled by daylight, moon phase, rain))
// OvWaterSpecB = (colour of the light rgb (gold to peach at sunset, blue-white at night), shininess)
// OvWaterSpecC = (wave slope (rain makes it rougher), time in seconds wrapped to 600, camera x and z wrapped to 1024)
uniform vec4 OvWaterSpec;
uniform vec4 OvWaterSpecB;
uniform vec4 OvWaterSpecC;

// Slope of the slow waves at camera-relative position pos (world x, z of the waves wrap every 1024 blocks and every 600 seconds,
// so every wave has a whole number of cycles in both: no seam). Wavelengths about 0.5 to 4.7 blocks, periods 0.67 to 3.8 seconds
// (nothing under half a second).
vec2 ovWaterSlope(vec3 pos) {
    vec2 w = OvWaterSpecC.zw + pos.xz;
    float t = OvWaterSpecC.y;
    const float TAU = 6.2831853;
    vec2 k1 = vec2(230.0, 70.0) * (TAU / 1024.0);
    vec2 k2 = vec2(-90.0, 200.0) * (TAU / 1024.0);
    vec2 k3 = vec2(150.0, -260.0) * (TAU / 1024.0);
    vec2 k4 = vec2(560.0, 450.0) * (TAU / 1024.0);
    float p1 = dot(k1, w) - t * (160.0 * TAU / 600.0);
    float p2 = dot(k2, w) - t * (200.0 * TAU / 600.0);
    float p3 = dot(k3, w) - t * (250.0 * TAU / 600.0);
    float p4 = dot(k4, w) - t * (500.0 * TAU / 600.0);
    // Two fine ripples (wavelength about 0.5 to 0.6 blocks, periods 0.67 and 0.86 seconds) break the path into glitter.
    vec2 k5 = vec2(1400.0, -900.0) * (TAU / 1024.0);
    vec2 k6 = vec2(-1200.0, -1700.0) * (TAU / 1024.0);
    float p5 = dot(k5, w) - t * (700.0 * TAU / 600.0);
    float p6 = dot(k6, w) - t * (900.0 * TAU / 600.0);
    vec2 s = normalize(k1) * cos(p1) + normalize(k2) * cos(p2) + normalize(k3) * cos(p3) * 0.8 + normalize(k4) * cos(p4) * 0.5
            + normalize(k5) * cos(p5) * 0.6 + normalize(k6) * cos(p6) * 0.5;
    return s / 4.4;
}

// The highlight to add to a water face (before the fog). n = face normal turned toward the camera, sky = vertex sky light 0..1,
// under = how much the fragment is underground (ov_cave.glsl), shade = 1 where the canopy map knows the face is in shade (Sodium only;
// 0 elsewhere). Returns (colour rgb, extra opacity).
vec4 ovWaterGlint(vec3 pos, vec3 n, float sky, float under, float shade) {
    if (OvWaterSpec.w <= 0.0 || n.y < 0.5) {
        return vec4(0.0);
    }
    // Sky light 15 only: Sodium reads level 14 as 14.5 / 15 (its 8..248 light coordinate over 240), so the edge sits above that.
    float open = smoothstep(14.6 / 15.0, 15.0 / 15.0, sky) * (1.0 - clamp(under, 0.0, 1.0)) * (1.0 - clamp(shade, 0.0, 1.0));
    if (open <= 0.0) {
        return vec4(0.0);
    }
    float dist = length(pos);
    vec3 v = -pos / max(dist, 1.0e-4);
    vec2 s = ovWaterSlope(pos) * OvWaterSpecC.x;
    vec3 wn = normalize(vec3(-s.x, 1.0, -s.y));
    vec3 h = normalize(OvWaterSpec.xyz + v);
    float spec = pow(max(dot(wn, h), 0.0), OvWaterSpecB.w);
    // More of the light is mirrored at grazing angles (a mild Fresnel; looking straight down still shows the sun, a little dimmer).
    float fresnel = mix(0.35, 1.0, pow(1.0 - clamp(dot(wn, v), 0.0, 1.0), 3.0));
    float k = spec * fresnel * OvWaterSpec.w * open;
    return vec4(OvWaterSpecB.rgb * k, clamp(k, 0.0, 1.0));
}
