#version 150

// Overgrown Vegetation: the colours of the sky (client/sky/SkyColors.java holds the explanation). Keep this file ASCII-only.
// A quad over the whole screen; the view ray of every pixel comes from the inverse of projection * camera rotation.

in vec3 Position;

uniform mat4 InvViewProj;

out vec3 ray;

void main() {
    gl_Position = vec4(Position.xy, 1.0, 1.0);
    vec4 far = InvViewProj * vec4(Position.xy, 1.0, 1.0);
    vec4 near = InvViewProj * vec4(Position.xy, -1.0, 1.0);
    ray = far.xyz / far.w - near.xyz / near.w;
}
