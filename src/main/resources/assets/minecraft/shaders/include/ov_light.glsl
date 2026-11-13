#version 150

// Overgrown Vegetation: canopy shade and sunflecks on every surface (client/atmosphere/CanopySunMap.java holds the
// explanation). Keep this file ASCII-only.
//
// The CPU traces the sun direction through the world and writes a small "sun-space" map: every texel is one line along
// the sun direction (all points on it share u = x - y * sun.x / sun.y and v = z - y * sun.z / sun.y).
//   r = top of the highest solid block crossed by the line (blocks above OvMapA.w, 0 = none)
//   g = top of the highest leaf-like block crossed by the line (same units, 0 = none)
//   b = number of leaf-like blocks crossed (x 17, up to 15)
//   a = 1 traced in an overgrown area, 0.5 traced outside, 0 not traced yet. Since 11-06 (two layers: the whole world, stronger
//   in the overgrown forest) shade, flecks, the light through leaves and the moon map work on every traced line (a > 0.25); only
//   the blue-green hue and the warm sunlit colour stay on the overgrown lines (a > 0.75).
// A fragment is in shade when a solid block of its line is more than 1.5 blocks above it (less makes stripes on the sides
// of a trunk: lines that just graze the trunk), a leaf more than one block above it, or (solid and cutout_mipped layers)
// when it faces away from the sun.
// Under thin foliage (1 to 3 leaves, no solid block) small sunflecks break through on everything but the top of the
// ground (the ground has its own spots drawn by client/atmosphere/CanopyDapple.java). The fleck pattern lives in sun space, so a
// fleck lands on a fern frond and a trunk behind it along the same beam, and is stretched along the sun on slopes.
//
// OvSunA     = (sun.x / sun.y, sun.z / sun.y, shade strength; 0 = map off, how much the map decides sun or shade (daylight))
// OvSunDir   = (sun direction, strength of the facing-away shade)
// OvMapA     = (map origin u, map origin v (both relative to the camera), map size in texels, map base y relative to the camera)
// OvShadeColor = grey brightness multiplier of full shade (rgb all the same)
// OvTint     = (blue-green hue multiplier with brightness 1, forest-wide strength). The hue goes on everything out of the sun:
//              where the map knows sun and shade (day, inside the map, overgrown lines) only on the shade, elsewhere (outside the
//              map, low sun, night) on every face while the camera is in the overgrown forest. Faces lit by torches keep their
//              amber light (block light above OV_TORCH_FROM).
// OvFleck    = (camera u and v times 1.7 wrapped to 64 plus the wind shift of the flecks, time in seconds, fleck strength;
//              0 = off). The pattern repeats every 64 / 1.7 blocks; the wrap keeps it fixed in the world while the camera moves.
uniform sampler2D OvSunMap;
uniform vec4 OvSunA;
uniform vec4 OvSunDir;
uniform vec4 OvMapA;
uniform vec4 OvShadeColor;
uniform vec4 OvTint;
uniform vec4 OvFleck;
// OvSunTint = (warm white to gold multiplier rgb of the sunlit faces and the sunflecks, strength; 0 = off). Only where the map knows the sun.
uniform vec4 OvSunTint;
// 11-06 light inspection 21 ("in a plain oak forest the gold sunflecks float on ground that has no shade"). A fleck is a hole in the
// shade, not light added on top: it moves the face from its colour toward the colour the same face would have in the sun (no canopy
// shade, sky light 15, no lift), and never past it. So where the face is hardly darker than in the sun (an open oak floor) the fleck
// all but vanishes, and where it is dark (sky light 0 to 3 under a dense canopy) it stays. The sky-light part needs the lift of the
// lightmap (client/atmosphere/CanopyLift; the same curve as liftedSkyBrightness, ambient light 0 in the overworld).
// OvLift = (lift now (skyLift, includes the forest presence and the night), lift factor of sky light 0 (skyLiftDark now), night weight
//           (sent but not read yet: the night flecks keep their old rule),
//           gamma option 0 to 1)
uniform vec4 OvLift;
// The shadow of trunks, rocks, hills, cliffs and houses (11-06 light inspection 21 and "light of the plain world 1: terrain shadow";
// design light.trunkShadow, terrainShadowReach, trunkShadowSoft): where the sun line of a fragment crosses a solid block more than 1.5
// blocks above it, the face is darker by OvTrunk.x on top of the canopy shade. It fades out with the height of the caster above the
// face (full up to (1 - soft) of OvTrunk.y, none at OvTrunk.y), and its edge gets wider the farther the caster is along the sun line
// (the sun is not a point). Faces turned to the sun do not take it (only up faces and faces turned away). Day only (the moon map has
// none), near only (inside the sun map, which since this step is built in the open too, not only under a canopy).
// OvTrunk = (strength (0 = off), reach in blocks of height, soft share of the reach, 1 while the map is there (2 = and the low
//            quality tier: the edge is not widened, 1 tap instead of 5))
uniform vec4 OvTrunk;
// How much wider the shadow edge gets per block along the sun line (blocks; the true sun would be 0.0093, a block world reads better
// with more), and the widest edge.
const float OV_PENUMBRA = 0.05;
const float OV_PENUMBRA_MAX = 2.0;

// Brightness of the sky light step level (0 to 15) as the lifted lightmap draws it, relative to step 15 (which is 1).
float ovLiftedSky(float level) {
    float t = clamp(level / 7.0, 0.0, 1.0);
    float gate = mix(OvLift.y, 1.0, t * t * (3.0 - 2.0 * t));
    float f = clamp((level + OvLift.x * gate * (15.0 - level)) / 15.0, 0.0, 1.0);
    float b = f / (4.0 - 3.0 * f);
    float g = 1.0 - (1.0 - b) * (1.0 - b) * (1.0 - b) * (1.0 - b);
    return mix(b, g, clamp(OvLift.w, 0.0, 1.0));
}

// The trunk band of one map texel (0 to 1) at height h.
float ovTrunkAt(ivec2 cell, float h) {
    vec4 m = texelFetch(OvSunMap, cell, 0);
    float solidTop = m.r * 255.0;
    if (m.a < 0.25 || solidTop < 0.5 || h >= solidTop - 1.5) {
        return 0.0;
    }
    float reach = max(OvTrunk.y, 2.0);
    return 1.0 - smoothstep(reach * (1.0 - clamp(OvTrunk.z, 0.0, 1.0)), reach + 0.001, solidTop - h);
}

// The shadow at map position uv (texels, bilinear over the 4 texels around it), 0 outside the map.
float ovTrunkBand(vec2 uv, float h) {
    float size = OvMapA.z;
    uv = clamp(uv, vec2(0.0), vec2(size - 1.001));
    ivec2 c = ivec2(floor(uv));
    vec2 f = uv - floor(uv);
    ivec2 c1 = min(c + ivec2(1), ivec2(int(size) - 1));
    float t00 = ovTrunkAt(c, h);
    float t10 = ovTrunkAt(ivec2(c1.x, c.y), h);
    float t01 = ovTrunkAt(ivec2(c.x, c1.y), h);
    float t11 = ovTrunkAt(c1, h);
    return mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
}

// The shadow with its edge widened by the distance to the caster along the sun line (5 taps when the edge is wider than a quarter block).
float ovTerrainShadow(vec2 uv, float h) {
    float center = ovTrunkBand(uv, h);
    if (OvTrunk.w > 1.5) {
        return center;
    }
    vec4 m = texelFetch(OvSunMap, ivec2(clamp(floor(uv + 0.5), vec2(0.0), vec2(OvMapA.z - 1.0))), 0);
    float drop = max(m.r * 255.0 - h, 0.0);
    float r = min(drop * sqrt(1.0 + dot(OvSunA.xy, OvSunA.xy)) * OV_PENUMBRA, OV_PENUMBRA_MAX);
    if (r < 0.25) {
        return center;
    }
    return (center + ovTrunkBand(uv + vec2(r, 0.0), h) + ovTrunkBand(uv - vec2(r, 0.0), h)
            + ovTrunkBand(uv + vec2(0.0, r), h) + ovTrunkBand(uv - vec2(0.0, r), h)) * 0.2;
}

// How much ovCanopyLight found this fragment in canopy shade (0 to 1), for later effects (the cloud shadow must not shade it again).
float ovCanopyShade = 0.0;
// The colour factor the canopy shade and the trunk band put on this fragment (1 = none). 11-12 (user report: under a cloud shadow
// the ground under a tree was brighter than around it): the cloud shadow now takes the darker of the two (ov_cloud.glsl).
vec3 ovCanopyMul = vec3(1.0);

// Block light (0 to 1) from which a face counts as torch-lit and keeps its warm colour (fully at OV_TORCH_FULL).
const float OV_TORCH_FROM = 0.45;
const float OV_TORCH_FULL = 0.9;

// The colour of the sky light (11-05, light inspection 3; client/atmosphere/SunLight.skyTint): by the height of the sun, white by day,
// gold, then peach at sunset, a low-saturation blue-grey after it, white again at night (the night stays as it was). It goes on every
// face in the overgrown forest, also when there is no sun map (low sun): the dusk light is the light of the whole sky, not of the sun
// spots. Where the canopy is open (high sky light at the vertex) at full strength, under the canopy only OvSkyTintB.x of it, and less
// on torch-lit faces. The colour has brightness 1 (0.3 : 0.59 : 0.11), so it shifts the hue and never lights a face up.
// OvSkyTint = (colour rgb, strength in the open; 0 = off), OvSkyTintB = (share under the canopy, -, -, -).
uniform vec4 OvSkyTint;
uniform vec4 OvSkyTintB;
// Sky light (0 to 1) from which a vertex counts as more open than "under the canopy", and where it is fully open.
const float OV_SKYTINT_FROM = 10.0 / 15.0;
const float OV_SKYTINT_TO = 1.0;
// The sky tint of this fragment (set by ovCanopyLight).
vec3 ovSkyTintNow = vec3(1.0);

vec3 ovSkyTintAt(vec2 light) {
    if (OvSkyTint.w <= 0.0) {
        return vec3(1.0);
    }
    float open = mix(OvSkyTintB.x, 1.0, smoothstep(OV_SKYTINT_FROM, OV_SKYTINT_TO, light.y));
    float k = OvSkyTint.w * open * (1.0 - smoothstep(OV_TORCH_FROM, OV_TORCH_FULL, light.x));
    return mix(vec3(1.0), OvSkyTint.rgb, k);
}

float ovShadowAt(ivec2 cell, float h) {
    vec4 m = texelFetch(OvSunMap, cell, 0);
    if (m.a < 0.25) {
        return 0.0;
    }
    float solidTop = m.r * 255.0;
    float leafTop = m.g * 255.0;
    if (solidTop > 0.5 && h < solidTop - 1.5) {
        return 1.0;
    }
    if (leafTop > 0.5 && m.b > 0.01 && h < leafTop - 1.0) {
        return 1.0;
    }
    return 0.0;
}

float ovHash(vec2 p) {
    p = mod(p, 64.0);
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float ovNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    float a = ovHash(i);
    float b = ovHash(i + vec2(1.0, 0.0));
    float c = ovHash(i + vec2(0.0, 1.0));
    float d = ovHash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// The night (11-05, light inspection 4 "the night is a flat grey-green"; client/atmosphere/CanopySunMap.java sets these). At night
// OvSunDir holds the moon direction (always, also without a map). Night only (OvMoon.x = night weight times the forest presence, 0 by
// day and at dusk, so the day stays as it was).
// OvMoon  = (night weight, strength of the moon facing shade (moon height times phase, new moon 0.3 of it), desaturation of the
//            places the moon does not reach (times this), moon light for the blue-grey tint (moon height times phase, 0 at new moon))
// OvMoonB = (combined brightness of a face on the moon side, on the far side, facing down; facing up toward the moon is 1, -)
// OvMoonC = (colour of the moonlit places in the moon map (brightness 1), strength of the moon map)
uniform vec4 OvMoon;
uniform vec4 OvMoonB;
uniform vec4 OvMoonC;

// Brightness the moon gives a face with normal n, divided by the face shade baked into the vertex colour (up 1.0, down 0.5,
// north/south 0.8, east/west 0.6), so face shade and this together give the OvMoonB ratios. d = dot(n, moon direction): 1 = 1.0,
// 0.5 = moon side, -0.5 = far side, -1 = down. Never brightens a face (at most 1).
float ovMoonFace(vec3 n, float d) {
    float t;
    if (d >= 0.5) {
        t = mix(OvMoonB.x, 1.0, (d - 0.5) * 2.0);
    } else if (d >= -0.5) {
        t = mix(OvMoonB.y, OvMoonB.x, d + 0.5);
    } else {
        t = mix(OvMoonB.z, OvMoonB.y, (d + 1.0) * 2.0);
    }
    float baked = n.y > 0.5 ? 1.0 : (n.y < -0.5 ? 0.5 : (abs(n.x) > 0.5 ? 0.6 : 0.8));
    return min(t / baked, 1.0);
}

// Colour of a terrain fragment at camera-relative position pos with world normal n. facing > 0 also darkens faces that
// look away from the sun (not for the cutout layer: grass and ferns are thin and lit through). light = (block light,
// sky light) from 0 to 1.
vec3 ovCanopyLight(vec3 color, vec3 pos, vec3 n, float facing, vec2 light) {
    ovSkyTintNow = ovSkyTintAt(light);
    // The night (OvMoon.x) goes on everywhere since 11-06, also with no map and no forest hue (the plain at night).
    // The map may be there for the terrain shadow only (OvTrunk.w, in the open with no canopy: OvSunA.z = 0 then).
    bool mapOn = OvSunA.z > 0.0 || OvTrunk.w > 0.5;
    if (!mapOn && OvTint.w <= 0.0 && OvMoon.x <= 0.0) {
        return color * ovSkyTintNow;
    }
    float shade = 0.0;
    float fleck = 0.0;
    float trunk = 0.0;
    // How much the map knows whether this fragment is in the sun (0 = unknown: outside the map, map off), and whether the
    // map traced it outside the overgrown area (no hue there).
    float known = 0.0;
    float knownMoon = 0.0;
    float outside = 0.0;
    vec2 uvRel = vec2(pos.x - pos.y * OvSunA.x, pos.z - pos.y * OvSunA.y);
    vec2 uv = uvRel - OvMapA.xy - 0.5;
    float size = OvMapA.z;
    // Never read the map while it is off (its sampler slot may hold another texture then).
    if (mapOn && uv.x >= 0.0 && uv.y >= 0.0 && uv.x < size - 1.0 && uv.y < size - 1.0) {
        ivec2 c = ivec2(floor(uv));
        vec2 f = uv - floor(uv);
        float h = pos.y - OvMapA.w;
        // Fade out over the last 6 texels of the map (no hard edge where the map ends).
        float edge = smoothstep(0.0, 6.0, min(min(uv.x, uv.y), min(size - 1.0 - uv.x, size - 1.0 - uv.y)));
        if (OvTrunk.x > 0.0 && OvSunA.w > 0.0) {
            // Faces turned to the sun take no shadow (only up faces, faces turned away, and grass and fronds); 11-06 inspection 21.
            float toSun = (facing > 0.0 && n.y < 0.7) ? 1.0 - max(dot(n, OvSunDir.xyz), 0.0) : 1.0;
            trunk = ovTerrainShadow(uv, h) * edge * clamp(OvSunA.w, 0.0, 1.0) * toSun;
        }
      // With no canopy (OvSunA.z = 0, the map is there for the terrain shadow only) the canopy shade, the flecks and the facing-away
      // shade all come to nothing (strength 0, no forest hue, no warm colour), so skip their map reads (11-06, about 0.2 ms a frame).
      if (OvSunA.z > 0.0) {
        float s00 = ovShadowAt(c, h);
        float s10 = ovShadowAt(c + ivec2(1, 0), h);
        float s01 = ovShadowAt(c + ivec2(0, 1), h);
        float s11 = ovShadowAt(c + ivec2(1, 1), h);
        shade = mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
        shade *= edge;
        vec4 m = texelFetch(OvSunMap, ivec2(floor(uv + 0.5)), 0);
        bool inside = m.a > 0.25;
        known = inside ? edge * OvSunA.w : 0.0;
        // At night the map follows the moon (OvSunA.w stays 0: no leaf light, no gold).
        knownMoon = inside ? edge * step(0.001, OvMoonC.w) : 0.0;
        outside = (m.a > 0.25 && m.a < 0.75) ? edge : 0.0;
        // Sunflecks under thin foliage (1 to 3 leaves, nothing solid above), not on the top of the ground.
        float leaves = m.b * 15.0;
        bool underLeaves = m.g * 255.0 > 0.5 && h < m.g * 255.0 - 1.0;
        bool underSolid = m.r * 255.0 > 0.5 && h < m.r * 255.0 - 1.5;
        if (OvFleck.w > 0.0 && inside && underLeaves && !underSolid && leaves > 0.5 && leaves < 3.5 && n.y < 0.7) {
            vec2 p = uvRel * 1.7 + OvFleck.xy;
            float noise = ovNoise(p) * 0.7 + ovNoise(p * 2.0 + 17.0) * 0.3;
            float threshold = leaves < 1.5 ? 0.6 : (leaves < 2.5 ? 0.68 : 0.75);
            float spot = smoothstep(threshold, threshold + 0.06, noise);
            // Each fleck flickers on its own (leaves fluttering in front of it).
            float flicker = 0.7 + 0.3 * sin(OvFleck.z * 4.0 + ovHash(floor(p)) * 6.2832);
            float bright = leaves < 1.5 ? 1.0 : (leaves < 2.5 ? 0.8 : 0.55);
            // Solid faces and leaves only catch a fleck when they face the sun (a wall under a noon sun only gets grazing light);
            // grass and fronds are thin and glow through whatever their angle.
            float incidence = facing > 0.0 ? smoothstep(0.05, 0.45, dot(n, OvSunDir.xyz)) : 1.0;
            fleck = spot * bright * flicker * incidence * OvFleck.w * edge;
            shade *= 1.0 - fleck;
        }
        // Faces looking away from the sun are in their own shade, but only inside the traced overgrown area.
        if (facing > 0.0 && inside) {
            float nl = dot(n, OvSunDir.xyz);
            shade = max(shade, facing * OvSunDir.w * (1.0 - smoothstep(-0.25, -0.02, nl)) * edge);
        }
      }
    }
    ovCanopyShade = OvSunA.z > 0.0 ? clamp(shade, 0.0, 1.0) : 0.0;
    float shadeAmount = OvSunA.z > 0.0 ? shade * OvSunA.z : 0.0;
    // Hue: on the shade where the map knows the sun, on everything where it does not (while in the forest); never where the
    // map traced outside the overgrown area, and less on torch-lit faces.
    float notLit = mix(1.0, shade, known);
    float hue = max(OvTint.w * notLit * (1.0 - outside), shadeAmount);
    float torch = 1.0 - smoothstep(OV_TORCH_FROM, OV_TORCH_FULL, light.x);
    hue *= torch;
    // The night (11-05, light inspection 4): how much moonlight reaches this fragment (facing the moon, and in the open by its sky
    // light, or in the moonlight of the moon map). Only there the night hue (blue-grey); the dark gets less colour instead.
    float moonLit = 0.0;
    float moonFace = 1.0;
    if (OvMoon.x > 0.0) {
        float d = dot(n, OvSunDir.xyz);
        moonFace = facing > 0.0 ? mix(1.0, ovMoonFace(n, d), OvMoon.y * torch) : 1.0;
        float faceLit = facing > 0.0 ? smoothstep(-0.2, 0.8, d) : 1.0;
        float open = mix(smoothstep(0.25, 0.9, light.y), 1.0 - clamp(shade, 0.0, 1.0), knownMoon);
        moonLit = clamp(faceLit * open * OvMoon.w, 0.0, 1.0);
        hue *= mix(1.0, moonLit, OvMoon.x);
    }
    vec3 lit = color * mix(vec3(1.0), OvShadeColor.rgb, shadeAmount) * mix(vec3(1.0), OvTint.rgb, hue) * ovSkyTintNow * moonFace;
    if (OvMoon.x > 0.0) {
        // Moonlit places of the moon map take the moon colour (white to blue-grey, brightness 1).
        lit *= mix(vec3(1.0), OvMoonC.rgb, (1.0 - clamp(shade, 0.0, 1.0)) * knownMoon * OvMoonC.w * torch);
        // Where the moon does not reach, the colour fades toward grey (no grey-green night).
        float sat = mix(OvMoon.z, 1.0, moonLit);
        lit = mix(lit, mix(vec3(dot(lit, vec3(0.299, 0.587, 0.114))), lit, sat), OvMoon.x * torch);
    }
    // Sunlit faces are warm white to gold (where the map knows they are in the sun): more contrast with the green shade.
    float sunlit = (1.0 - clamp(shade, 0.0, 1.0)) * known * (1.0 - outside) * clamp(OvSunTint.w, 0.0, 2.0);
    lit *= mix(vec3(1.0), OvSunTint.rgb, min(sunlit, 1.0));
    // The trunk band (11-06 light inspection 21): a darker band behind trunks and rocks, on top of the canopy shade, not on torch-lit faces.
    // Its strength (OvTrunk.x) already holds the daylight; it does not follow the canopy (it is the shadow of the open land too).
    float trunkK = clamp(trunk, 0.0, 1.0) * clamp(OvTrunk.x, 0.0, 0.9) * torch;
    lit *= 1.0 - trunkK;
    ovCanopyShade = max(ovCanopyShade, trunkK);
    ovCanopyMul = mix(vec3(1.0), OvShadeColor.rgb, shadeAmount) * (1.0 - trunkK);
    float fleckK = fleck * clamp(OvSunA.z, 0.0, 1.0);
    if (fleckK <= 0.0) {
        return lit;
    }
    // By day a fleck is a hole in the shade (11-06 light inspection 21): toward the colour of the same face in the sun (no canopy shade,
    // sky light 15 without lift, the warm sunlit colour where the map knows the sun), never past it. Block light keeps its own share.
    float skyNow = ovLiftedSky(light.y * 15.0);
    float blockNow = light.x / (4.0 - 3.0 * light.x);
    float lightRatio = clamp(max(skyNow, blockNow), 0.05, 1.0);
    float warmK = min(known * (1.0 - outside) * clamp(OvSunTint.w, 0.0, 2.0), 1.0);
    vec3 sunColor = color * ovSkyTintNow * mix(vec3(1.0), OvSunTint.rgb, warmK) / lightRatio;
    vec3 dayLit = mix(lit, max(lit, sunColor), fleckK);
    // At night a fleck is moonlight as before (11-05, light inspection 4): a small lift in the moon colour.
    vec3 nightLit = lit * (1.0 + fleckK * OvMoonC.rgb * 0.2);
    return mix(dayLit, nightLit, clamp(OvMoon.x, 0.0, 1.0));
}
