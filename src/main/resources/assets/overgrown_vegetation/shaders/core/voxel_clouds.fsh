#version 150

// Overgrown Vegetation: voxel clouds (client/VoxelClouds.java holds the explanation). Keep this file ASCII-only.
// CloudColor = vanilla cloud colour (dark at night, grey in rain). SunDir = (light direction, strength; the moon at night).
// SunColor = light colour (orange at a low sun, blue-white for the moon). Fade = (start, end) of the horizontal fade-out,
// (z, w) = horizontal sun direction. Glow = vanilla sunrise/sunset colour and strength.
//
// Vertex colour: r = (face * 16 + convex-edge bits) / 255 (face 0 down, 1 up, 2 north, 3 south, 4 west, 5 east; bit 1/2/4/8 = the edge
// a-b / b-c / c-d / d-a in vertex order is a convex edge of the cloud), g = height within the layer (storms: baked brightness),
// b = thinness, a = 0.5 low layer / 0.65 cloud sea / 0.8 giant storm / 1 storm. UV = position on the face (a = 0,0, b = 1,0, c = 1,1, d = 0,1).

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform vec4 CloudColor;
uniform vec4 SunDir;
uniform vec4 SunColor;
uniform vec4 SkyColor;
uniform vec4 Fade;
uniform vec4 Glow;
// Fade-out of storm clouds: (start, end) for storms (vertex alpha 1), (z, w) for giant storms and their cloud sea (0.8 / 0.65);
// the low layer (vertex alpha 0.5) uses Fade. For storms the vertex g is a baked brightness (height and overhangs).
uniform vec4 StormFade;
// Split: see voxel_clouds.vsh.
uniform vec4 Split;
// Lightning inside the storm clouds (client/Thunderstorms.java): position relative to the camera, intensity (0 = none).
uniform vec4 Flash0;
uniform vec4 Flash1;
uniform vec4 Flash2;
// Camera inside a cloud (client/CloudInterior.java): x = fog far (blocks), y = how deep inside (0 = outside), z = strength of the grey
// silhouettes of storm clouds seen through the fog. InsideColor = fog colour.
uniform vec4 InsideFog;
uniform vec4 InsideColor;
// Look: x = cel shading (0..1), y = soft edges (0..1), z = sunset tops (0..1), w = 1 in the depth-only pass.
uniform vec4 Look;
// World: x = camera y.
uniform vec4 World;
// PreRain = (base drop in blocks, darkening 0..1, -, -): before a scheduled rain (11-09, clouds stage 2a-6). The drop is done in voxel_clouds.vsh.
uniform vec4 PreRain;
// Storm shadow map (client/CloudSelfShadow.java): ShadowA = (map origin x, z relative to the camera, map span, strength),
// ShadowB = (sun kx, kz of the map, plane height, height range). Value = (first height inside a storm along the sun line - 300) / range.
uniform vec4 ShadowA;
uniform vec4 ShadowB;
// Rough = (clear-weather cloud colour of the time of day, rough weather 0..1: real rain counts 0.6, thunder 1).
uniform vec4 Rough;
// Field = (camera position in field coordinates (moving with the clouds) modulo 32768, wind direction x, z).
uniform vec4 Field;
// Look2: x = wind streaks (0..1), y = blue haze of far clouds (0..1), z = brush mottling and the other texture touches (0..1).
uniform vec4 Look2;
// Holes punched by shells and beams (client/CloudHoles.java): two holes per matrix, columns A = (start relative to the camera,
// radius), B = (end relative to the camera, glow of the cut edge). Radius 0 = no hole. HoleGlow = colour of the glowing edge.
uniform mat4 Holes0;
uniform mat4 Holes1;
uniform mat4 Holes2;
uniform mat4 Holes3;
uniform vec4 HoleGlow;
// Far ring above the clouds (client/VoxelClouds.java): vertex alpha 0.3 = far cloud sea, 0.15 = far storm. Far = (fade start, fade end,
// alpha, cut). While cut is on, the near cloud floor is dropped outside FarHole (min x, min z, max x, max z relative to the camera):
// the far ring draws it there.
uniform vec4 Far;
uniform vec4 FarHole;
// Puffed clouds (client/CloudPuffs.java): Puff = (scale, opacity, -, 1 = puff mesh). While fading they write no depth, so the clouds behind stay.
uniform vec4 Puff;
// HorizonColor = (fog colour at the horizon, 1 = in use). 11-08 (clouds stage 1, improvements 3 and 4): far clouds and the grey ceiling
// of an overcast day melt into the horizon colour, and at night far cloud bases no longer read as black holes.
// World.y = cloud cover (0..1) for the overcast ceiling.
uniform vec4 HorizonColor;
// Curve.w = horizon haze 0..1 (11-18 trial): clouds low over the horizon melt into a pale haze (voxel_clouds.vsh has the rest).
uniform vec4 Curve;
// Time-of-day tints (11-08, design sky.json clouds.lit*/shade*, provisional): LitTint multiplies the lit tone, ShadeTint the shadow tone
// (white by day; peach light and lavender shade at dusk by default). World.z = flatness of the cloud floor (0..1).
uniform vec4 LitTint;
uniform vec4 ShadeTint;
// Dissolve = (progress 0..1, mode, -, -). 11-12 (user report: clouds and their shadows jumped every few seconds): a rebuilt cloud mesh no
// longer replaces the old one in a single frame. For a moment both are drawn: the new mesh (mode 1) keeps the points whose noise is
// below the progress, the old one (mode -1) the rest. The noise sits in field coordinates, so the change spreads over the clouds like
// billowing, and where both meshes have the same face exactly one of them draws each point (no flicker). Mode 0 = off.
uniform vec4 Dissolve;
// The haze of the overgrown forest (11-13; client/atmosphere/CloudHaze.java). Terrain, mobs and particles fade into the spore haze through
// the fog mark of MistView (fog.glsl), but these clouds are drawn by their own shader and stood crisp and blue-white in a sky filled with
// the yellow-green haze. ForestHaze = (colour of the haze-filled sky, the share of the sky dome filled with it 0..1),
// ForestHazeK.x = haze per block at the camera. A cloud at distance d keeps (1 - share) * exp(-k d) of itself (it takes the haze colour and
// thins out by the rest); both 0 outside the forest.
uniform vec4 ForestHaze;
uniform vec4 ForestHazeK;

in vec3 ovPos;
in vec4 ovInfo;
in vec2 ovUv;
in float ovBentY;

out vec4 fragColor;

vec3 ovFlash(vec4 f) {
    if (f.w <= 0.0) {
        return vec3(0.0);
    }
    vec3 d = ovPos - f.xyz;
    float g = exp(-dot(d, d) / (2.0 * 220.0 * 220.0));
    return vec3(0.82, 0.86, 1.0) * (f.w * g * 0.6);
}

// Storm nest (11-18): the lightning lights the dark walls wide and strong, brighter on the faces turned towards it, so the shape of
// the wall stands out for the moment of the flash.
float ovFlashNest(vec4 f, vec3 nrm) {
    if (f.w <= 0.0) {
        return 0.0;
    }
    vec3 d = f.xyz - ovPos;
    float g = exp(-dot(d, d) / (2.0 * 700.0 * 700.0));
    float facing = 0.3 + 0.7 * max(dot(nrm, normalize(d)), 0.0);
    return f.w * g * facing * 0.45;
}

// Direction from the face centre towards its edge e (0: a-b, 1: b-c, 2: c-d, 3: d-a in vertex order; client/VoxelClouds.java uses the
// same vertex order for every face of a direction). The face beyond a convex edge faces this way.
vec3 ovEdgeDir(int face, int e) {
    if (face == 0) return e == 0 ? vec3(0.0, 0.0, -1.0) : e == 1 ? vec3(1.0, 0.0, 0.0) : e == 2 ? vec3(0.0, 0.0, 1.0) : vec3(-1.0, 0.0, 0.0);
    if (face == 1) return e == 0 ? vec3(0.0, 0.0, 1.0) : e == 1 ? vec3(1.0, 0.0, 0.0) : e == 2 ? vec3(0.0, 0.0, -1.0) : vec3(-1.0, 0.0, 0.0);
    if (face == 2) return e == 0 ? vec3(0.0, -1.0, 0.0) : e == 1 ? vec3(-1.0, 0.0, 0.0) : e == 2 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    if (face == 3) return e == 0 ? vec3(0.0, -1.0, 0.0) : e == 1 ? vec3(1.0, 0.0, 0.0) : e == 2 ? vec3(0.0, 1.0, 0.0) : vec3(-1.0, 0.0, 0.0);
    if (face == 4) return e == 0 ? vec3(0.0, -1.0, 0.0) : e == 1 ? vec3(0.0, 0.0, 1.0) : e == 2 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, -1.0);
    return e == 0 ? vec3(0.0, -1.0, 0.0) : e == 1 ? vec3(0.0, 0.0, -1.0) : e == 2 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
}

// Distance (0..1 across the face) to a convex edge that lies on the outline as seen from the camera: the face around the edge is
// turned away. Edges in front of the cloud body are pushed out of reach (softening them would show the sky through the cloud).
float ovOutlineDist(int face, int bits, int e, float d, vec3 view) {
    if ((bits & (1 << e)) == 0) {
        return 2.0;
    }
    float away = smoothstep(0.0, 0.2, dot(ovEdgeDir(face, e), view));
    return d + (1.0 - away) * 2.0;
}

float ovHash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// Smooth value noise, 0..1.
float ovNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = ovHash(i);
    float b = ovHash(i + vec2(1.0, 0.0));
    float c = ovHash(i + vec2(0.0, 1.0));
    float e = ovHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, e, f.x), f.y);
}

// Distance from p to the segment a-b.
float ovCapsule(vec3 p, vec3 a, vec3 b) {
    vec3 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 0.0001), 0.0, 1.0);
    return length(p - a - ab * t);
}

// Cuts the hole out of the cloud (discard) with a slightly torn edge; returns the edge band (x = glow, y = dark lip) for the colour.
vec2 ovHole(vec4 a, vec4 b, float torn) {
    if (a.w <= 0.5) {
        return vec2(0.0);
    }
    float r = a.w * (1.0 + 0.16 * (torn - 0.5));
    float dd = ovCapsule(ovPos, a.xyz, b.xyz) - r;
    if (dd < 0.0) {
        discard;
    }
    return vec2(b.w * (1.0 - smoothstep(0.0, 7.0, dd)), 1.0 - smoothstep(0.0, 5.0, dd));
}

vec3 ovFaceNormal(int i) {
    if (i == 0) return vec3(0.0, -1.0, 0.0);
    if (i == 1) return vec3(0.0, 1.0, 0.0);
    if (i == 2) return vec3(0.0, 0.0, -1.0);
    if (i == 3) return vec3(0.0, 0.0, 1.0);
    if (i == 4) return vec3(-1.0, 0.0, 0.0);
    return vec3(1.0, 0.0, 0.0);
}

// 1 = the sun is blocked by a storm cloud above this point (0 = lit).
float ovStormShadow(float worldY, float bias) {
    if (ShadowA.w <= 0.001) {
        return 0.0;
    }
    vec2 q = ovPos.xz + ShadowB.xy * (ShadowB.z - worldY);
    vec2 uv = (q - ShadowA.xy) / ShadowA.z;
    if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0) {
        return 0.0;
    }
    float v = texture(Sampler0, uv).r;
    if (v <= 0.002) {
        return 0.0;
    }
    float blocker = 300.0 + v * ShadowB.w;
    float edge = smoothstep(0.0, 0.06, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
    return smoothstep(0.0, 24.0, blocker - worldY - bias) * edge * ShadowA.w;
}

void main() {
    float dist = length(ovPos);
    // The far pass reaches 24 blocks into the near range (the near pass draws over it), so no gap shows at the split.
    if (Split.y > 0.5 ? dist < Split.x - 24.0 : (Split.y < -0.5 && dist >= Split.x)) {
        discard;
    }
    if (Dissolve.y != 0.0) {
        vec2 fq = ovPos.xz + Field.xy + vec2(0.37, -0.61) * (ovPos.y + World.x);
        float dn = 0.65 * ovNoise(fq / 14.0) + 0.35 * ovNoise(fq / 5.0 + 13.0);
        bool isNew = dn < mix(-0.02, 1.02, Dissolve.x);
        if (isNew != (Dissolve.y > 0.0)) {
            discard;
        }
    }
    float d = length(ovPos.xz);
    // a: 0.5 low layer, 0.65 cloud sea (low-layer shading, giant fade), 0.8 giant storm, 1 storm.
    bool farRing = ovInfo.a < 0.42;
    bool giant = ovInfo.a > 0.58 && ovInfo.a < 0.9;
    bool storm = ovInfo.a >= 0.9 || (ovInfo.a > 0.72 && ovInfo.a < 0.9) || ovInfo.a < 0.22;
    // 11-23: floor columns of the near window that reach into a storm are drawn as the storm (client/sky/VoxelClouds.java
    // stormColumnTags: 0.91 storm, 0.976 nest, 0.851 giant), but they are still floor and leave the far ring's hole to the far window.
    bool floorInStorm = (ovInfo.a > 0.905 && ovInfo.a < 0.915) || (ovInfo.a > 0.966 && ovInfo.a < 0.979) || (ovInfo.a > 0.845 && ovInfo.a < 0.857);
    if (Far.w > 0.5 && ((ovInfo.a > 0.42 && ovInfo.a < 0.72) || floorInStorm)
            && (ovPos.x < FarHole.x || ovPos.z < FarHole.y || ovPos.x > FarHole.z || ovPos.z > FarHole.w)) {
        discard;
    }
    float fadeFrom = farRing ? Far.x : (giant ? StormFade.z : (storm ? StormFade.x : Fade.x));
    float fadeTo = farRing ? Far.y : (giant ? StormFade.w : (storm ? StormFade.y : Fade.y));
    float alpha = (1.0 - smoothstep(fadeFrom, fadeTo, d)) * CloudColor.a * (farRing ? Far.z : 1.0);
    // Horizon trial (11-18, Curve.w): far clouds that sit right on the horizon fade out into the sky behind them (they showed as thin
    // lines and flat discs in the haze band). Higher parts stay.
    if (Curve.w > 0.001) {
        float el0 = ovBentY / max(length(vec3(ovPos.xz, ovBentY)), 1.0);
        // Only from below the cloud layer (World.x = camera y): from above, every far cloud lies below the horizon line and would vanish.
        alpha *= 1.0 - Curve.w * (1.0 - smoothstep(0.0, 0.02, el0)) * smoothstep(2500.0, 5000.0, d) * (1.0 - smoothstep(300.0, 420.0, World.x));
    }
    if (Puff.w > 0.5) {
        alpha *= Puff.y;
        if (Look.w > 0.5 && Puff.y < 0.98) {
            discard;
        }
    }
    // The haze of the overgrown forest (11-13): a cloud at distance dist keeps this share of itself (hazeKeep). Its colour goes to the
    // haze colour by the lost share (further down). 11-16 (user report: clouds were see-through now and then): the lost share used to
    // thin the cloud as well, so a half-hazed cloud (forest edge, the seconds after leaving the canopy) was a ghost with the blue sky
    // and the cirrus showing through it. Now the cloud stays solid while it is less than about half haze, and only thins once it is
    // mostly haze-coloured anyway (so that a fully hazed cloud still vanishes into the haze-filled sky, which is not one flat colour).
    // 11-16: the distance term exp(-ForestHazeK.x * dist) is gone too. It took the haze at the camera as reaching all the way to the
    // cloud, so while the camera was leaving the forest (little haze left, sky dome already mostly blue) clouds a few hundred blocks
    // away were still mostly hazed: pale ghosts on a blue sky. The share of the dome that is haze-filled is the whole measure now.
    float hazeKeep = 1.0 - ForestHaze.a;
    if (hazeKeep < 0.999) {
        float hazeSolid = 1.0 - smoothstep(0.55, 1.0, 1.0 - hazeKeep);
        if (Look.w > 0.5 && hazeSolid < 0.98) {
            discard;
        }
        alpha *= hazeSolid;
    }
    if (alpha <= 0.003) {
        discard;
    }
    int code = int(floor(ovInfo.r * 255.0 + 0.5));
    int face = code / 16;
    int bits = code - face * 16;
    vec3 n = ovFaceNormal(face);
    // Faces seen from behind are never needed (the meshes are closed): skipping them keeps the see-through soft edges from showing the
    // inside of the cloud, and inside a cloud the fog hides the cloud around the camera instead.
    if (dot(n, ovPos) > 0.0) {
        discard;
    }
    // Soft outline: near the convex edges of the boxes the face turns see-through, rounded at the corners.
    // Only the opaque part writes depth (depth-only pass), so a softened edge shows whatever lies behind it, cloud or sky.
    vec3 view = normalize(ovPos);
    float du = min(ovOutlineDist(face, bits, 3, ovUv.x, view), ovOutlineDist(face, bits, 1, 1.0 - ovUv.x, view));
    float dv = min(ovOutlineDist(face, bits, 0, ovUv.y, view), ovOutlineDist(face, bits, 2, 1.0 - ovUv.y, view));
    // Far away the soft band is wider, so the box corners melt into round outlines (user pick: round far outlines).
    float far = smoothstep(500.0, 1600.0, dist) * Look2.z;
    float W = mix(0.42, 0.62, far);
    vec2 k = max(vec2(W) - vec2(du, dv), 0.0);
    float edgeSoft = 1.0 - smoothstep(0.0, W, W - length(k));
    // Storm shadow on this point (client/CloudSelfShadow.java). Inside a shadow the edges stay nearly solid: a see-through edge there
    // showed the lit cloud or sky behind it, so the shadowed step edges looked bright.
    float worldY = ovPos.y + World.x;
    // 11-12 (user report: under a storm the cloud base had white patches): seen from below the cloud layer, the undersides of a storm
    // and its low sides take the tone of the cloud floor's underside, so the base reads as one grey ceiling. 1 = like the floor.
    float below = 1.0 - smoothstep(280.0, 310.0, World.x);
    float asLow = storm ? below * (n.y < -0.5 ? 1.0 : (n.y > 0.5 ? 0.0 : 1.0 - smoothstep(330.0, 430.0, worldY))) : 0.0;
    float shadowed = ovStormShadow(worldY, storm ? 40.0 : 6.0) * SunDir.w;
    // Holes (in field coordinates the torn edge stays put on the cloud while the camera moves).
    float torn = ovNoise((ovPos.xz + Field.xy) / 6.0 + vec2(worldY / 5.0, 0.0));
    vec2 hole = max(max(max(ovHole(Holes0[0], Holes0[1], torn), ovHole(Holes0[2], Holes0[3], torn)),
            max(ovHole(Holes1[0], Holes1[1], torn), ovHole(Holes1[2], Holes1[3], torn))),
            max(max(ovHole(Holes2[0], Holes2[1], torn), ovHole(Holes2[2], Holes2[3], torn)),
            max(ovHole(Holes3[0], Holes3[1], torn), ovHole(Holes3[2], Holes3[3], torn))));
    // Overcast ceiling (11-08, improvement 3): on a cloudy day the undersides seen from below are a solid grey ceiling, so their soft
    // see-through edges close up (at first the overcast sea looked pale and see-through from the ground).
    float ceiling = smoothstep(0.6, 0.95, World.y) * (n.y < -0.5 ? 1.0 : 0.0);
    // The flat cloud floor (11-08 finishing 5): its low step edges went see-through and read as thin diagonal lines skimming the floor.
    float floorEdge = (!storm && (giant || farRing)) ? World.z : 0.0;
    // 11-08 (user report: standing at a step of the cloud floor, the clouds near the camera were see-through and the ground below showed):
    // the soft outline is for far silhouettes only. Near the camera every face is solid, and the cloud floor stays solid out to 300 blocks.
    // 11-09 (clouds stage 2a): storm towers are 16-block boxes whose every edge is convex, so the soft outline turned whole faces
    // see-through within a few hundred blocks (a tower 300 blocks away looked like a ghost). 11-09 (stage 2a-2, user decision): towers
    // 500 to 1100 blocks away still showed see-through rims, so storms stay solid out to 1500 blocks and soften only by 2500.
    float nearSolid = storm ? 1.0 - smoothstep(1500.0, 2500.0, dist) : 1.0 - smoothstep(48.0, 160.0, dist);
    float floorSolid = (!storm && (giant || farRing)) ? 1.0 - smoothstep(300.0, 700.0, dist) : 0.0;
    float keep = max(max(0.9 * floorEdge, nearSolid), floorSolid);
    float soft = 1.0 - Look.y * mix(0.8, 0.95, far) * edgeSoft * (1.0 - 0.75 * shadowed) * (1.0 - 0.85 * ceiling) * (1.0 - keep);
    if (Look.w > 0.5 && soft < 0.98) {
        discard;
    }
    alpha *= soft;
    float height = ovInfo.g;
    float thin = ovInfo.b;
    vec3 l = SunDir.xyz;
    float s = SunDir.w;
    // Lower parts of a cloud are darker; flat grey bases, bright tops.
    float shade = mix(0.68, 0.95, clamp(height * 1.8, 0.0, 1.0));
    if (storm) {
        // Baked brightness: dark base and undersides of the lobes, bright head.
        shade = mix(0.74, 1.0, height) * (n.y < -0.5 ? 0.86 : 1.0);
    } else if (n.y < -0.5) {
        shade = 0.62;
    } else if (n.y > 0.5) {
        // Lower tops (between the puffs) are a little darker, so the terraces read from above.
        // Flatter for the cloud sea (user pick: the steps of the sea should not stand out).
        shade = mix(mix(0.8, 0.9, Look2.z), 1.0, clamp(height * 1.6, 0.0, 1.0));
    }
    // The cloud floor reads as one smooth plain (11-08, the user's reference: a smooth lavender floor): the tops of the sea take one tone,
    // and the low step sides lean to it, so the 24-block columns do not show as terraces.
    if (!storm && (giant || farRing)) {
        if (n.y > 0.5) {
            shade = mix(shade, 0.96, World.z);
        } else if (n.y > -0.5) {
            shade = mix(shade, 0.9, 0.6 * World.z);
        }
    }
    shade = mix(shade, n.y < -0.5 ? 0.62 : 0.66, asLow);
    float lit = max(dot(n, l), 0.0) * (1.0 - asLow);
    // Storm clouds cast shadows on the cloud sea and on their own far side (client/CloudSelfShadow.java).
    float cast = shadowed * (storm ? 0.6 : 1.0);
    vec3 c = CloudColor.rgb * shade;
    c *= mix(1.0, 0.88, s * (1.0 - lit));
    // Storm clouds are big stair-stepped voxel surfaces: less contrast between face directions (else every step is a stripe).
    c += SunColor.rgb * lit * s * (storm ? 0.12 : 0.22) * (1.0 - cast);
    c *= 1.0 - 0.3 * cast;
    // Cel shading (user: anime-like, cel style that fits Minecraft): three flat tones with crisp steps. The light value is per face
    // and per voxel (storm brightness is baked per box), so the steps fall on box edges like Minecraft blocks.
    float lum = shade * (1.0 - 0.12 * s * (1.0 - lit)) + (storm ? 0.12 : 0.22) * lit * s - 0.28 * cast;
    // Brush mottling (user pick): a faint painted unevenness on the flat faces, stretched like brush strokes, in field coordinates so
    // it stays on the cloud. It also nudges the tone steps, so their edges wander like a painted line.
    vec2 fpB = ovPos.xz + Field.xy;
    vec2 pc = abs(n.y) > 0.5 ? fpB : (abs(n.x) > 0.5 ? vec2(fpB.y, worldY) : vec2(fpB.x, worldY));
    float brush = 0.6 * ovNoise(vec2(pc.x / 64.0, pc.y / 22.0)) + 0.4 * ovNoise(vec2(pc.x / 19.0 + 7.0, pc.y / 9.0));
    brush = (brush - 0.5) * 2.0 * Look2.z * (1.0 - smoothstep(900.0, 1600.0, dist));
    lum += 0.035 * brush;
    // Storm bases (user pick: dark and flat like a rain cloud): the lower part of the tower (low baked brightness) and the undersides
    // of the lobes drop to the shadow tone.
    if (storm) {
        // 11-09 (stage 2a-2, user decision): half as strong, the dark base made the towers stand out from the cloud sea (deep blue-violet
        // bodies at dusk).
        float base = smoothstep(0.72, 0.56, height) * Look2.z * 0.5;
        lum = mix(lum, min(lum, 0.66), base);
        if (n.y < -0.5) {
            lum = mix(lum, min(lum, 0.68), Look2.z * 0.5);
        }
    }
    // Palette after the user's reference (Royal Space Force: The Wings of Honneamise): faintly warm white lights, pale lavender-blue
    // middle tones and blue-violet shadows that lean to the sky colour. Inside each tone a soft painted gradient (lighter towards
    // the top of the cloud and towards the next tone), so the tones read like a hand-painted background rather than flat fills.
    float rise = storm ? smoothstep(320.0, 1100.0, worldY) : clamp(height * 1.2, 0.0, 1.0);
    vec3 hi = CloudColor.rgb * vec3(1.02, 1.01, 0.99) + SunColor.rgb * 0.05 * s;
    vec3 mid = CloudColor.rgb * vec3(0.8, 0.83, 0.93);
    // The cloud sea: middle tone closer to the light one, so each step of the columns is a softer line (user pick).
    // 11-09 (stage 2a-2, user decision "the same brightness as the clouds around"): the storm towers take most of it too, and their
    // shadow tone leans to the middle one, so a near tower wall is not a pale lavender sheet and a dusk tower not a dark violet mass.
    mid = mix(mid, hi, (storm ? 0.3 : 0.4) * Look2.z);
    vec3 low = mix(CloudColor.rgb * vec3(0.55, 0.59, 0.8), SkyColor.rgb * 0.72, 0.22);
    if (storm) {
        low = mix(low, mid, 0.35 * (1.0 - asLow));
    }
    // Time-of-day tints (11-08, design values).
    hi *= LitTint.rgb;
    mid *= mix(LitTint.rgb, ShadeTint.rgb, 0.5);
    low *= ShadeTint.rgb;
    hi *= mix(0.95, 1.0, smoothstep(0.9, 1.1, lum));
    mid *= mix(0.93, 1.04, max(smoothstep(0.74, 0.9, lum), rise * 0.6));
    low *= mix(0.86, 1.06, max(smoothstep(0.45, 0.74, lum), rise * 0.5));
    // Rough weather (user ask after the reference): slate blue-grey clouds while it rains or storms (the real weather: above the
    // clouds the rain is hidden from view, the clouds still look stormy).
    float w = Rough.a;
    hi = mix(hi, Rough.rgb * vec3(0.6, 0.7, 0.78), w);
    mid = mix(mid, Rough.rgb * vec3(0.38, 0.46, 0.56), w);
    low = mix(low, Rough.rgb * vec3(0.22, 0.28, 0.38), w);
    float tHi = smoothstep(0.88, 0.91, lum);
    float tMid = smoothstep(0.72, 0.75, lum);
    vec3 cel = mix(low, mix(mid, hi, tHi), tMid);
    c = mix(c, cel, Look.x);
    c *= 1.0 + 0.045 * brush;
    c = mix(c, mix(low, hi, smoothstep(0.55, 1.05, lum)), w * (1.0 - Look.x));
    // Wind streaks (user ask after the reference): long brush strokes along the wind on the cloud sea, in field coordinates so they
    // drift with the clouds. Troughs are bluish, crests a little lighter; weaker on storm clouds and gone far away (they would shimmer).
    vec2 fp = ovPos.xz + Field.xy;
    float along = dot(fp, Field.zw);
    float across = dot(fp, vec2(-Field.w, Field.z));
    float sn = 0.6 * ovNoise(vec2(along / 300.0, across / 16.0)) + 0.4 * ovNoise(vec2(along / 130.0 + 17.0, across / 7.0));
    // Not on the undersides (11-08, improvement 3): from the ground they read as diagonal stripes on the ceiling.
    float streak = (smoothstep(0.4, 0.8, sn) - 0.4) * Look2.x * (storm ? 0.45 : (n.y > 0.5 ? 1.0 : (n.y < -0.5 ? 0.0 : 0.6)))
            * (1.0 - smoothstep(600.0, 1400.0, d));
    c = mix(c, c * vec3(0.86, 0.9, 1.0), max(-streak, 0.0) * 1.5);
    c += hi * 0.07 * max(streak, 0.0) * (1.0 - shadowed);
    // Edges only glow where the cloud is lit: out of the storm shadows and in the light tones. At first every softened edge was
    // brightened and took the silver and cyan rims, so the step edges inside a shadow stood out bright (user: the edges float).
    float litEdge = (1.0 - shadowed) * smoothstep(0.74, 0.92, lum);
    // Silver lining: looking towards the light, thin parts and the soft edges glow (stronger since the user picked it).
    vec3 v = normalize(ovPos);
    float rim = max(thin * (1.0 - shadowed), edgeSoft * litEdge);
    float silver = pow(max(dot(v, l), 0.0), 6.0) * (0.25 + 0.75 * rim) * s;
    c += SunColor.rgb * silver * 0.9;
    // Rough weather: light leaks cyan through the gaps (the outline edges and thin parts of the cloud).
    c += Rough.rgb * vec3(0.3, 0.58, 0.66) * w * (0.6 * edgeSoft * litEdge + (storm ? 0.0 : 0.35 * thin * (1.0 - shadowed)));
    // Sunrise and sunset.
    if (Glow.a > 0.001) {
        vec2 lh = normalize(Fade.zw + vec2(0.0001, 0.0));
        vec2 dh = normalize(ovPos.xz + vec2(0.0001, 0.0));
        float toward = max(dot(dh, lh), 0.0);
        // Only the tops take the red light (user pick): tall storm heads and the highest crests; the low clouds stay in the
        // bluish shade of the evening, their bases lit a little from below on the sun side.
        float top = storm ? smoothstep(520.0, 1000.0, worldY) : 0.25 * smoothstep(340.0, 460.0, worldY);
        float facing = n.y > 0.5 ? 0.85 : (n.y < -0.5 ? 0.0 : 0.45 + 0.55 * max(dot(n.xz, lh), 0.0));
        float gTop = clamp(Glow.a * Look.z * top * facing * (0.6 + 0.4 * toward), 0.0, 1.0);
        vec3 red = mix(Glow.rgb, vec3(1.0, 0.42, 0.32), 0.5);
        c = mix(c, c * 0.45 + red * 0.85, gTop);
        float under = n.y < -0.5 ? 0.9 : 0.2;
        float gLow = clamp(Glow.a * under * (1.0 - top) * (0.35 + 0.65 * toward * toward) * 0.5, 0.0, 1.0);
        c = mix(c, c * 0.55 + Glow.rgb * 0.8, gLow);
        c *= mix(vec3(1.0), vec3(0.8, 0.8, 0.9), Glow.a * Look.z * (1.0 - top) * (storm ? 0.3 : 0.6));
    }
    // Cut edge of a hole: a darker lip (the cloud seen into its depth) and, right after the hit, a glowing rim.
    c *= 1.0 - 0.22 * hole.y;
    c += HoleGlow.rgb * hole.x * 0.9;
    // Lightning lights the cloud from inside (before the haze, so far flashes are softened too).
    // Capped so a flash lights the cloud from inside without blowing it out to white (user: too dazzling).
    // Inside a cloud the flash lights the fog (client/CloudInterior.java) rather than the box faces: a near flash made the hidden faces
    // around the camera stand out as hard white rectangles.
    // Storm nest (11-18): vertex alpha 0.95 (near) or 0.19 (far ring) = the dark storm clouds of a nest. Deep grey low down and on the
    // undersides, lighter towards the top, so the walls read as a storm and the lightning stands out (the flashes are added after this).
    bool nestCloud = (ovInfo.a > 0.92 && ovInfo.a < 0.98) || (ovInfo.a > 0.17 && ovInfo.a < 0.215);
    if (nestCloud) {
        float nh = clamp((worldY - 300.0) / 2300.0, 0.0, 1.0);
        float nk = n.y < -0.5 ? 0.93 : mix(0.90, 0.55, nh * nh);
        // 11-18 (user: the unlit wall read as a flat slab): slow drifting patches of thicker and thinner cloud (Look2.w = seconds).
        vec2 nf = ovPos.xz + Field.xy;
        float nt = Look2.w;
        float tone = ovNoise(nf / 170.0 + vec2(nt * 0.012, worldY / 260.0 - nt * 0.008));
        float tone2 = ovNoise(nf / 55.0 + vec2(worldY / 70.0, nt * 0.03));
        nk = clamp(nk + (tone - 0.5) * 0.36 + (tone2 - 0.5) * 0.16, 0.0, 0.97);
        c = mix(c, CloudColor.rgb * vec3(0.11, 0.12, 0.16), nk);
        // Rain curtains hanging from the lower part of the walls: paler vertical streaks that fall.
        if (abs(n.y) < 0.5) {
            float along = abs(n.x) > 0.5 ? nf.y : nf.x;
            float col = ovNoise(vec2(along / 22.0, 3.7));
            float fall = ovNoise(vec2(along / 22.0 + 11.0, worldY / 90.0 + nt * 1.6));
            float low = 1.0 - smoothstep(520.0, 1100.0, worldY);
            float curtain = smoothstep(0.45, 0.75, col) * (0.4 + 0.6 * fall) * low;
            c = mix(c, CloudColor.rgb * vec3(0.30, 0.33, 0.40), curtain * 0.6);
        }
    }
    // 11-18 (user: near bolts blew the picture out to white): the nest clouds take the light with a soft ceiling (0.78 at most), so the
    // wall brightens but keeps its shape, and the faces turned away from the bolt stay darker.
    vec3 flashSum = nestCloud ? vec3(0.82, 0.88, 1.0) * (0.78 * (1.0 - exp(-1.1 * (ovFlashNest(Flash0, n) + ovFlashNest(Flash1, n) + ovFlashNest(Flash2, n)))))
            : min(ovFlash(Flash0) + ovFlash(Flash1) + ovFlash(Flash2), vec3(0.35));
    c += flashSum * (1.0 - 0.85 * smoothstep(0.03, 0.3, InsideFog.y));
    // Before a scheduled rain (11-09, clouds stage 2a-6, PreRain.y): the undersides and the low sides darken, so an overcast sky still
    // reads as "rain is coming". Strongest on the undersides seen from the ground.
    if (PreRain.y > 0.001) {
        float lowPart = 1.0 - smoothstep(300.0, 420.0, worldY);
        float dark = n.y < -0.5 ? 0.32 : 0.18 * lowPart;
        c *= 1.0 - dark * PreRain.y;
    }
    // Blue haze (user ask after the reference): the farther a cloud, the bluer and paler (aerial perspective).
    vec3 haze = min(SkyColor.rgb * vec3(0.9, 0.97, 1.1), vec3(1.0));
    // By horizontal distance (towards the horizon): looking down from high above stays clear (at first it washed the whole sea out).
    // Past 2500 blocks (the far ring above the clouds) the haze keeps thickening, so the far edge melts into the painted horizon.
    // With the outer ring above y 950 (Far.y out to 18 km) the second rise stretches with it, so the far clouds keep their shapes
    // out to the horizon (it ended at 7 km, and the outer ring was a pale band).
    // Rain (11-08, improvement 17): the tops of the cloud sea turn a little grey too while it really rains (seen from above the clouds).
    if (n.y > 0.5) {
        c *= mix(1.0, 0.82, w);
    }
    // Overcast ceiling (11-08, improvement 3): a flat grey underside, less lavender, less washed out by the blue haze.
    vec3 grey = CloudColor.rgb * vec3(0.66, 0.68, 0.72);
    c = mix(c, mix(grey, Rough.rgb * vec3(0.3, 0.35, 0.42), w), 0.55 * ceiling);
    float reachScale = clamp(Far.y / 7600.0, 1.0, 2.4);
    // Horizon trial (11-18, Curve.w): the parts of a far cloud that stand well above the horizon keep their own colour (white tops of
    // far storm clouds), and only the parts low over the horizon melt into the haze. lift = how much of the distance haze is held back.
    float el = ovBentY / max(length(vec3(ovPos.xz, ovBentY)), 1.0);
    float lift = 1.0 - 0.85 * Curve.w * smoothstep(0.015, 0.12, el) * smoothstep(1200.0, 3000.0, d);
    c = mix(c, haze, lift * Look2.y * (1.0 - 0.7 * ceiling) * (0.45 * (1.0 - exp(-d / 2000.0)) + 0.3 * smoothstep(2500.0, 7000.0 * reachScale, d)));
    // Far clouds fade into the sky colour before they disappear (towards the horizon colour when known: 11-08). The overcast ceiling
    // fades less, so a cloudy sky stays a ceiling to the horizon.
    vec3 skyFar = mix(SkyColor.rgb, HorizonColor.rgb, HorizonColor.a * smoothstep(300.0, 1500.0, d));
    c = mix(c, skyFar, lift * smoothstep(fadeFrom * (storm ? 0.6 : 0.3), fadeTo, d) * (storm ? 0.4 : 0.55) * (1.0 - 0.6 * ceiling));
    // Night (11-08, improvement 4): far cloud bases were darker than the sky near the horizon and looked like black holes. Lift them
    // towards the horizon colour (the moonlit haze) with the distance.
    float night = 1.0 - smoothstep(0.4, 0.7, CloudColor.b);
    c = mix(c, max(c, HorizonColor.rgb * 1.05), night * HorizonColor.a * smoothstep(120.0, 700.0, d));
    if (Curve.w > 0.001) {
        float hz = 0.55 * Curve.w * (1.0 - smoothstep(0.0, 0.05, abs(el))) * smoothstep(2000.0, 5000.0, d) * (1.0 - smoothstep(300.0, 420.0, World.x));
        c = mix(c, mix(HorizonColor.rgb, vec3(1.0), 0.35), hz);
    }
    if (hazeKeep < 0.999) {
        c = mix(c, ForestHaze.rgb, 1.0 - hazeKeep);
    }
    if (InsideFog.y > 0.001) {
        // The thin tail of the haze after leaving a cloud does not grey out the clouds (they come back right away).
        float f = clamp(dist / InsideFog.x, 0.0, 1.0) * smoothstep(0.03, 0.3, InsideFog.y);
        c = mix(c, InsideColor.rgb, f);
        if (storm) {
            // Storm clouds stay as faint grey shapes beyond the fog (a few fog lengths out to 1000 blocks).
            float sil = InsideFog.z * f * smoothstep(InsideFog.x * 2.0, InsideFog.x * 8.0, dist) * (1.0 - smoothstep(600.0, 1000.0, dist));
            c = mix(c, InsideColor.rgb * 0.62, sil);
        }
    }
    fragColor = vec4(min(c, vec3(1.0)), alpha) * ColorModulator;
}
