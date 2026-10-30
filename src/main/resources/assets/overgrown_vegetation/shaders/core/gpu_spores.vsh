#version 150

// Overgrown Vegetation: drifting spores drawn as one static vertex buffer (client/GpuSpores.java holds the explanation).
// Keep this file ASCII-only.
//
// Each vertex is (slot, corner, 0). Everything else comes from the slot number, the time and two small 3D textures:
//   LightVol = 16x16x16 cells of 4 blocks around the camera, wrapped (REPEAT) in world space:
//              r = sky light / 15, g = block light / 15, b = spawn density (biome with drifting spores and open air), a = loaded.
//   WindHist = 16x16 columns of 8 blocks (x, z) times 1024 ticks: rg = how far the wind has pushed a spore at that column since
//              the start (blocks, accumulated on the CPU every tick), b = wind strength. Pushed now minus pushed at birth = drift.
// CamMod  = (camera x mod 2048, camera y, camera z mod 2048, -)
// Clock   = (time in ticks, wind row now = (tick - 1 + partial) mod 1024, slots to draw, underground 0..1)
// CamLeft = (camera left vector, daylight for the light shafts)
// CamUp   = (camera up vector, height where the clear high sky starts)
// Sprite  = (u0, v0, u1, v1) of minecraft:drip_fall in the particle atlas
// Shafts  = light shafts, two vec4 each: (top - camera, half width scale), (bottom - camera, presence)

#moj_import <fog.glsl>

in vec3 Position;

uniform sampler2D Sampler2;
uniform sampler3D LightVol;
uniform sampler3D WindHist;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform vec4 CamMod;
uniform vec4 Clock;
uniform vec4 CamLeft;
uniform vec4 CamUp;
uniform vec4 Sprite;
uniform vec4 Shafts[28];
uniform int ShaftCount;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;

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
    vertexDistance = 0.0;
    texCoord0 = vec2(0.0);
    vertexColor = vec4(0.0);
}

// The same test as CanopyLight.Shaft#litAt: 1 inside the shaft, 0 at 0.6 blocks outside its edge.
float litAt(vec3 p) {
    float best = 0.0;
    for (int i = 0; i < ShaftCount; i++) {
        vec4 a = Shafts[i * 2];
        vec4 b = Shafts[i * 2 + 1];
        vec3 ax = b.xyz - a.xyz;
        float len2 = dot(ax, ax);
        float t = len2 <= 1.0e-6 ? 0.0 : dot(p - a.xyz, ax) / len2;
        if (t < 0.0 || t > 1.0) {
            continue;
        }
        float d = length(p - (a.xyz + ax * t));
        float hw = a.w * (0.75 + 0.5 * t);
        best = max(best, clamp(1.0 - (d - hw) / 0.6, 0.0, 1.0) * b.w);
    }
    return best * CamLeft.w;
}

void main() {
    int slot = int(Position.x + 0.5);
    int corner = int(Position.y + 0.5);
    if (float(slot) >= Clock.z) {
        hide();
        return;
    }

    // Fixed per slot: life length, where in its life it is, a phase.
    uint s = hashU(uint(slot) * 747796405u + 2891336453u);
    float life = 500.0 + 500.0 * rnd(s);
    float tt = Clock.x + rnd(s) * life;
    float cycle = floor(tt / life);
    float age = tt - cycle * life;
    float phase = rnd(s) * 6.2831853;

    // New every life: where it was born, size, chances.
    uint c = hashU(s ^ (uint(cycle) * 2654435761u + 1013904223u));
    // Nested boxes around the camera, so spores are denser near the player like vanilla (it samples two triangular radii):
    // 19% of the slots within 8 blocks, 48% within 16, 33% within 32. The golden-ratio sequence keeps the shares for any slot count.
    float pick = fract(float(slot) * 0.61803399);
    float box = pick < 0.19 ? 16.0 : (pick < 0.67 ? 32.0 : 64.0);
    vec3 u = vec3(rnd(c), rnd(c), rnd(c));
    vec3 rel0 = mod(u * box - CamMod.xyz + 0.5 * box, box) - 0.5 * box;
    vec3 home = CamMod.xyz + rel0;

    vec4 born = texture(LightVol, home / 64.0);
    if (born.a < 0.5 || born.b <= rnd(c)) {
        hide();
        return;
    }
    if (home.y > CamUp.w + 30.0 * rnd(c)) {
        hide();
        return;
    }
    float size = 0.2 * (rnd(c) * 0.5 + 0.5) * (rnd(c) * 0.6 + 0.6);

    // Pushed by the wind since birth (read where it was born, so spores near each other move together).
    vec2 wuv = home.xz / 128.0;
    vec4 dn = texture(WindHist, vec3(wuv, (Clock.y + 0.5) / 1024.0));
    vec4 db = texture(WindHist, vec3(wuv, (mod(Clock.y - age, 1024.0) + 0.5) / 1024.0));
    vec3 p = home + vec3(dn.x - db.x, -0.0012 * age, dn.y - db.y);

    // Swirls (SporeDrift's eddy velocities turned into offsets: amplitude / angular speed).
    float wc = min(dn.z, 1.2);
    float t = Clock.x;
    float a = 25.0 * (0.003 + 0.03 * wc);
    vec3 q = p;
    p.x += a * sin(0.37 * q.y + 0.23 * q.z + 0.043 * t + 1.3) * cos(0.19 * q.x - 0.031 * t);
    p.y += 0.6 * a * sin(0.29 * q.x + 0.17 * q.z - 0.037 * t + 2.1) * cos(0.21 * q.y + 0.027 * t);
    p.z += a * sin(0.31 * q.x + 0.41 * q.y - 0.039 * t + 0.7) * cos(0.23 * q.z + 0.033 * t);
    float b = 5.0 * 0.012 * wc;
    p.x += b * sin(1.1 * q.y + 0.9 * q.z + 0.21 * t + phase);
    p.y += 0.7 * b * sin(0.95 * q.x + 1.2 * q.z - 0.19 * t + phase * 1.3);
    p.z += b * sin(1.05 * q.x + 0.85 * q.y - 0.23 * t + phase * 0.7);

    // Wrap back into the box around the camera; fade near the box edge so the wrap is never seen.
    vec3 rel = mod(p - CamMod.xyz + 0.5 * box, box) - 0.5 * box;
    vec3 ar = abs(rel);
    float edge = 1.0 - smoothstep(0.5 * box - min(4.0, 0.25 * box), 0.5 * box, max(ar.x, max(ar.y, ar.z)));
    float alpha = min(1.0, age / 20.0) * min(1.0, (life - age) / 40.0) * edge;
    if (alpha <= 0.003) {
        hide();
        return;
    }

    vec4 lv = texture(LightVol, (CamMod.xyz + rel) / 64.0);
    float sky = lv.r * 15.0;
    float blk = lv.g * 15.0;
    float lit = ShaftCount > 0 ? litAt(rel) : 0.0;
    float glow = 12.0 * lit;
    blk = max(blk, glow);
    sky = max(sky, glow);
    // Underground only the spores near a light show (dark ones turned into black squares over the cave mist).
    float seen = clamp(max(blk, sky) / 11.0, 0.0, 1.0);
    alpha *= mix(1.0, seen, Clock.w);

    vec3 base = vec3(0.32, 0.5, 0.22);
    vec3 lightCol = vec3(0.62, 0.78, 0.36);
    vec3 col = mix(base, lightCol, lit);
    vec4 lm = texture(Sampler2, clamp((vec2(blk, sky) + 0.5) / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));

    vec2 cxy;
    vec2 uv;
    if (corner == 0) {
        cxy = vec2(-1.0, -1.0);
        uv = Sprite.zw;
    } else if (corner == 1) {
        cxy = vec2(-1.0, 1.0);
        uv = vec2(Sprite.z, Sprite.y);
    } else if (corner == 2) {
        cxy = vec2(1.0, 1.0);
        uv = Sprite.xy;
    } else {
        cxy = vec2(1.0, -1.0);
        uv = vec2(Sprite.x, Sprite.w);
    }
    vec3 pos = rel + (CamLeft.xyz * cxy.x + CamUp.xyz * cxy.y) * size;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    vertexDistance = fog_distance(pos, FogShape);
    texCoord0 = uv;
    vertexColor = vec4(col * lm.rgb, alpha);
}
