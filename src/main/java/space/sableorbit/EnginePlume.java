package space.sableorbit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ntmspace.rocket.RocketPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class EnginePlume {
    // Offsets and bell radii are fractions of the rendered mesh width. Recess is a fraction of its height.
    private record Bell(float x, float z, float recess, float radius) {}
    private static List<Bell> ring(int count, double offset, double recess, double radius) {
        var bells = new ArrayList<Bell>(count);
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            bells.add(new Bell((float)(offset * Math.sin(angle)), (float)(offset * Math.cos(angle)),
                (float)recess, (float)radius));
        }
        return bells;
    }
    private static List<Bell> cluster(List<Bell> inner, List<Bell> outer) {
        var bells = new ArrayList<>(inner);
        bells.addAll(outer);
        return List.copyOf(bells);
    }
    // NTM reuses these bell meshes across several fuels and skins.
    private static final List<Bell> SINGLE = ring(1, 0, 0, 0.37);
    private static final Map<String, List<Bell>> BELLS = Map.ofEntries(
        Map.entry("models/missile_parts/mp_t_10_kerosene.obj", ring(1, 0, 0, 0.5)),
        Map.entry("models/missile_parts/mp_t_20_solid.obj", ring(1, 0, 0, 0.5)),
        Map.entry("models/missile_parts/mp_t_20_kerosene.obj", ring(1, 0, 0, 0.425)),
        Map.entry("models/missile_parts/mp_t_20_hydrazine.obj", ring(1, 0, 0, 0.425)),
        Map.entry("models/missile_parts/mp_t_15_balefire.obj", ring(1, 0, 0, 0.25)),
        Map.entry("models/missile_parts/mp_t_15_balefire_short.obj", ring(1, 0, 0, 0.25)),
        Map.entry("models/missile_parts/mp_t_15_kerosene_dual.obj", ring(2, 0.233, 0, 0.217)),
        Map.entry("models/missile_parts/mp_t_15_kerosene_triple.obj", ring(3, 0.267, 0, 0.217)),
        Map.entry("models/missile_parts/mp_t_20_kerosene_dual.obj", ring(2, 0.25, 0, 0.225)),
        Map.entry("models/missile_parts/mp_t_20_kerosene_triple.obj", ring(3, 0.275, 0, 0.225)),
        Map.entry("models/missile_parts/mp_t_15_solid_hexdecuple.obj", cluster(ring(4, 0.167, 0, 0.083), ring(12, 0.383, 0.25, 0.083))),
        Map.entry("models/missile_parts/mp_t_20_solid_multi.obj", cluster(cluster(ring(1, 0, 0, 0.0625), ring(6, 0.175, 0, 0.0625)),
            cluster(ring(16, 0.30, 0.125, 0.05), ring(24, 0.425, 0.125, 0.05))))
    );
    private static final RenderType PLUME = net.neoforged.neoforge.client.NeoForgeRenderTypes.getUnlitTranslucent(
        ResourceLocation.fromNamespaceAndPath("sableorbits", "textures/effect/engine_plume.png"));

    static float modelWidth(RocketPart part) {
        return (float)Math.max(1, Math.max(part.topRadius(), part.bottomRadius()));
    }
    static double maxLength(RocketPart part) { return 2.7 * modelWidth(part) + 0.5; }

    static void render(RocketPart part, PoseStack pose, MultiBufferSource buffers, float time, int phase) {
        var mesh = part.getModel(false);
        var bells = mesh == null ? SINGLE : BELLS.getOrDefault(mesh.getPath(), SINGLE);
        int sprite = switch (part.fuel()) {
            case "HYDROGEN" -> 1;
            case "METHALOX" -> 2;
            case "XENON" -> 3;
            case "BALEFIRE" -> 4;
            case "HYDRAZINE" -> 5;
            default -> 0;
        };
        var vertices = buffers.getBuffer(PLUME);
        float width = modelWidth(part);
        for (var bell : bells) {
            float radius = width * bell.radius * 0.88f;
            float pulse = (float)Math.sin(time * 1.2 + phase + 17 * bell.x + 7 * bell.z);
            float length = (radius * 5 + width * 0.22f) * (1 + 0.07f * pulse);
            pose.pushPose();
            pose.translate(bell.x * width, (bell.recess - 0.5) * part.height() + 0.015, bell.z * width);
            var transform = pose.last();
            jet(vertices, transform, sprite, radius, 0, length);
            jet(vertices, transform, sprite, 0, radius, length);
            // Keep the hot throat visible when looking straight up the exhaust axis.
            vertex(vertices, transform, -radius, -0.018f, -radius, 6, 0, 0);
            vertex(vertices, transform,  radius, -0.018f, -radius, 6, 1, 0);
            vertex(vertices, transform,  radius, -0.018f,  radius, 6, 1, 1);
            vertex(vertices, transform, -radius, -0.018f,  radius, 6, 0, 1);
            pose.popPose();
        }
    }

    private static void jet(VertexConsumer out, PoseStack.Pose pose, int sprite, float x, float z, float length) {
        vertex(out, pose, -x, 0, -z, sprite, 0, 0);
        vertex(out, pose,  x, 0,  z, sprite, 1, 0);
        vertex(out, pose,  x, -length, z, sprite, 1, 1);
        vertex(out, pose, -x, -length, -z, sprite, 0, 1);
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z,
                               int sprite, float u, float v) {
        out.addVertex(pose.pose(), x, y, z).setColor(-1).setUv((sprite + u) / 8, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, -1, 0);
    }
}
