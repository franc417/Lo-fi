// Present: draw the final virtual-resolution texture to the screen / export target.
// Upscale filtering (nearest or linear) is a property of the texture, set per look.
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }
