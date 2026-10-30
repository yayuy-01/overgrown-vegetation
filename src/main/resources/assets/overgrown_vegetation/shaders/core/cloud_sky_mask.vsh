#version 150

// VoxelClouds far pass after the terrain (client/VoxelClouds.java, 2026-10-24). Keep this file ASCII-only.
// A full-screen quad: Position is already in clip space.

in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
