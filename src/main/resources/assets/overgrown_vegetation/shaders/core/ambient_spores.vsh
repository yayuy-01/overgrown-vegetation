#version 150

#moj_import <fog.glsl>
#moj_import <ov_drift.glsl>
#moj_import <ov_fog.glsl>

// Overgrown Vegetation: the ambient spores of the overgrown biomes, tiny floating cubes (client/atmosphere/AmbientSpores.java
// holds the explanation). They move like the glowing spores in the light shafts (gpu_motes.vsh): pushed by the wind since
// birth (WindHist), the same swirls, which calm down slowly after a gust front has passed. Keep this file ASCII-only.
//
// Each vertex is (slot, face * 4 + corner, 0). A slot draws the 3 faces of its cube that look at the camera.
// CamMod  = (camera x mod 2048, camera y, camera z mod 2048, -)
// Clock   = (time in ticks, wind row now, slots shown, cube edge)
// Box     = (half width, height, centre above the camera, distance where the fade ends)
// Look    = (r, g, b, brightness)
// WindHist = (pushed x, z, wind strength now, what is left after a gust) at each 8-block column (client/atmosphere/WindHistory.java)
// Density = (how many spores each 8-block column keeps: overgrown biome 1, else 0; world y of the canopy top there), same
//           16 x 16 wrap as WindHist. Above the canopy top the spores fade out (no specks against the open sky).
// Sky     = (cut at the canopy top 1 / 0, how much brighter in the sun, sun map on 1 / 0, -)
// SunMap, SunA, MapA = the canopy sun map of client/atmosphere/CanopySunMap.java (same as OvSunMap, OvSunA, OvMapA of
//           ov_light.glsl): a spore in a light shaft or a sunfleck is a little brighter, a spore in the shade is not.

in vec3 Position;

uniform sampler3D WindHist;
uniform sampler2D Density;
uniform sampler2D SunMap;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec4 CamMod;
uniform vec4 Clock;
uniform vec4 Box;
uniform vec4 Look;
uniform vec4 Sky;
uniform vec4 SunA;
uniform vec4 MapA;

out vec4 vertexColor;
out float vertexDistance;
out vec4 ovFog;

uint hashU(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float rnd(inout uint s) {
    s = hashU(s);
    return float(s >> 8) * (1.0 / 16777216.0);
}

void hide() {
    gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
    vertexColor = vec4(0.0);
    vertexDistance = 0.0;
    ovFog = vec4(0.0);
}

// 1 when the spore at camera-relative pos is in the sun (light shaft, sunfleck, open sky), 0 in the canopy shade.
float sunAt(vec3 pos) {
    if (Sky.z < 0.5) {
        return 0.0;
    }
    vec2 uv = vec2(pos.x - pos.y * SunA.x, pos.z - pos.y * SunA.y) - MapA.xy - 0.5;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > MapA.z - 1.0 || uv.y > MapA.z - 1.0) {
        return 0.0;
    }
    vec4 m = texelFetch(SunMap, ivec2(floor(uv + 0.5)), 0);
    if (m.a < 0.75) {
        return 0.0;
    }
    float h = pos.y - MapA.w;
    float solidTop = m.r * 255.0;
    float leafTop = m.g * 255.0;
    if (solidTop > 0.5 && h < solidTop - 1.5) {
        return 0.0;
    }
    if (leafTop > 0.5 && m.b > 0.01 && h < leafTop - 1.0) {
        return 0.0;
    }
    return SunA.w;
}

// The copy of a point on the repeating tile (2 * half wide) nearest to 0.
vec3 wrapNear(vec3 p, vec3 tile, vec3 centre) {
    return p - tile * floor((p - centre) / tile + 0.5);
}

void main() {
    int slot = int(Position.x + 0.5);
    int code = int(Position.y + 0.5);
    int face = code / 4;
    int corner = code - face * 4;
    if (float(slot) >= Clock.z) {
        hide();
        return;
    }

    uint s = hashU(uint(slot) * 2891336453u + 1013904223u);
    float life = 240.0 + 240.0 * rnd(s);
    float tt = Clock.x + rnd(s) * life;
    float cycle = floor(tt / life);
    float age = tt - cycle * life;
    uint c = hashU(s ^ (uint(cycle) * 2654435761u + 374761393u));

    // Born somewhere on a tile anchored in the world (so the spores stay put when the camera moves), then pushed by the wind.
    vec3 tile = vec3(2.0 * Box.x, Box.y, 2.0 * Box.x);
    vec3 centre = vec3(0.0, Box.z, 0.0);
    vec3 seedPos = vec3(rnd(c), rnd(c), rnd(c)) * tile - CamMod.xyz;
    vec3 rel = wrapNear(seedPos, tile, centre);
    vec3 home = CamMod.xyz + rel;
    vec2 wuv = home.xz / 128.0;
    vec4 dn = texture(WindHist, vec3(wuv, (Clock.y + 0.5) / 1024.0));
    vec4 db = texture(WindHist, vec3(wuv, (mod(Clock.y - age, 1024.0) + 0.5) / 1024.0));
    vec2 pushXZ = dn.xy - db.xy;
    float pushed = length(pushXZ);
    rel += vec3(pushXZ.x, -0.0012 * age, pushXZ.y);
    float wc = min(max(dn.z, 0.7 * dn.w), 1.2);
    float tm = Clock.x;
    float a = 25.0 * (0.003 + 0.03 * wc);
    vec3 q = home;
    rel.x += a * sin(0.37 * q.y + 0.23 * q.z + 0.043 * tm + 1.3) * cos(0.19 * q.x - 0.031 * tm);
    rel.y += 0.6 * a * sin(0.29 * q.x + 0.17 * q.z - 0.037 * tm + 2.1) * cos(0.21 * q.y + 0.027 * tm);
    rel.z += a * sin(0.31 * q.x + 0.41 * q.y - 0.039 * tm + 0.7) * cos(0.23 * q.z + 0.033 * tm);
    // Tossed about by the air: swaying across the flow, surging in gusts (ov_drift.glsl).
    vec4 before = texture(WindHist, vec3(wuv, (mod(Clock.y - 10.0, 1024.0) + 0.5) / 1024.0));
    vec4 driftR = vec4(rnd(c), rnd(c), rnd(c), rnd(c));
    vec4 spinR = vec4(rnd(c), rnd(c), rnd(c), rnd(c));
    rel += ovDriftOffset(tm / 20.0, wc, ovFlowDir(dn, before), driftR);
    // A spore blown off the tile comes back on the other side (far away, where it is faded out).
    rel = wrapNear(rel, tile, centre);

    vec2 cell = texture(Density, (CamMod.xz + rel.xz) / 128.0).rg;
    float density = cell.r;
    // Cut at the canopy top (the CPU keeps the highest top of the 3 x 3 columns, so a gap in the canopy does not cut it).
    float ceiling = Sky.x > 0.5 ? 1.0 - smoothstep(cell.g - 2.0, cell.g, CamMod.y + rel.y) : 1.0;
    float pick = rnd(c);
    float shown = smoothstep(pick, pick + 0.15, density);
    float dist = length(rel);
    float fade = min(1.0, age / 20.0) * min(1.0, (life - age) / 30.0)
            * (1.0 - smoothstep(Box.w - 5.0, Box.w, length(rel.xz)))
            * (1.0 - smoothstep(0.35 * Box.y, 0.5 * Box.y, abs(rel.y - Box.z)))
            // A spore right in front of the eye would be a big square: fade it out closer than 1.5 blocks (like gpu_motes.vsh).
            * smoothstep(0.6, 1.5, dist) * shown * ceiling;
    if (fade <= 0.01) {
        hide();
        return;
    }

    // The face of the cube on this axis that looks at the camera. Each cube tumbles about its own random axis, faster the
    // faster the wind carries it (ov_drift.glsl).
    float edge = Clock.w * (0.7 + 0.6 * rnd(c));
    mat3 turn = ovTumble(spinR, age / 20.0, pushed);
    vec3 toCam = transpose(turn) * (-rel);
    vec3 n = vec3(0.0);
    vec3 u = vec3(0.0);
    vec3 v = vec3(0.0);
    float shade;
    if (face == 0) {
        n = vec3(sign(toCam.x), 0.0, 0.0);
        u = vec3(0.0, 1.0, 0.0);
        v = vec3(0.0, 0.0, 1.0);
        shade = 0.6;
    } else if (face == 1) {
        n = vec3(0.0, sign(toCam.y), 0.0);
        u = vec3(1.0, 0.0, 0.0);
        v = vec3(0.0, 0.0, 1.0);
        shade = toCam.y > 0.0 ? 1.0 : 0.5;
    } else {
        n = vec3(0.0, 0.0, sign(toCam.z));
        u = vec3(1.0, 0.0, 0.0);
        v = vec3(0.0, 1.0, 0.0);
        shade = 0.8;
    }
    // The face shade follows the face as it tumbles (top lit, bottom dark, like block faces).
    vec3 wn = turn * n;
    shade = 0.72 + 0.28 * wn.y - 0.1 * abs(wn.x);
    vec2 cxy = corner == 0 ? vec2(-1.0, -1.0) : (corner == 1 ? vec2(-1.0, 1.0) : (corner == 2 ? vec2(1.0, 1.0) : vec2(1.0, -1.0)));
    vec3 local = (n + u * cxy.x + v * cxy.y) * (0.5 * edge);
    vec3 pos = rel + turn * local;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    // The height fog of the overgrown forest (ov_fog.glsl, the whole ray: these do not write depth for the near ray march) and vanilla fog.
    vertexDistance = fog_distance(pos, FogShape);
    ovFog = ovFogShaded(pos, 1.0);
    // In a light shaft or a sunfleck a little brighter and warmer (far less than the glowing spores of gpu_motes.vsh).
    float sun = sunAt(rel) * Sky.y;
    vec3 rgb = Look.rgb * (shade * Look.w) * (1.0 + sun) + vec3(0.05, 0.04, 0.0) * sun;
    vertexColor = vec4(rgb, fade);
}
