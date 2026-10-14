package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class BbModelTest {
    @TempDir Path directory;

    @Test
    void bundledModelsKeepGeometryTexturesAndNamedLidAnimations() throws Exception {
        for (String name : new String[] {"GrassPouch", "BackBasket"}) {
            JsonObject json = bundled(name);
            BbModel model = read(json, null, null);
            assertEquals(json.getAsJsonArray("elements").size(), model.cubes().size());
            assertEquals(1, model.textures().size());
            assertEquals(0.45f, model.open().seconds());
            var hinge = model.open().tracks().keySet().iterator().next();
            assertEquals(-54, model.open().sample(hinge, "rotation", 0.5f).x);
            assertEquals(0, model.close().sample(hinge, "rotation", 1).x);
            ModelAsset asset = new ModelAsset(model);
            assertTrue(asset.groundOffset() > 0);
            assertArrayEquals(
                    asset.bounds(new MatrixStack(), new BagAnimation.Sample(false, 0)),
                    asset.bounds(new MatrixStack(), new BagAnimation.Sample(true, 1)),
                    0.001f);
        }
    }

    @Test
    void staticModelOpensWithoutWaitingForAnInventedAnimation() throws Exception {
        JsonObject json = bundled("GrassPouch");
        json.remove("animations");
        BbModel model = read(json, null, null);
        var animation = new BagAnimation();
        animation.configure(new ModelAsset(model));
        animation.open();
        animation.advance(0);
        assertTrue(animation.ready());
        animation.close();
        animation.advance(0);
        assertTrue(animation.atRest());
    }

    @Test
    void missingClosingClipUsesOpeningClipInReverse() throws Exception {
        JsonObject json = bundled("GrassPouch");
        json.getAsJsonArray("animations").remove(1);
        ModelAsset asset = new ModelAsset(read(json, null, null));
        assertEquals(asset.openSeconds(), asset.closeSeconds());
        assertArrayEquals(
                asset.bounds(new MatrixStack(), new BagAnimation.Sample(false, 0.25f)),
                asset.bounds(new MatrixStack(), new BagAnimation.Sample(true, 0.75f)),
                0.001f);
    }

    @Test
    void explicitMissingClipAndUnsupportedGeometryFailClearly() throws Exception {
        JsonObject json = bundled("GrassPouch");
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> read(json, "wrong", null))
                        .getMessage()
                        .contains("wrong"));
        json.getAsJsonArray("elements").get(0).getAsJsonObject().addProperty("type", "mesh");
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> read(json, null, null))
                        .getMessage()
                        .contains("mesh"));
    }

    @Test
    void nestedBonesUvRotationAndUntexturedFacesArePreserved() throws Exception {
        JsonObject json = bundled("GrassPouch");
        var first = json.getAsJsonArray("elements").get(0).getAsJsonObject();
        var faces = first.getAsJsonObject("faces");
        faces.getAsJsonObject("west").addProperty("rotation", 90);
        faces.getAsJsonObject("east").add("texture", null);
        BbModel model = read(json, null, null);
        BbModel.Cube cube = model.cubes().get(0);
        assertEquals(1, cube.faces().get(0).rotation());
        assertNull(cube.faces().get(1));
        assertTrue(model.cubes().stream().anyMatch(value -> value.bones().size() == 2));
    }

    @Test
    void keyframesRejectExpressionsAndUnsupportedInterpolation() throws Exception {
        JsonObject json = bundled("GrassPouch");
        var frame = firstFrame(json);
        frame.getAsJsonArray("data_points")
                .get(0)
                .getAsJsonObject()
                .addProperty("x", "query.life_time");
        assertThrows(IllegalArgumentException.class, () -> read(json, null, null));
        frame.getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("x", "0");
        frame.addProperty("interpolation", "bezier");
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> read(json, null, null))
                        .getMessage()
                        .contains("bezier"));
    }

    @Test
    void stepKeysHoldUntilTheirNextTimestamp() throws Exception {
        JsonObject json = bundled("GrassPouch");
        firstFrame(json).addProperty("interpolation", "step");
        ModelClip clip = read(json, null, null).open();
        String bone = clip.tracks().keySet().iterator().next();
        assertEquals(0, clip.sample(bone, "rotation", 0.99f).x);
        assertEquals(-108, clip.sample(bone, "rotation", 1).x);
    }

    @Test
    void brokenPngAndOggHeadersAreRejected() throws Exception {
        JsonObject json = bundled("GrassPouch");
        json.getAsJsonArray("textures")
                .get(0)
                .getAsJsonObject()
                .addProperty("source", "data:image/png;base64,YmFk");
        assertThrows(IllegalArgumentException.class, () -> read(json, null, null));
        Path sound = directory.resolve("invalid.ogg");
        Files.writeString(sound, "OggS........OpusHead");
        assertThrows(IllegalArgumentException.class, () -> ModelSounds.readOgg(sound));
    }

    private BbModel read(JsonObject json, String open, String close) throws Exception {
        Path file = directory.resolve("bag.bbmodel");
        Files.writeString(file, json.toString());
        return BbModel.read(
                directory,
                new ModelConfig.Entry(
                        new Identifier("example", "bag"), file, open, close, Map.of()));
    }

    private static JsonObject bundled(String name) throws Exception {
        try (var stream =
                BbModelTest.class.getResourceAsStream(
                        "/bs3d/defaults/models/" + name + ".bbmodel")) {
            return JsonParser.parseString(
                            new String(
                                    stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static JsonObject firstFrame(JsonObject json) {
        return json.getAsJsonArray("animations")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("animators")
                .entrySet()
                .iterator()
                .next()
                .getValue()
                .getAsJsonObject()
                .getAsJsonArray("keyframes")
                .get(0)
                .getAsJsonObject();
    }
}
