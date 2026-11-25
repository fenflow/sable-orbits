package space.sableorbit;

import com.hbm.client.model.WavefrontObj;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ntmspace.client.render.rocket.RocketPronter;
import com.ntmspace.rocket.RocketPart;
import dev.golem.core.render.RecordingSubmitSink;
import dev.golem.core.render.StaticMeshes;
import dev.golem.core.render.gem.Geometry;
import dev.golem.core.render.gem.Surface;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/** The renderer instance and its OBJ cache are recreated together on a resource reload. */
final class RocketPartModels {
    private record Mesh(Geometry geometry, float width, float height, float x, float y, float z) {
        static Mesh of(WavefrontObj obj) {
            var min = obj.boundsMin();
            var max = obj.boundsMax();
            return new Mesh(obj.all(), Math.max(.001f, Math.max(max[0] - min[0], max[2] - min[2])),
                Math.max(.001f, max[1] - min[1]), (min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2);
        }
    }

    private final RocketPronter pronter = new RocketPronter();
    private final Map<ResourceLocation, Mesh> meshes = new HashMap<>();
    private final Map<ResourceLocation, Surface> surfaces = new HashMap<>();

    void render(RocketPart part, float width, float height, PoseStack pose, MultiBufferSource buffers, Level level,
                int light, int overlay) {
        var mesh = meshes.computeIfAbsent(part.getModel(false), model -> Mesh.of(pronter.mesh(model)));
        var surface = surfaces.computeIfAbsent(part.skin(), skin -> Surface.cutout(skin.toString()));
        float horizontal = width / mesh.width;
        pose.scale(horizontal, height / mesh.height, horizontal);
        pose.translate(-mesh.x, -mesh.y, -mesh.z);
        if (StaticMeshes.active()) {
            var sink = new RecordingSubmitSink();
            StaticMeshes.submit(sink, pose, surface, mesh.geometry, light, overlay, -1);
            sink.flush(buffers, level);
        } else {
            drawBuffered(mesh.geometry, pose.last(), buffers.getBuffer(surface.renderType()), light, overlay);
        }
    }

    private static void drawBuffered(Geometry geometry, PoseStack.Pose pose, VertexConsumer output, int light, int overlay) {
        // Shader packs still use the normal render type. Replay the cached triangulation as
        // degenerate quads, which is the same primitive and winding as HBM's immediate OBJ path.
        var vertices = geometry.vertices();
        var triangles = geometry.indices();
        var position = new Vector3f();
        var normal = new Vector3f();
        for (int index = 0; index < triangles.length; index += 3) {
            for (int vertex = 0; vertex < 3; vertex++) {
                int offset = triangles[index + vertex] * Geometry.FLOATS_PER_VERTEX;
                pose.pose().transformPosition(vertices[offset], vertices[offset + 1], vertices[offset + 2], position);
                pose.transformNormal(vertices[offset + 5], vertices[offset + 6], vertices[offset + 7], normal);
                float u = vertices[offset + 3], v = vertices[offset + 4];
                output.addVertex(position.x, position.y, position.z, -1, u, v, overlay, light, normal.x, normal.y, normal.z);
                if (vertex == 2)
                    output.addVertex(position.x, position.y, position.z, -1, u, v, overlay, light, normal.x, normal.y, normal.z);
            }
        }
    }
}
