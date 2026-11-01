#version 150

// Overgrown Vegetation: cave mode of the overgrown area (2026-11-01 prototype; client/atmosphere/CaveLights.java and CaveMap.java hold
// the explanation). Keep this file ASCII-only.
//
// The black fog around the camera is vanilla fog (MistView sets the distance and a near-black colour), so mobs and particles fade the same.
// Here: the point lights of glowing cave plants, the glowing pixels of those plants left out of the fog, and (11-01 evening) the
// per-fragment cave factor from the cave map, so that a cave seen from outside sinks into black and the bright outside seen from a cave
// keeps its daylight.
//
// OvCaveLights: 25 x 2 float texture. Row 0 = (light position relative to the camera xyz, radius r),
//               row 1 = (light colour rgb, strength). Column 24: row 0 = (ambient colour rgb x ambient, clip knee),
//               row 1 = (ambient desaturation, clip desaturation, 0, 0).
// OvCave = (number of lights, camera cave mix 0..1, light gain, brightness above which a texel of a light block glows)
// OvCaveMap: 64 x 64 float texture, one texel per block column around the camera: r = world y of the top of the highest rock
//            (not tree, not air or plants) in the column, g = 1 when the column is in the overgrown area.
// OvCaveMapA = (map origin x, map origin z relative to the camera, minus camera y, 1 when the map is ready)
// OvCaveMapB = (rock above from which a fragment starts to be underground, rock above where it is fully, sky light from, sky light to (0..1))
// OvCaveFog  = (cave fog colour rgb, -), OvCaveFogD = (cave fog start, end, 1 when the camera is in a fluid, -)
// OvCaveSurf = (spore haze colour of the forest rgb, haze per block): the haze an outside fragment gets while the camera is in a cave
//              (the black camera fog is taken off it, and the outside would otherwise show its bare, dim lightmap)
//
// A light adds albedo * colour * strength / (1 + (d/r)^2) to a face, faded to 0 between r and 1.5 r. Solid faces turned
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

// How much this fragment is underground (0..1). Without the map (or outside it) the camera's cave mix stands in.
// n points away from the face into the air it faces (the column on that side decides, so cliff faces stay outside).
float ovCaveUnder(vec3 pos, vec3 n, float sky) {
    if (OvCaveMapA.w < 0.5) {
        return OvCave.y;
    }
    vec3 p = pos + n * 0.5;
    ivec2 t = ivec2(floor(p.x - OvCaveMapA.x), floor(p.z - OvCaveMapA.y));
    if (t.x < 0 || t.y < 0 || t.x > 63 || t.y > 63) {
        return OvCave.y;
    }
    vec4 c = texelFetch(OvCaveMap, t, 0);
    if (c.g < 0.5) {
        return 0.0;
    }
    float rock = c.r + OvCaveMapA.z - p.y;
    float roof = smoothstep(OvCaveMapB.x, OvCaveMapB.y, rock);
    float dark = smoothstep(0.0, 1.0, clamp((OvCaveMapB.z - sky) / max(OvCaveMapB.z - OvCaveMapB.w, 0.01), 0.0, 1.0));
    return roof * dark;
}

vec3 ovCaveLight(vec3 color, vec3 albedo, vec3 pos, vec3 n, float facing, float under) {
    if (under <= 0.0) {
        return color;
    }
    int count = int(OvCave.x + 0.5);
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 24; i++) {
        if (i >= count) {
            break;
        }
        vec4 p = texelFetch(OvCaveLights, ivec2(i, 0), 0);
        vec3 to = p.xyz - pos;
        float d = length(to);
        float reach = 1.5 * p.w;
        if (d >= reach) {
            continue;
        }
        vec4 c = texelFetch(OvCaveLights, ivec2(i, 1), 0);
        float q = d / p.w;
        float att = c.w / (1.0 + q * q) * (1.0 - smoothstep(p.w, reach, d));
        float ndl = dot(n, to / max(d, 0.001));
        float lambert = mix(1.0, clamp(ndl * 0.75 + 0.25, 0.0, 1.0), facing);
        sum += c.rgb * att * lambert;
    }
    vec4 extra = texelFetch(OvCaveLights, ivec2(24, 0), 0);
    vec4 extra2 = texelFetch(OvCaveLights, ivec2(24, 1), 0);
    vec3 lum = vec3(0.2126, 0.7152, 0.0722);
    // The raised floor: grey-green, from the albedo with part of its saturation taken out.
    vec3 floorAlbedo = mix(albedo, vec3(dot(albedo, lum)), extra2.x);
    vec3 lit = color + (albedo * sum * OvCave.z + floorAlbedo * extra.rgb) * under;
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

// How much the texel is a glowing pixel of a light block (cave factor, or 0): inside the block of a light and brighter than OvCave.w.
float ovCaveGlow(vec3 texel, vec3 pos, float under) {
    // In water (OvCaveFogD.z) the glowing pixels sink into the water fog like everything else.
    if (OvCave.x < 0.5 || under <= 0.0 || OvCaveFogD.z > 0.5 || max(texel.r, max(texel.g, texel.b)) < OvCave.w) {
        return 0.0;
    }
    int count = int(OvCave.x + 0.5);
    for (int i = 0; i < 24; i++) {
        if (i >= count) {
            break;
        }
        vec3 a = abs(pos - texelFetch(OvCaveLights, ivec2(i, 0), 0).xyz);
        if (max(a.x, max(a.y, a.z)) < 0.52) {
            return under;
        }
    }
    return 0.0;
}

// The fog of a fragment by where it is, not where the camera is. fogged = the colour after the usual fogs (vanilla fog around the
// camera, which is black in cave mode), lit = the colour before any fog, surface = lit with only the height fog of the forest.
// A cave fragment seen while the camera is outside gets the black cave fog by its distance; an outside fragment seen while the camera
// is in a cave drops the black camera fog and keeps the surface look.
vec3 ovCaveFog(vec3 fogged, vec3 lit, vec3 surface, float dist, float under) {
    // The camera is in water (or another fluid): the fluid fog wins over the cave fog (11-01; a drowned cave seen from the sea was clear,
    // because the cave fog starts at 20 blocks and replaced the short water fog).
    if (OvCaveMapA.w < 0.5 || OvCaveFogD.z > 0.5) {
        return fogged;
    }
    float cam = OvCave.y;
    vec3 c = fogged;
    float toCave = under * (1.0 - cam);
    if (toCave > 0.0) {
        vec3 cave = mix(lit, OvCaveFog.rgb, smoothstep(OvCaveFogD.x, OvCaveFogD.y, dist));
        c = mix(c, cave, toCave);
    }
    float toSurface = (1.0 - under) * cam;
    if (toSurface > 0.0) {
        vec3 hazed = mix(surface, OvCaveSurf.rgb, 1.0 - exp(-OvCaveSurf.w * dist));
        c = mix(c, hazed, toSurface);
    }
    return c;
}
