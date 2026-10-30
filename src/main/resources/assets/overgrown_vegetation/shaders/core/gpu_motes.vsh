#version 150

// Overgrown Vegetation: glowing spores inside the light shafts, drawn as one static vertex buffer
// (client/atmosphere/ShaftMotes.java and LightShafts.java hold the explanation). Keep this file ASCII-only.
//
// Each vertex is (slot, corner, 0). Slot / 64 is the shaft group; each group gets 3 vec4 in Groups:
//   (top - camera, spawn half width), (bottom - camera, presence), (tMin, share of slots shown, lit half width scale, thin)
// CamMod  = (camera x mod 2048, camera y, camera z mod 2048, -)
// Clock   = (time in ticks, wind row now, -, -)
// CamLeft / CamUp = camera vectors.  Sun = (light direction, daylight).  Glow = (r, g, b, intensity)
// WindHist = (how far the wind has pushed a spore x, z, wind strength now, what is left after a gust) at each 8-block column
// (client/atmosphere/WindHistory.java).

in vec3 Position;

uniform sampler3D WindHist;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 CamMod;
uniform vec4 Clock;
uniform vec4 CamLeft;
uniform vec4 CamUp;
uniform vec4 Sun;
uniform vec4 Glow;
// Phase = (light seen from behind, extra toward the light, power, -) of SunLight#phase.
uniform vec4 Phase;
uniform vec4 Groups[42];
uniform int GroupCount;

out vec2 local;
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
    local = vec2(0.0);
    vertexColor = vec4(0.0);
}

void main() {
    int slot = int(Position.x + 0.5);
    int corner = int(Position.y + 0.5);
    int group = slot / 64;
    if (group >= GroupCount) {
        hide();
        return;
    }
    vec4 g0 = Groups[group * 3];
    vec4 g1 = Groups[group * 3 + 1];
    vec4 g2 = Groups[group * 3 + 2];
    if (g1.w <= 0.0) {
        hide();
        return;
    }

    uint s = hashU(uint(slot) * 747796405u + 1442695041u);
    float life = 90.0 + 110.0 * rnd(s);
    float tt = Clock.x + rnd(s) * life;
    float cycle = floor(tt / life);
    float age = tt - cycle * life;
    float phase = rnd(s) * 6.2831853;

    uint c = hashU(s ^ (uint(cycle) * 2654435761u + 374761393u));
    // Fewer slots shown for short / thin shafts (the CPU spawned fewer); a soft edge instead of popping when the share changes.
    float pick = rnd(c);
    float shown = smoothstep(pick, pick + 0.08, g2.y);
    if (shown <= 0.0) {
        hide();
        return;
    }
    float t = g2.x + rnd(c) * (1.0 - g2.x);
    float hw = g0.w * (0.75 + 0.5 * t);
    vec3 born = mix(g0.xyz, g1.xyz, t) + (vec3(rnd(c), rnd(c), rnd(c)) * 2.0 - 1.0) * hw;
    float size = 0.025 + rnd(c) * 0.04;

    // Pushed by the wind since birth, plus the same swirls as the drifting spores.
    vec3 home = CamMod.xyz + born;
    vec2 wuv = home.xz / 128.0;
    vec4 dn = texture(WindHist, vec3(wuv, (Clock.y + 0.5) / 1024.0));
    vec4 db = texture(WindHist, vec3(wuv, (mod(Clock.y - age, 1024.0) + 0.5) / 1024.0));
    vec3 rel = born + vec3(dn.x - db.x, -0.0012 * age, dn.y - db.y);
    // The swirls follow the wind now, and after a gust front has passed they calm down slowly (dn.w = what is left of it).
    float wc = min(max(dn.z, 0.7 * dn.w), 1.2);
    float tm = Clock.x;
    float a = 25.0 * (0.003 + 0.03 * wc);
    vec3 q = home;
    rel.x += a * sin(0.37 * q.y + 0.23 * q.z + 0.043 * tm + 1.3) * cos(0.19 * q.x - 0.031 * tm);
    rel.y += 0.6 * a * sin(0.29 * q.x + 0.17 * q.z - 0.037 * tm + 2.1) * cos(0.21 * q.y + 0.027 * tm);
    rel.z += a * sin(0.31 * q.x + 0.41 * q.y - 0.039 * tm + 0.7) * cos(0.23 * q.z + 0.033 * tm);

    // Lit only while inside its own shaft (LightShafts.Shaft).
    vec3 ax = g1.xyz - g0.xyz;
    float len2 = dot(ax, ax);
    float st = len2 <= 1.0e-6 ? 0.0 : dot(rel - g0.xyz, ax) / len2;
    float lit = 0.0;
    if (st >= 0.0 && st <= 1.0) {
        float d = length(rel - (g0.xyz + ax * st));
        float half2 = g2.z * (0.75 + 0.5 * st);
        lit = clamp(1.0 - (d - half2) / 0.6, 0.0, 1.0) * g1.w;
    }
    // Brighter looking toward the light (SunLight#phase).
    float dist = length(rel);
    float ph = 1.0;
    if (lit > 0.0 && dist > 1.0e-5) {
        float cs = dot(rel, Sun.xyz) / dist;
        float qq = clamp((1.0 + cs) * 0.5, 0.0, 1.0);
        ph = Phase.x + Phase.y * pow(qq, Phase.z);
    }
    float fade = min(1.0, age / 15.0) * min(1.0, (life - age) / 30.0);
    float twinkle = 0.7 + 0.3 * sin(tm * 0.25 + phase);
    float glow = (0.04 + 0.96 * lit * ph) * fade * twinkle * Glow.w * max(Sun.w, 0.15) * shown;
    // A spore right in front of the eye would be a huge out-of-focus blob: fade it out closer than 1.5 blocks.
    glow *= smoothstep(0.6, 1.5, dist);
    if (glow <= 0.01 || dist > 40.0) {
        hide();
        return;
    }
    size *= 0.7 + 0.6 * lit;

    vec2 cxy;
    if (corner == 0) {
        cxy = vec2(-1.0, -1.0);
    } else if (corner == 1) {
        cxy = vec2(-1.0, 1.0);
    } else if (corner == 2) {
        cxy = vec2(1.0, 1.0);
    } else {
        cxy = vec2(1.0, -1.0);
    }
    vec3 pos = rel + (CamLeft.xyz * cxy.x + CamUp.xyz * cxy.y) * size;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    local = cxy;
    vertexColor = vec4(Glow.rgb, min(1.0, 0.95 * glow));
}
