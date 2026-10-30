#version 150

// Overgrown Vegetation: full-screen color grade (client/light/MoodPost.java holds the explanation). Keep this file ASCII-only.
// The quad is already in clip space.

in vec3 Position;

out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
