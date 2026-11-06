#version 150

// Overgrown Vegetation: cave mode of the overgrown area (2026-11-01 prototype; client/atmosphere/CaveLights.java and CaveMap.java hold
// the explanation). Keep this file ASCII-only.
//
// The black fog around the camera is vanilla fog (MistView sets the distance and a near-black colour), so mobs and particles fade the same.
// Here: the point lights of glowing cave plants, the glowing pixels of those plants left out of the fog, and (11-01 evening) the
// per-fragment cave factor from the cave map, so that a cave seen from outside sinks into black and the bright outside seen from a cave
// keeps its daylight.
//
// OvCaveLights: 25 x 3 float texture. Row 0 = (light position relative to the camera xyz, radius r),
//               row 1 = (light colour rgb, strength x flame flicker), row 2 = (colour family 0..3 or -1 for this mod's lights,
//               flame flicker factor, 1 if a flame, 0) (11-06). Column 24: row 0 = (ambient colour rgb x ambient, clip knee),
//               row 1 = (ambient desaturation, clip desaturation, falloff, surface night tint strength (11-06)), row 2 = cave water.
// OvCave = (number of lights, camera cave mix 0..1, light gain, brightness above which a texel of a light block glows)
// OvCaveMap: 64 x 64 float texture, one texel per block column around the camera: r = world y of the top of the highest rock
//            (not tree, not air or plants) in the column that is at least 3 blocks thick (11-02: thinner layers, like soil on a
//            giant trunk, are skipped), g = 1 when the column is in the overgrown area. 11-05: 128 wide x 192; the fine map is rows 0..63, columns 0..63; rows 64..191 are the coarse
//            map (see ovCaveUnder): r = rock top, g = overgrown, b = 1 when loaded.
// OvCaveMapA = (map origin x, map origin z relative to the camera, minus camera y, 1 when the map is ready, 2 when it also has overgrown
//              columns)
// OvCaveMapB = (rock above from which a fragment starts to be underground, rock above where it is fully, sky light from, sky light to (0..1))
// OvCaveFog  = (cave fog colour rgb, -), OvCaveFogD = (cave fog start, end, 1 when the camera is in a fluid, dev red tint)
// OvCaveSurf = (spore haze colour of the forest rgb, haze per block): the haze an outside fragment gets while the camera is in a cave
//              (the black camera fog is taken off it, and the outside would otherwise show its bare, dim lightmap)
// OvCaveMouth = (from, to, light sink, unsmoothed target of the camera cave mix (11-05)) (11-04): while the camera is outside, a cave fragment sinks into the cave fog colour by its
//              horizontal distance from the nearest cave mouth (map b, smoothstep from..to), with the forest haze only outside the cave; the lights and
//              the glowing pixels sink by that times "light sink" (below 1 they stay as dim pools in the dark). Before 11-04 the cave fog was
//              by the distance from the camera, so a cave seen from far away was solid black with only its glowing pixels left.
// OvCaveMap b = horizontal distance (columns, capped at 32) to the nearest column open to the sky at the height of this column's
//              cave ceiling; a = the y of that ceiling (bottom block of the roof rock).
//
// A light adds albedo * colour * strength / (1 + (d/(f r))^2) to a face, faded to 0 between r and 1.5 r (f = falloff, light row 1
// column 24 z; 11-04: 1 before, so a light of radius 22 lit its whole room the same). Solid faces turned
// away from the light get less (facing = 1); thin plants do not (facing = 0). Walls do not block the light.
// Every underground face also gets a grey-green raised floor (desaturated albedo * ambient colour), and a face brightened by the lights
// is softly held below 1.0 above the clip knee and loses saturation the brighter it gets (11-01 evening: no lime blow-out).
uniform sampler2D OvCaveLights;
uniform vec4 OvCave;
uniform sampler2D OvCaveMap;
uniform vec4 OvCaveMapA;
uniform vec4 OvCaveMapB;
uniform vec4 OvCaveFog;
uniform vec4 OvCaveFogD;
uniform vec4 OvCaveSurf;
uniform vec4 OvCaveMouth;

// Water and glass (the translucent layer) take this share of the point lights and the raised floor (11-04, provisional).
const float OV_CAVE_WATER_GAIN = 0.5;

// How much this fragment is underground (0..1). Without the map (or outside it) the camera's cave mix stands in.
// n points away from the face into the air it faces (the column on that side decides, so cliff faces stay outside).
float ovCaveUnder(vec3 pos, vec3 n, float sky) {
    float dark = smoothstep(0.0, 1.0, clamp((OvCaveMapB.z - sky) / max(OvCaveMapB.z - OvCaveMapB.w, 0.01), 0.0, 1.0));
    // Without the map (or outside it) the camera's cave mix stands in, but only where the sky light is all but gone
    // (11-04: with the sky no longer black, the far forest past the 64 x 64 map - giant trees with shaded canopies, sky light 3..8 -
    // showed as black shapes through a cave mouth. Sky light 0.5 -> 3 (of 15) fades from cave to outside; provisional).
    float enclosed = 1.0 - smoothstep(0.5 / 15.0, 3.0 / 15.0, sky);
    float outside = OvCave.y * enclosed;
    if (OvCaveMapA.w < 0.5) {
        return outside;
    }
    // 11-04: past the map, while the map has overgrown columns (w 2), a fragment with (almost) no sky light is in a cave whether the
    // camera is or not (a cave seen from outside went back to its bare lightmap 32 blocks from the camera).
    // 11-05 (light inspection 16): that rule now holds only past the coarse map too (out of the loaded area). By the sky light alone the
    // trunk, the branch clusters and the floor under a giant tree's crown (sky light about 0) were caves, and seen from outside they sank
    // into the black cave fog. Past the fine map the coarse map (rows 64..191, 128 x 128, one column read per 4 x 4 blocks, 512 blocks square, its
    // origin = the fine origin + 32 - 256; CaveMap.COARSE) takes that rule back where no rock is above the fragment (trees are not rock).
    // Cells of 8 blocks first: a cell centred on the bank made the pond under a giant crown a cave, and a cell across the edge of a small
    // hill (fog14, 16 wide) took the cave's back wall out of the cave; 4 blocks get both right.
    if (OvCaveMapA.w > 1.5) {
        outside = enclosed;
    }
    vec3 p = pos + n * 0.5;
    ivec2 tc = ivec2(floor((p.xz - OvCaveMapA.xy + 224.0) / 4.0));
    if (tc.x >= 0 && tc.y >= 0 && tc.x <= 127 && tc.y <= 127) {
        vec4 cc = texelFetch(OvCaveMap, ivec2(tc.x, tc.y + 64), 0);
        // b = 1 where the column was loaded; otherwise the old stand-in. The coarse map only takes away: a fragment is a cave by the
        // old rule (sky light 0.5 -> 3 of 15) and only where rock is above it. Counting it a cave by the rock alone (times dark, as in
        // the fine map) made the passage near a mouth past the map a cave too, and its forest haze over the walked part (ovCavePath)
        // lit the mouth up brighter than the rock around it (fog14 at 40 blocks: 74 -> 100, the 14 regression).
        float coarse = outside;
        if (cc.b > 0.5) {
            coarse = cc.g >= 0.5 ? smoothstep(OvCaveMapB.x, OvCaveMapB.y, cc.r + OvCaveMapA.z - p.y) * outside : 0.0;
        }
        // Blend into the stand-in over the outer 4 cells (16 blocks), so the edge of the coarse map does not show as a jump.
        float cedge = float(min(min(tc.x, 127 - tc.x), min(tc.y, 127 - tc.y)));
        outside = mix(outside, coarse, smoothstep(0.0, 4.0, cedge));
    }
    ivec2 t = ivec2(floor(p.x - OvCaveMapA.x), floor(p.z - OvCaveMapA.y));
    if (t.x < 0 || t.y < 0 || t.x > 63 || t.y > 63) {
        return outside;
    }
    vec4 c = texelFetch(OvCaveMap, t, 0);
    float inMap = 0.0;
    if (c.g >= 0.5) {
        float rock = c.r + OvCaveMapA.z - p.y;
        float roof = smoothstep(OvCaveMapB.x, OvCaveMapB.y, rock);
        // 11-04 (13, the fog "peeling off" in a cave): the roof steps per column, so the floor under a hole in the roof (or a thin roof the
        // map skips) was outside right up to a straight line, with the forest haze on it next to the dark cave. While the camera is in a
        // cave, near it the sky light alone decides (per vertex, so it ramps over the ~8 blocks the light falls off from the hole); past
        // 0.6 fogStart .. fogStart the roof map counts again (the far outside seen through a mouth keeps the 11-04 rule). Provisional.
        float near = OvCave.y * (1.0 - smoothstep(0.6 * OvCaveFogD.x, OvCaveFogD.x, length(pos)));
        inMap = mix(roof, 1.0, near) * dark;
    }
    // 11-04 (c): blend into the stand-in over the outer 6 columns, so the map's edge does not show as a jump.
    float edge = float(min(min(t.x, 63 - t.x), min(t.y, 63 - t.y)));
    return mix(outside, inMap, smoothstep(0.0, 6.0, edge));
}

// Set by ovCaveSink and ovCaveLight for ovCaveFog in the same fragment (11-04): how deep it is from the cave mouth (0..1, by
// OvCaveMouth.x..y), its distance from the mouth (blocks) and what the point lights added to it.
float ovCaveSinkNow = 0.0;
float ovCaveMouthNow = 0.0;
// 1 once the fragment shader has called ovCaveSetHaze right before ovCaveFog (11-04, 14); -1 = not (the older OvCaveSurf haze is used).
float ovCaveHaze = -1.0;
// The fragment's position relative to the camera, kept by ovCaveSink for ovCaveFog.
vec3 ovCavePosNow = vec3(0.0);
vec3 ovCaveAdd = vec3(0.0);

// Map b (distance to the nearest cave mouth) at a column, clamped to the map.
float ovCaveMouthAt(ivec2 t) {
    return texelFetch(OvCaveMap, clamp(t, ivec2(0), ivec2(63)), 0).b;
}

// How much a fragment sinks into the cave fog colour while the camera is outside (0..1; 11-04). The distance to the mouth is
// interpolated between the four nearest columns, so walls do not show one-block steps. Applied in ovCaveFog:
// near the mouth the cave keeps the forest haze like the ground around it, and deeper in it goes dark.
float ovCaveSink(vec3 pos, vec3 n, float under) {
    ovCaveSinkNow = 0.0;
    ovCaveMouthNow = 0.0;
    if (under <= 0.0 || OvCaveMapA.w < 0.5 || OvCaveFogD.z > 0.5) {
        return 0.0;
    }
    float away = under * (1.0 - OvCave.y);
    if (away <= 0.0) {
        return 0.0;
    }
    vec3 p = pos + n * 0.5;
    vec2 g = vec2(p.x - OvCaveMapA.x, p.z - OvCaveMapA.y) - 0.5;
    ivec2 i = ivec2(floor(g));
    vec2 f = g - vec2(i);
    float d = mix(mix(ovCaveMouthAt(i), ovCaveMouthAt(i + ivec2(1, 0)), f.x),
                  mix(ovCaveMouthAt(i + ivec2(0, 1)), ovCaveMouthAt(i + ivec2(1, 1)), f.x), f.y);
    // Past the map (and over its outer 6 columns) the mouth is unknown: such a fragment is only underground with (almost) no sky
    // light (ovCaveUnder), so it counts as deep (32, the cap of the map).
    ivec2 t = ivec2(floor(p.x - OvCaveMapA.x), floor(p.z - OvCaveMapA.y));
    float edge = float(min(min(t.x, 63 - t.x), min(t.y, 63 - t.y)));
    d = mix(32.0, d, smoothstep(0.0, 6.0, edge));
    ovCaveMouthNow = d;
    ovCaveSinkNow = smoothstep(OvCaveMouth.x, OvCaveMouth.y, d);
    ovCavePosNow = pos;
    return away * ovCaveSinkNow;
}

// How far the camera's line of sight to pos (camera-relative) runs outside before it first goes under a roof of the cave map (rock top
// more than roofFrom above it), as a share of the whole line (0..1; 11-04, 14). This is the part of the view the forest haze is in: the
// rest is the dark air inside the cave. Before, the haze was over dist - (the fragment's distance to the nearest mouth), and past the
// map that distance was 32, so a mouth seen from the misty forest was a hard black hole at any distance. Marched in up to 24 steps over
// at most 48 blocks. Where the line leaves the map (or that reach) still outside, the mouth is not known: only the part walked counts
// (provisional; the map reaches 24..40 blocks from the camera). Counting more ("up to 12 blocks short of the fragment") made a mouth just
// past the map lighter than the rock around it, because the dark air inside was hazed as forest; this way a far mouth stays a little
// darker than its rim.
float ovCaveOutsideShare(vec3 pos) {
    float len = length(pos);
    if (len < 0.5) {
        return 1.0;
    }
    vec3 dir = pos / len;
    float stepLen = max(min(len, 48.0) / 24.0, 0.5);
    for (int i = 1; i <= 24; i++) {
        float tt = float(i) * stepLen;
        if (tt >= len) {
            return 1.0;
        }
        vec3 q = dir * tt;
        ivec2 t = ivec2(floor(q.x - OvCaveMapA.x), floor(q.z - OvCaveMapA.y));
        if (t.x < 0 || t.y < 0 || t.x > 63 || t.y > 63) {
            return tt / len;
        }
        if (texelFetch(OvCaveMap, t, 0).r + OvCaveMapA.z - q.y > OvCaveMapB.x) {
            return (tt - 0.5 * stepLen) / len;
        }
    }
    return min(48.0, len) / len;
}

// The length of the line of sight that is in the forest haze (11-04, 14): the whole distance, except for a cave fragment seen while the
// camera is outside (by its cave share), where it ends where the line goes under the roof (ovCaveOutsideShare). Call after ovCaveSink.
float ovCavePath(float dist, float under) {
    float toCave = under * (1.0 - OvCave.y);
    if (OvCaveMapA.w < 0.5 || OvCaveFogD.z > 0.5 || toCave <= 0.0) {
        return dist;
    }
    return dist * mix(1.0, ovCaveOutsideShare(ovCavePosNow), toCave);
}

// The usual fogs of the fragment (11-04, 14), for the cave part seen from outside: height = the height fog of the forest (colour, share
// over the whole line), fogColor = the vanilla fog colour (the spore haze in the forest) with its alpha, fogDist / fogPath = the vanilla
// fog at the fragment's distance and at the path length (ovCavePath). The height fog over the path is taken as the same share per block.
vec4 ovCaveHeight = vec4(0.0);
vec4 ovCaveVanilla = vec4(0.0);
float ovCaveHeightPath = 0.0;
// The height fog colour over the path: it goes from the near to the far colour with distance (ov_fog.glsl), and a far fragment carries
// the far one, so over the shorter path it is moved back by the same rule. Read from the height fog uniforms of the vertex shader.
vec3 ovCaveHeightPathColor = vec3(0.0);
uniform vec4 OvFogC;
uniform vec4 OvFogD;
uniform vec4 OvFogH;
uniform vec4 OvFogB;
uniform vec4 OvFogG;
// OvFogL.y = 1 while design fog.forestDim is on (the rest of OvFogL is for ov_fog.glsl, in the vertex shaders).
uniform vec4 OvFogL;

// 11-05 (light inspection 2): while the camera is in the forest the spore haze is drawn by the vanilla fog (fog.glsl, the haze mark: a
// fog start below -1000), and under the canopy it was brighter than the brightest leaf (the haze seemed to glow). MistView.renderFog now
// darkens the terrain fog colour by the camera's sky light (shadeFloor OvFogH.x to 1, the rule of ovFogShaded) for terrain, mobs and
// particles alike. Here, on terrain only, fragments near the camera with less sky light than the camera go a little darker still
// (the haze in front of them is the air of their own shade); the more haze is in front, the less (the haze is the air along the whole
// line: by the fragment's own sky light alone, far trunks came out as pale and dark blotches). Needs the camera's sky light OvFogG.w,
// sent only while the height fog is on (OvFogB.x > 0). Off with design fog.forestDim false (OvFogL.y = 0).
// 11-05 (light inspection 3): the haze toward the sun at dusk and dawn (the same as ovHazeSunSide in ov_fog.glsl, which the vertex
// shaders use). The vanilla fog colour is one colour for the whole view, so the sun side goes in here, per fragment. Sent only while
// the camera is in the overgrown area (OvHazeS.w, 0 by day and at night).
uniform vec4 OvHazeS;
uniform vec4 OvHazeD;

vec3 ovHazeSunSideF(vec3 color, vec3 pos) {
    if (OvHazeS.w <= 0.0) {
        return color;
    }
    float len = length(pos);
    float side = smoothstep(-0.2, 1.0, dot(pos / max(len, 0.001), OvHazeD.xyz));
    float k = OvHazeS.w * side * side * (1.0 - exp(-len / max(OvHazeD.w, 1.0)));
    return min(mix(color, vec3(dot(color, vec3(0.3, 0.59, 0.11))) * OvHazeS.rgb, k), vec3(1.0));
}

// pos = the fragment relative to the camera (for the sun side of the haze).
vec4 ovShadedFogColor(vec4 fogColor, float fogStart, float sky, float dist, vec3 pos) {
    fogColor.rgb = ovHazeSunSideF(fogColor.rgb, pos);
    if (fogStart >= -1000.0 || OvFogB.x <= 0.0 || OvFogL.y < 0.5) {
        return fogColor;
    }
    float floorDim = OvFogH.x > 0.0 ? OvFogH.x : 0.3;
    float camera = clamp(OvFogG.w, 0.0, 1.0);
    float near = mix(floorDim, 1.0, min(clamp(sky, 0.0, 1.0), camera)) / mix(floorDim, 1.0, camera);
    // The spore haze part of the mark only (fog.glsl; since 11-06 the mark also carries the aerial perspective of the whole world,
    // which is not the forest haze: no darkening by the shade without the spore haze).
    float c = -fogStart - 1000.0;
    float k = (c - floor((c + 0.5) / 10000.0) * 10000.0) / 100000.0;
    if (k <= 0.0) {
        return fogColor;
    }
    float haze = 1.0 - exp(-k * dist);
    return vec4(fogColor.rgb * mix(near, 1.0, haze), fogColor.a);
}


float ovCaveVanillaDist = 0.0;
float ovCaveVanillaPath = 0.0;

void ovCaveSetHaze(vec4 height, vec4 fogColor, float dist, float path, float fogDist, float fogPath) {
    ovCaveHaze = 1.0;
    ovCaveHeight = height;
    ovCaveVanilla = fogColor;
    ovCaveHeightPath = 1.0 - pow(max(1.0 - height.a, 0.0), path / max(dist, 0.001));
    float colorDist = max(OvFogD.w, 1.0);
    ovCaveHeightPathColor = clamp(height.rgb + (OvFogD.rgb - OvFogC.rgb) * (exp(-dist / colorDist) - exp(-path / colorDist)), 0.0, 1.0);
    ovCaveVanillaDist = fogDist * fogColor.a;
    ovCaveVanillaPath = fogPath * fogColor.a;
}

// The two usual fogs over the whole line (path false) or over the path only.
vec3 ovCaveHazeOver(vec3 color, bool path) {
    color = path ? mix(color, ovCaveHeightPathColor, ovCaveHeightPath) : mix(color, ovCaveHeight.rgb, ovCaveHeight.a);
    return mix(color, ovCaveVanilla.rgb, path ? ovCaveVanillaPath : ovCaveVanillaDist);
}

// block = the vanilla block light of the fragment (0..1). 11-04: vanilla lights (lava, torches...; point lights since 11-04, sent with
// a negative radius) add less where the block light is already high (on and right next to them), or lava faces washed out to pink.
// The glowing plants of this mod are not held back (their own light level is low, or the glow vines' 14 left dark rings around them).
vec3 ovCaveLight(vec3 color, vec3 albedo, vec3 pos, vec3 n, float facing, float under, float block) {
    ovCaveAdd = vec3(0.0);
    // 11-04 (13 continued, the floor under a hole in the roof darker than around it): under holds the sky light (12 -> 4 of 15), so the
    // sunlit floor right under the hole got neither the raised floor nor the point lights and stayed at the bare vanilla light, darker
    // than the cave floor around it. While the camera is in a cave, near it (the same 0.6 fogStart .. fogStart as ovCaveUnder) both go
    // in whatever the sky light; the sky light still decides the fog only (ovCaveFog gets the real under). Provisional.
    under = max(under, OvCave.y * (1.0 - smoothstep(0.6 * OvCaveFogD.x, OvCaveFogD.x, length(pos))));
    int count = int(OvCave.x + 0.5);
    vec4 extra2 = texelFetch(OvCaveLights, ivec2(24, 1), 0);
    // 11-06 (light 6): at night on the surface the lights pull the colour of a face toward their colour and direction, by the
    // block light share of the fragment (the vanilla lightmap keeps its brightness; sky light is not pulled). extra2.w = night x strength.
    float surf = count > 0 ? extra2.w * (1.0 - under) * clamp(block, 0.0, 1.0) : 0.0;
    if (under <= 0.0 && surf <= 0.0) {
        return color;
    }
    vec3 sum = vec3(0.0);
    // Surface tint sums: colour x reach (without the flame flicker), colour x reach x direction x flicker, and the reach.
    vec3 sCol = vec3(0.0);
    vec3 sDir = vec3(0.0);
    float sAtt = 0.0;
    vec4 extra = texelFetch(OvCaveLights, ivec2(24, 0), 0);
    float falloff = extra2.z > 0.0 ? extra2.z : 1.0;
    float vanillaKeep = 1.0 - smoothstep(0.6, 1.0, block);
    for (int i = 0; i < 24; i++) {
        if (i >= count) {
            break;
        }
        vec4 p = texelFetch(OvCaveLights, ivec2(i, 0), 0);
        float r = abs(p.w);
        vec3 to = p.xyz - pos;
        float d = length(to);
        float reach = 1.5 * r;
        if (d >= reach) {
            continue;
        }
        vec4 c = texelFetch(OvCaveLights, ivec2(i, 1), 0);
        float q = d / (r * falloff);
        float base = c.w / (1.0 + q * q) * (1.0 - smoothstep(r, reach, d));
        float att = base * (p.w < 0.0 ? vanillaKeep : 1.0);
        float ndl = dot(n, to / max(d, 0.001));
        float lambert = mix(1.0, clamp(ndl * 0.75 + 0.25, 0.0, 1.0), facing);
        sum += c.rgb * att * lambert;
        if (surf > 0.0) {
            // Row 2 y = this frame's flame flicker (c.w already has it): the colour sum leaves it out, so the flicker shows.
            float flick = max(texelFetch(OvCaveLights, ivec2(i, 2), 0).y, 0.05);
            // Facing: toward the light 1.25, edge-on 0.8, away down to 0.45 (solid faces only; centred near 1 so the street keeps its
            // brightness: 0.5 edge-on darkened the village by 2..3.5 of 255 on 11-06; provisional).
            float dirK = mix(1.0, clamp(0.8 + 0.6 * ndl, 0.45, 1.25), facing);
            // Which light the vanilla light of this face comes from: vanilla takes the brightest source, emission - block steps, and walls
            // stop it. A vanilla light (radius 0.7 x emission) whose level here (from the air block in front of the face) is far from the
            // fragment's block light is behind a wall or not the one that lights it, so it weighs little (11-06: the torches inside a house
            // darkened its front wall through the wall by about a quarter). This mod's lights have no such radius: weight 1. Provisional.
            float plaus = 1.0;
            if (p.w < 0.0) {
                vec3 ta = to - n * 0.5;
                float le = r / 0.7 - (abs(ta.x) + abs(ta.y) + abs(ta.z));
                plaus = exp(-abs(le - block * 15.0) / 2.0);
            }
            sCol += c.rgb * base * plaus / flick;
            sDir += c.rgb * base * plaus * dirK;
            sAtt += base * plaus / flick;
        }
    }
    vec3 lum = vec3(0.2126, 0.7152, 0.0722);
    if (surf > 0.0 && sAtt > 0.0) {
        // tint = (colour x direction x flicker) / brightness of the plain colour mix: the hue of the lights near the face, darker on faces
        // turned away. Faded in over the first 0.1 of reach so the edge of a light's reach does not show.
        vec3 tint = sDir / max(dot(sCol, lum), 1.0e-4);
        color *= mix(vec3(1.0), tint, surf * clamp(sAtt / 0.1, 0.0, 1.0));
    }
    if (under <= 0.0) {
        return color;
    }
    // The raised floor: grey-green, from the albedo with part of its saturation taken out.
    vec3 floorAlbedo = mix(albedo, vec3(dot(albedo, lum)), extra2.x);
    ovCaveAdd = albedo * sum * OvCave.z * under;
    vec3 lit = color + floorAlbedo * extra.rgb * under + ovCaveAdd;
    // Soft clip of the brightest channel above the knee (only where the lights added something), losing saturation as it climbs.
    float knee = clamp(extra.w, 0.1, 1.0);
    float top = max(lit.r, max(lit.g, lit.b));
    if (knee < 1.0 && top > knee && max(sum.r, max(sum.g, sum.b)) > 0.0) {
        float span = 1.0 - knee;
        float over = 1.0 - exp(-(top - knee) / span);
        float held = knee + span * over;
        vec3 clipped = lit * (held / top);
        clipped = mix(clipped, vec3(dot(clipped, lum)), extra2.y * over);
        lit = mix(lit, clipped, under);
    }
    return lit;
}

// Cave water (11-06, light inspection 10; values provisional, design cave.waterDepthDarken/waterDepthFold/waterSpecular/waterShininess).
// OvCaveLights column 24 row 2 = (bottom darkening, its e-fold depth in blocks, specular strength, shininess); 0 when the texture is older.
// OvCaveMap columns 64..127 of rows 0..63 = the water surface of each fine-map column (r = world y of the surface, g = 1 if any;
// CaveMap.buildWater, from 8 above to 24 below the camera).

// A face under cave water gets darker with the depth of the water above it (the bottom of a lake and its sunk banks), so the deep
// middle no longer has the brightness of the shore. Opaque passes only (the water face itself sits at depth 0). The column is taken
// on the water side of the face (a bank wall facing into the lake reads the lake's column), like ovCaustics.
vec3 ovCaveWaterBottom(vec3 color, vec3 pos, vec3 n, float under) {
    vec4 wp = texelFetch(OvCaveLights, ivec2(24, 2), 0);
    if (wp.x <= 0.0 || under <= 0.0 || OvCaveMapA.w < 0.5) {
        return color;
    }
    ivec2 t = ivec2(floor(pos.x + n.x * 0.3 - OvCaveMapA.x), floor(pos.z + n.z * 0.3 - OvCaveMapA.y));
    if (t.x < 0 || t.y < 0 || t.x > 63 || t.y > 63) {
        return color;
    }
    vec4 w = texelFetch(OvCaveMap, ivec2(t.x + 64, t.y), 0);
    if (w.g < 0.5) {
        return color;
    }
    float depth = w.r + OvCaveMapA.z - pos.y;
    if (depth <= 0.05) {
        return color;
    }
    float k = 1.0 - exp(-depth / max(wp.y, 0.1));
    return color * (1.0 - wp.x * k * under);
}

// The point lights mirrored on the cave water (Blinn half vector): the first 2 lights of the list (nearest to the camera for their
// radius) leave a soft spot of their colour on a level water face where the camera sees their reflection. Up-facing faces only.
vec3 ovCaveWaterSpecular(vec3 pos, vec3 n, float under) {
    vec4 wp = texelFetch(OvCaveLights, ivec2(24, 2), 0);
    if (wp.z <= 0.0 || under <= 0.0 || n.y < 0.5) {
        return vec3(0.0);
    }
    int count = min(int(OvCave.x + 0.5), 2);
    vec3 v = normalize(-pos);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 2; i++) {
        if (i >= count) {
            break;
        }
        vec4 p = texelFetch(OvCaveLights, ivec2(i, 0), 0);
        float r = abs(p.w);
        vec3 to = p.xyz - pos;
        float d = length(to);
        if (d >= 1.5 * r || to.y <= 0.0) {
            continue;
        }
        vec4 c = texelFetch(OvCaveLights, ivec2(i, 1), 0);
        vec3 h = normalize(to / d + v);
        float s = pow(max(dot(n, h), 0.0), wp.w);
        // The same fade with distance as ovCaveLight, but a reflection keeps more of a far light (it is an image of the light, not light
        // spread over a face): only the outer fade.
        float att = c.w * (1.0 - smoothstep(r, 1.5 * r, d));
        sum += c.rgb * att * s;
    }
    return sum * wp.z * under;
}

// How much the texel is a glowing pixel of a light block (cave factor, or 0): inside the block of a light and brighter than OvCave.w.
float ovCaveGlow(vec3 texel, vec3 pos, float under) {
    // In water (OvCaveFogD.z) the glowing pixels sink into the water fog like everything else.
    if (under <= 0.0 || OvCaveFogD.z > 0.5 || max(texel.r, max(texel.g, texel.b)) < OvCave.w) {
        return 0.0;
    }
    // 11-06 (lights popping on as the player walks up): the glowing pixels have their own list (rows 3..5, up to 72 of this mod's lights
    // by distance, w = weight fading to 0 toward the end of the list and the gather radius), apart from the 24 point lights. Before, only
    // the lights in the 24 glowed, so a plant started to glow when it got into the 24. Vanilla lights (lava, torches) are not in the
    // list: they obey the fog like vanilla (every 4th lava face was a light, so the lake showed as a checkerboard in the black fog).
    int count = int(texelFetch(OvCaveLights, ivec2(24, 3), 0).x + 0.5);
    for (int i = 0; i < 72; i++) {
        if (i >= count) {
            break;
        }
        int row = i / 24;
        vec4 lp = texelFetch(OvCaveLights, ivec2(i - row * 24, 3 + row), 0);
        vec3 a = abs(pos - lp.xyz);
        // 0.497 (11-03): a sheet on a wall (ley root, net) sits 0.006 inside its block; at 0.52 the wall face behind it was restored too
        if (max(a.x, max(a.y, a.z)) < 0.497) {
            return under * lp.w;
        }
    }
    return 0.0;
}

// The fog of a fragment by where it is, not where the camera is. fogged = the colour after the usual fogs (vanilla fog around the
// camera, which is black in cave mode), lit = the colour before any fog, surface = lit with only the height fog of the forest.
// An outside fragment seen while the camera is in a cave drops the black camera fog and keeps the surface look. A cave fragment seen
// while the camera is outside drops the forest haze inside the cave and sinks into the cave fog colour by its distance from the mouth
// (ovCaveSink; 11-04, before that by its distance from the camera, so a cave seen from far away was solid black). The point lights
// sink only by OvCaveMouth.z of that, so they stay as dim pools of light in the dark.
vec3 ovCaveFog(vec3 fogged, vec3 lit, vec3 surface, float dist, float under) {
    // The camera is in water (or another fluid): the fluid fog wins over the cave fog (11-01; a drowned cave seen from the sea was clear,
    // because the cave fog starts at 20 blocks and replaced the short water fog).
    if (OvCaveMapA.w < 0.5 || OvCaveFogD.z > 0.5) {
        return fogged;
    }
    float cam = OvCave.y;
    vec3 c = fogged;
    float s = ovCaveSinkNow;
    float toCave = under * (1.0 - cam);
    if (toCave > 0.0) {
        // The cave part of the view: no forest haze inside the cave, sinking into the cave fog colour by the depth from the mouth (the
        // point lights less). Then the forest spore haze only for the part of the view outside the cave (distance minus depth from the
        // mouth), with the density of the forest fog (OvCaveSurf, the same haze as the outside seen from a cave).
        vec3 cave = mix(lit, OvCaveFog.rgb, s) + ovCaveAdd * s * (1.0 - OvCaveMouth.z);
        if (ovCaveHaze >= 0.0) {
            // 11-04 (14): the same haze as the faces around the mouth. The fragment (its cave share darkened) gets the two usual fogs
            // (the height fog, then the vanilla spore haze) over the outside part of the line of sight only (ovCavePath), as a change
            // to fogged: where under fades out the path is the whole distance and this is fogged again. Before, the black went in after
            // the forest haze with OvCaveSurf over dist - mouth only (32 past the map, so ~0): a hard black hole whatever the distance.
            // (A first try took the haze colour from fogged itself: far fragments carry the far, brighter colour of the height fog, so
            // a mouth seen from 40 blocks came out lighter than its rim.)
            vec3 x = mix(lit, cave, toCave);
            c = fogged + ovCaveHazeOver(x, true) - ovCaveHazeOver(lit, false);
        } else {
            cave = mix(cave, OvCaveSurf.rgb, 1.0 - exp(-OvCaveSurf.w * max(dist - ovCaveMouthNow, 0.0)));
            c = mix(c, cave, toCave);
        }
    }
    // 11-05 (light inspection 20, the canopy mottled right after leaving a cave): this branch swaps the forest look for the haze seen
    // from inside a cave (OvCaveSurf over the outside part of the path). It went by the smoothed cave mix, which takes ~4 s to fall
    // after the camera has left, so under the canopy right outside the leaves past ~8 blocks were painted with a bright haze wall next
    // to vivid near ones. It now goes by the lower of the smoothed mix and its unsmoothed target (OvCaveMouth.w): off as soon as the
    // camera is out, still easing in on the way into a cave. The black camera fog keeps its slow fade (vanilla fog, MistView).
    float toSurface = (1.0 - under) * min(cam, OvCaveMouth.w);
    if (toSurface > 0.0) {
        // 11-04 (13): the part of the path inside the cave (the camera's own distance to the nearest mouth, map b) has no bright forest
        // haze, the same as the cave part seen from outside (a lit floor under a hole in the roof a few blocks away was veiled bright green).
        float camMouth = texelFetch(OvCaveMap, clamp(ivec2(floor(-OvCaveMapA.xy)), ivec2(0), ivec2(63)), 0).b;
        float outPath = max(dist - camMouth, 0.0);
        vec3 base = surface;
        if (ovCaveHaze >= 0.0) {
            // The height fog of the forest (in surface, over the whole line from the camera) too: it made most of that veil, so it
            // goes over the outside part only, the same way as for a cave seen from outside (ovCaveSetHaze).
            float share = 1.0 - pow(max(1.0 - ovCaveHeight.a, 0.0), outPath / max(dist, 0.001));
            float colorDist = max(OvFogD.w, 1.0);
            vec3 col = clamp(ovCaveHeight.rgb + (OvFogD.rgb - OvFogC.rgb) * (exp(-dist / colorDist) - exp(-outPath / colorDist)), 0.0, 1.0);
            base = mix(lit, col, share);
        }
        vec3 hazed = mix(base, OvCaveSurf.rgb, 1.0 - exp(-OvCaveSurf.w * outPath));
        c = mix(c, hazed, toSurface);
    }
    // Dev only (OvCaveFogD.w, devbridge "wind cavedev tint on"): paint the cave factor red.
    if (OvCaveFogD.w > 1.5) {
        // devbridge "wind cavedev tint sink": paint the sink from the mouth blue (11-04).
        c = mix(c, vec3(0.0, 0.3, 1.0), 0.8 * s * toCave);
    } else if (OvCaveFogD.w > 0.5) {
        c = mix(c, vec3(1.0, 0.0, 0.0), 0.7 * under);
    }
    return c;
}
