#version 150

#moj_import <fog.glsl>
#moj_import <ov_drift.glsl>
#moj_import <ov_fog.glsl>

// Overgrown Vegetation: the floating motes of light at dusk (client/atmosphere/DuskMotes.java holds the explanation). A few small
// soft points drift slowly 1 to 3 blocks above the forest floor and pulse slowly; they are added light (only the motes glow,
// never the leaves). Same world-anchored repeating tile and the same sway in the air as the ambient spores (ambient_spores.vsh,
// ov_drift.glsl), but slower and lower. Keep this file ASCII-only.
//
// Each vertex is (slot, corner, 0).
// CamMod  = (camera x mod 2048, camera y, camera z mod 2048, -)
// Clock   = (time in ticks, wind row now, slots shown, point size in blocks)
// Box     = (half width of the tile, lowest height above the floor, highest height above the floor, distance where the fade ends)
// Look    = (warm colour rgb, brightness)
// Look2   = (cool colour rgb, pulse period in ticks)
// CamLeft, CamUp = the camera's left and up vectors (the points face the camera)
// Floor   = (overgrown biome 1 / 0, world y of the forest floor) at each 8-block column, 16 x 16 wrapped (like Density of the
//           ambient spores). WindHist = the wind history (client/atmosphere/WindHistory.java).

in vec3 Position;

uniform sampler3D WindHist;
uniform sampler2D Floor;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec4 CamMod;
uniform vec4 Clock;
uniform vec4 Box;
uniform vec4 Look;
uniform vec4 Look2;
uniform vec4 CamLeft;
uniform vec4 CamUp;

out vec2 local;
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
    local = vec2(0.0);
    vertexColor = vec4(0.0);
    vertexDistance = 0.0;
    ovFog = vec4(0.0);
}

vec3 wrapNear(vec3 p, vec3 tile, vec3 centre) {
    return p - tile * floor((p - centre) / tile + 0.5);
}

void main() {
    int slot = int(Position.x + 0.5);
    int corner = int(Position.y + 0.5);
    if (float(slot) >= Clock.z) {
        hide();
        return;
    }
    uint s = hashU(uint(slot) * 2654435761u + 40503u);
    float life = 600.0 + 600.0 * rnd(s);
    float tt = Clock.x + rnd(s) * life;
    float cycle = floor(tt / life);
    float age = tt - cycle * life;
    uint c = hashU(s ^ (uint(cycle) * 2246822519u + 3266489917u));

    // Born somewhere on a horizontal tile anchored in the world, at a height above the forest floor of its column.
    float w = 2.0 * Box.x;
    vec2 seed = vec2(rnd(c), rnd(c)) * w - CamMod.xz;
    vec2 relXZ = seed - w * floor(seed / w + 0.5);
    vec2 homeXZ = CamMod.xz + relXZ;
    vec2 cell = texture(Floor, homeXZ / 128.0).rg;
    if (cell.r < 0.05) {
        hide();
        return;
    }
    float lift = mix(Box.y, Box.z, rnd(c));
    vec3 rel = vec3(relXZ.x, cell.g + lift - CamMod.y, relXZ.y);

    // A slow wander of its own (an insect, not a speck carried by the air), plus the sway of the air (ov_drift.glsl) at a third.
    float tm = Clock.x / 20.0;
    float p1 = rnd(c) * 6.2831853;
    float p2 = rnd(c) * 6.2831853;
    rel.x += 0.9 * sin(0.21 * tm + p1) + 0.4 * sin(0.53 * tm + p2 * 1.7);
    rel.z += 0.9 * cos(0.17 * tm + p2) + 0.4 * sin(0.47 * tm + p1 * 2.3);
    rel.y += 0.35 * sin(0.29 * tm + p1 * 0.7) + 0.15 * sin(0.71 * tm + p2);
    vec2 wuv = homeXZ / 128.0;
    vec4 dn = texture(WindHist, vec3(wuv, (Clock.y + 0.5) / 1024.0));
    vec4 before = texture(WindHist, vec3(wuv, (mod(Clock.y - 10.0, 1024.0) + 0.5) / 1024.0));
    float wc = min(max(dn.z, 0.7 * dn.w), 1.2);
    vec4 driftR = vec4(rnd(c), rnd(c), rnd(c), rnd(c));
    rel += 0.35 * ovDriftOffset(tm, wc, ovFlowDir(dn, before), driftR);

    // The slow pulse: dark between flashes, rising and falling smoothly (no sharp blink).
    float ph = rnd(c);
    float wave = 0.5 + 0.5 * sin(6.2831853 * (Clock.x / (Look2.w * (0.75 + 0.5 * rnd(c))) + ph));
    float pulse = 0.12 + 0.88 * wave * wave * wave;
    float dist = length(rel);
    float fade = min(1.0, age / 60.0) * min(1.0, (life - age) / 60.0)
            * (1.0 - smoothstep(Box.w - 4.0, Box.w, length(rel.xz)))
            * smoothstep(0.8, 2.0, dist) * smoothstep(0.05, 0.4, cell.r);
    float glow = fade * pulse;
    if (glow <= 0.01) {
        hide();
        return;
    }
    vec2 cxy = corner == 0 ? vec2(-1.0, -1.0) : (corner == 1 ? vec2(-1.0, 1.0) : (corner == 2 ? vec2(1.0, 1.0) : vec2(1.0, -1.0)));
    float size = Clock.w * (0.8 + 0.4 * rnd(c)) * (0.8 + 0.4 * wave);
    vec3 pos = rel + (CamLeft.xyz * cxy.x + CamUp.xyz * cxy.y) * size;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    ovFog = ovFogShaded(pos, 1.0);
    local = cxy;
    vec3 rgb = mix(Look.rgb, Look2.rgb, rnd(c));
    vertexColor = vec4(rgb, min(1.0, glow * Look.w));
}
