// Overgrown Vegetation: the giant trees seen from outside the forest (11-13; client/atmosphere/CanopySunMap.java sets the uniforms,
// client/atmosphere/GiantTrees.java knows the trees). Keep this file ASCII-only.
//
// Under the crown of a giant tree the sky light is 0 (the crown is tens of blocks wide), so its trunk, branches and the leaves on
// them are drawn black. Inside the forest the lift of the lightmap (client/atmosphere/CanopyLift) brings them back, but that lift
// follows the cover over the camera: from the open land outside, the landmark tree was a black block against a bright sky. Here the
// vertices inside the crown cylinder of a known giant tree, well above the forest under it, read the lightmap at no less than a sky
// light floor. Only the tree: the forest floor under it stays in its shade, caves and overhangs are untouched. The floor goes to 0
// while the camera is under a cover (the lightmap lift takes over; no double lift) and at night (a dark shape against the night sky).
//
// OvGiant0..3 = (axis x, z relative to the camera, crown radius, base y relative to the camera) of the nearest trees; radius 0 = none.
// OvGiantK    = (sky light floor now 0..1; 0 = off, height above the base where the floor starts, height where it is full, -)
uniform vec4 OvGiant0;
uniform vec4 OvGiant1;
uniform vec4 OvGiant2;
uniform vec4 OvGiant3;
uniform vec4 OvGiantK;

float ovGiantOne(vec3 pos, vec4 g) {
    if (g.z <= 0.0) {
        return 0.0;
    }
    float r = length(pos.xz - g.xy);
    return (1.0 - smoothstep(g.z - 6.0, g.z, r)) * smoothstep(OvGiantK.y, OvGiantK.z, pos.y - g.w);
}

// The sky light floor (0..1 of the 15 levels) of a vertex at camera-relative position pos.
float ovGiantFloor(vec3 pos) {
    if (OvGiantK.x <= 0.0) {
        return 0.0;
    }
    return OvGiantK.x * max(max(ovGiantOne(pos, OvGiant0), ovGiantOne(pos, OvGiant1)),
                            max(ovGiantOne(pos, OvGiant2), ovGiantOne(pos, OvGiant3)));
}
