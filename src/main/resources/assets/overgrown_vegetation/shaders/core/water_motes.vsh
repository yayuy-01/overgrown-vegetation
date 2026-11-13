#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the motes floating in the water, tiny cubes like the ambient spores on land (client/atmosphere/WaterMotes.java
// holds the explanation). They drift with the common water current, sink slowly and wander a little. Only inside the water: the
// water depth map (client/atmosphere/WaterDepth.java) gives the surface and the bottom of each block column. Keep this file ASCII-only.
//
// Each vertex is (slot, face * 4 + corner, 0). A slot draws the 3 faces of its cube that look at the camera.
// CamMod = (camera x mod 2048, camera y, camera z mod 2048, -)
// Clock  = (time in ticks, -, slots shown, cube edge)
// Box    = (half width, half height of the repeating tile, share of motes near the bottom, height of that bottom band)
// Flow   = (current x, current z in blocks per tick, sinking in blocks per tick, -)
// Look   = (brightness, share of the polluted colours, -, -)
// Spore, Peat, Algae = polluted colours (rgb, share; spore takes the rest), Clear = colour in clean water
// DepthA = (map origin x - camera x, map origin z - camera z, map size, map base y - camera y)
// DepthMap texel: r = depth * 8 / 255, b = (surface block y - base y + 1) / 255, a >= 0.5 for a water column

in vec3 Position;

uniform sampler2D DepthMap;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec4 CamMod;
uniform vec4 Clock;
uniform vec4 Box;
uniform vec4 Flow;
uniform vec4 Look;
uniform vec4 Spore;
uniform vec4 Peat;
uniform vec4 Algae;
uniform vec4 Clear;
uniform vec4 DepthA;

out vec4 vertexColor;
out float vertexDistance;

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
}

// The water column at a camera-relative x, z: (bottom y, top y of the water, 1 when the bottom is known, 1 when it is water).
vec4 column(vec2 rxz) {
    vec2 uv = floor(rxz - DepthA.xy);
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x >= DepthA.z || uv.y >= DepthA.z) {
        return vec4(0.0);
    }
    vec4 m = texelFetch(DepthMap, ivec2(uv), 0);
    if (m.a < 0.5) {
        return vec4(0.0);
    }
    float surface = floor(m.b * 255.0 + 0.5) - 1.0 + DepthA.w;
    float depth = floor(m.r * 255.0 / 8.0 + 0.5);
    return vec4(surface - depth + 1.0, surface + 0.85, depth < 30.5 ? 1.0 : 0.0, 1.0);
}

mat3 turnAbout(vec3 axis, float angle) {
    float sn = sin(angle);
    float cs = cos(angle);
    float t = 1.0 - cs;
    vec3 a = axis;
    return mat3(t * a.x * a.x + cs, t * a.x * a.y + sn * a.z, t * a.x * a.z - sn * a.y,
                t * a.x * a.y - sn * a.z, t * a.y * a.y + cs, t * a.y * a.z + sn * a.x,
                t * a.x * a.z + sn * a.y, t * a.y * a.z - sn * a.x, t * a.z * a.z + cs);
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
    float life = 300.0 + 300.0 * rnd(s);
    float tt = Clock.x + rnd(s) * life;
    float cycle = floor(tt / life);
    float age = tt - cycle * life;
    uint c = hashU(s ^ (uint(cycle) * 2654435761u + 374761393u));

    // Born somewhere on a tile anchored in the world (the motes stay put when the camera moves).
    float tw = 2.0 * Box.x;
    vec2 seed = vec2(rnd(c), rnd(c)) * tw;
    vec2 relXZ = seed - CamMod.xz;
    relXZ -= tw * floor(relXZ / tw + 0.5);
    vec4 home = column(relXZ);
    if (home.w < 0.5) {
        hide();
        return;
    }
    float yr = rnd(c);
    float relY;
    // Mud motes lie thick near the bottom; the rest fill a repeating tile in height.
    if (rnd(c) < Box.z && home.z > 0.5) {
        relY = home.x + 0.05 + Box.w * yr * yr;
    } else {
        float th = 2.0 * Box.y;
        relY = yr * th - CamMod.y;
        relY -= th * floor(relY / th + 0.5);
    }
    vec3 rel = vec3(relXZ.x, relY, relXZ.y);

    // Carried by the current, sinking slowly, wandering a little.
    rel.xz += Flow.xy * age;
    rel.y -= Flow.z * age;
    float tm = Clock.x;
    float ph = rnd(c) * 6.2832;
    vec3 q = vec3(seed.x, relY + CamMod.y, seed.y);
    rel.x += 0.12 * sin(0.37 * q.y + 0.23 * q.z + 0.031 * tm + ph);
    rel.y += 0.06 * sin(0.29 * q.x + 0.17 * q.z - 0.027 * tm + 2.1 + ph);
    rel.z += 0.12 * sin(0.31 * q.x + 0.41 * q.y - 0.029 * tm + 0.7 + ph);
    // A mote carried off the tile comes back on the other side (far away, where it is faded out).
    rel.xz -= tw * floor(rel.xz / tw + 0.5);

    vec4 col = column(rel.xz);
    if (col.w < 0.5) {
        hide();
        return;
    }
    // A sinking mote settles on the bottom instead of going through it.
    if (col.z > 0.5) {
        rel.y = max(rel.y, col.x + 0.04);
    }
    float inWater = 1.0 - smoothstep(col.y - 0.6, col.y - 0.1, rel.y);
    float dist = length(rel);
    float fade = min(1.0, age / 30.0) * min(1.0, (life - age) / 40.0)
            * (1.0 - smoothstep(Box.x - 5.0, Box.x - 1.0, length(rel.xz)))
            * (1.0 - smoothstep(Box.y - 3.0, Box.y, abs(rel.y)))
            // A mote right in front of the eye would be a big square.
            * smoothstep(0.9, 2.0, dist) * inWater;
    if (fade <= 0.01) {
        hide();
        return;
    }

    // Colour: in polluted water spores, peat and algae; in clean water a few pale specks.
    vec3 rgb;
    float size = 1.0;
    if (rnd(c) < Look.y) {
        float k = rnd(c);
        if (k < Peat.w) {
            rgb = Peat.rgb;
            size = 1.25;
        } else if (k < Peat.w + Algae.w) {
            rgb = Algae.rgb;
            size = 0.8;
        } else {
            rgb = Spore.rgb;
        }
    } else {
        rgb = Clear.rgb;
        size = 0.8;
    }

    // Each cube turns slowly about its own random axis.
    vec3 axis = normalize(vec3(rnd(c), rnd(c), rnd(c)) * 2.0 - 1.0 + vec3(0.0, 0.001, 0.0));
    mat3 turn = turnAbout(axis, age * (0.01 + 0.02 * rnd(c)) + ph);
    float edge = Clock.w * size * (0.7 + 0.6 * rnd(c));
    vec3 toCam = transpose(turn) * (-rel);
    vec3 n;
    vec3 u;
    vec3 v;
    if (face == 0) {
        n = vec3(sign(toCam.x), 0.0, 0.0);
        u = vec3(0.0, 1.0, 0.0);
        v = vec3(0.0, 0.0, 1.0);
    } else if (face == 1) {
        n = vec3(0.0, sign(toCam.y), 0.0);
        u = vec3(1.0, 0.0, 0.0);
        v = vec3(0.0, 0.0, 1.0);
    } else {
        n = vec3(0.0, 0.0, sign(toCam.z));
        u = vec3(1.0, 0.0, 0.0);
        v = vec3(0.0, 1.0, 0.0);
    }
    vec3 wn = turn * n;
    float shade = 0.72 + 0.28 * wn.y - 0.1 * abs(wn.x);
    vec2 cxy = corner == 0 ? vec2(-1.0, -1.0) : (corner == 1 ? vec2(-1.0, 1.0) : (corner == 2 ? vec2(1.0, 1.0) : vec2(1.0, -1.0)));
    vec3 local = (n + u * cxy.x + v * cxy.y) * (0.5 * edge);
    vec3 pos = rel + turn * local;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    // Darker the deeper below the surface.
    float dark = mix(1.0, 0.55, smoothstep(3.0, 28.0, col.y - rel.y));
    vertexColor = vec4(rgb * (shade * Look.x * dark), 0.85 * fade);
}
