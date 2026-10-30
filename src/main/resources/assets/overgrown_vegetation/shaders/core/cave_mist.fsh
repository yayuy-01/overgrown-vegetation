#version 150

// CaveMist (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).
//
// MistColor = (colour of the mist in the dark (the faint glow of the spores, by depth) rgb, alpha of one sheet)
// MistGlow  = (colour added where block light (glowing plants, torches) reaches the mist, rgb; extra alpha when seen from high above)
// MistSky   = (colour added where sky light (a hole in the roof, a cave mouth) reaches the mist, rgb; unused)
// MistNoise = (noise offset x, noise offset z (the camera in noise units, wrapped to 64), time in seconds, unused)
// MistFade  = (near fade start, near fade end (blocks), camera height above the sheet where it starts to show, where it fully shows)

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform vec4 MistColor;
uniform vec4 MistGlow;
uniform vec4 MistSky;
uniform vec4 MistNoise;
uniform vec4 MistFade;

in vec3 relPos;
in vec4 vertexColor;

out vec4 fragColor;

// Blocks per noise cell: cave mist lies in smaller wisps than the morning mist over the forest.
const float NOISE_SCALE = 7.0;

float mistHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float mistNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = mistHash(i);
    float b = mistHash(i + vec2(1.0, 0.0));
    float c = mistHash(i + vec2(0.0, 1.0));
    float d = mistHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float mistFbm(vec2 p) {
    return mistNoise(p) * 0.55 + mistNoise(p * 2.03 + 17.0) * 0.3 + mistNoise(p * 4.07 + 41.0) * 0.15;
}

void main() {
    float w = vertexColor.a;
    if (w <= 0.003) {
        discard;
    }
    float slice = vertexColor.r;
    float dist = length(relPos);
    // Sheets close to the camera, and sheets seen edge-on, show as flat planes (their cut edges look like glass): fade
    // them out. The sheets are only drawn when the camera is above the layer; inside it the terrain haze
    // (ov_cave_mist.glsl) shows the mist.
    // The sheets bend over slopes and pits, so "edge-on" is measured against the sheet's own surface (its normal from
    // the screen-space derivatives), not against the horizontal.
    float near = smoothstep(MistFade.x, MistFade.y, dist);
    vec3 dir = relPos / max(dist, 0.001);
    vec3 tilt = cross(dFdx(relPos), dFdy(relPos));
    float facing = length(tilt) > 1e-8 ? abs(dot(normalize(tilt), dir)) : abs(dir.y);
    float grazing = smoothstep(0.06, 0.26, facing);
    // Only sheets well below the camera are drawn: down in the mist (or level with it) they would be seen edge-on or from
    // below; there the terrain haze (ov_cave_mist.glsl) shows the mist. Decided per point of the sheet, so the mist pouring
    // down to a lower floor stays visible while the camera walks up to it.
    float below = -relPos.y;
    float fromAbove = smoothstep(MistFade.z, MistFade.w, below);
    if (near * grazing * fromAbove <= 0.0) {
        discard;
    }
    // Patchy wisps that drift slowly; deeper sheets are fuller (hollows are filled).
    vec2 drift = vec2(MistNoise.z * 0.04, MistNoise.z * 0.017);
    vec2 p = relPos.xz / NOISE_SCALE + MistNoise.xy + drift + vec2(slice * 3.7, slice * 1.3);
    float n = mistFbm(p);
    float from = mix(0.5, 0.26, slice);
    float cloud = smoothstep(from, from + 0.32, n);
    // Lit mist: block light (glowing plants, ley veins, torches) and sky light (a cave mouth) brighten the wisps there.
    float lb = vertexColor.g * vertexColor.g;
    float ls = vertexColor.b * vertexColor.b;
    float lit = max(lb, ls);
    // Seen from high above, the sheets are seen at a steep angle and look thin: draw them a little denser there.
    float boost = 1.0 + MistGlow.w * smoothstep(MistFade.w, MistFade.w + 6.0, below);
    float a = MistColor.a * w * cloud * near * grazing * fromAbove * boost * (1.0 + 0.8 * lit);
    a = min(a, 0.85);
    if (a <= 0.002) {
        discard;
    }
    vec3 col = MistColor.rgb * mix(1.0, 0.8, slice) + MistGlow.rgb * lb + MistSky.rgb * ls;
    col = min(col, vec3(1.0));
    float fd = FogShape == 0 ? dist : max(length(relPos.xz), abs(relPos.y));
    float fogValue = fd < FogEnd ? smoothstep(FogStart, FogEnd, fd) : 1.0;
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);
    fragColor = vec4(col, a * (1.0 - 0.6 * fogValue)) * ColorModulator;
}
