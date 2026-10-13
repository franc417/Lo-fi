// Camera frame -> virtual-resolution texture (rotate, mirror, centre-crop, downscale).
in vec2 vUv;
out vec4 fragColor;

uniform INPUT_SAMPLER uTex;
uniform mat4 uTexMatrix;   // SurfaceTexture transform
uniform mat2 uRot;         // displayed -> buffer rotation (normalised coords)
uniform vec2 uCrop;        // centre-crop scale in displayed space (<= 1)
uniform float uMirror;     // 1.0 = mirror horizontally (front camera)

void main() {
    vec2 q = (vUv - 0.5) * uCrop + 0.5;
    if (uMirror > 0.5) q.x = 1.0 - q.x;
    vec2 st = uRot * (q - 0.5) + 0.5;
    vec2 tc = (uTexMatrix * vec4(st, 0.0, 1.0)).xy;
    fragColor = vec4(texture(uTex, tc).rgb, 1.0);
}
