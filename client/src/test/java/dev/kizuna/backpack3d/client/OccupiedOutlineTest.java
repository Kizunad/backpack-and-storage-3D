package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.backpack3d.client.OccupiedOutline.Axis;
import dev.kizuna.backpack3d.client.OccupiedOutline.Edge;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;

import org.junit.jupiter.api.Test;

import java.util.List;

class OccupiedOutlineTest {
    @Test
    void adjacentItemsHaveOneOuterOutlineWithoutSharedFaceEdges() {
        var edges = outline(List.of(item(0, 0, 0), item(1, 0, 0)));
        assertEquals(16, edges.size());
        assertFalse(edges.contains(new Edge(1, 0, 0, Axis.Y)));
        assertFalse(edges.contains(new Edge(1, 0, 0, Axis.Z)));
        assertTrue(edges.contains(new Edge(0, 0, 0, Axis.X)));
        assertTrue(edges.contains(new Edge(1, 0, 0, Axis.X)));
    }

    @Test
    void multiCellItemHasOnlyCuboidOutline() {
        var box = item(0, 0, 0).toBuilder().setSize(Volumes.size(2, 2, 2)).build();
        var edges = outline(List.of(box));
        assertEquals(24, edges.size());
        assertFalse(edges.contains(new Edge(1, 1, 0, Axis.Z)));
        assertFalse(edges.contains(new Edge(1, 0, 0, Axis.Y)));
    }

    @Test
    void concaveCornersAndDiagonalContactsRemainVisible() {
        var corner = new Edge(1, 1, 0, Axis.Z);
        assertTrue(outline(List.of(item(0, 0, 0), item(1, 0, 0), item(0, 1, 0))).contains(corner));
        assertTrue(outline(List.of(item(0, 0, 0), item(1, 1, 0))).contains(corner));
    }

    @Test
    void emptyMiddleCellKeepsItsHoleOutline() {
        var items = new java.util.ArrayList<Item>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x != 1 || z != 1) {
                    items.add(item(x, 0, z));
                }
            }
        }
        assertTrue(outline(items).contains(new Edge(1, 0, 1, Axis.Y)));
        assertTrue(outline(items).contains(new Edge(1, 1, 1, Axis.X)));
    }

    @Test
    void sliceAddsBoundaryAndExcludesOtherContainers() {
        var range = new LayerRange();
        range.resize(3);
        range.moveLower(1);
        range.moveUpper(2);
        var tall = item(0, 0, 0).toBuilder().setSize(Volumes.size(1, 3, 1)).build();
        var other = item(1, 1, 0).toBuilder().setContainer("other").build();
        var edges = OccupiedOutline.build(List.of(tall, other), "bag", range);
        assertEquals(12, edges.size());
        assertTrue(edges.stream().allMatch(edge -> edge.y() >= 1 && edge.endY() <= 2));
        assertTrue(edges.contains(new Edge(1, 1, 0, Axis.Z)));
    }

    private static List<Edge> outline(List<Item> items) {
        var range = new LayerRange();
        range.resize(3);
        return OccupiedOutline.build(items, "bag", range);
    }

    private static Item item(int x, int y, int z) {
        return Item.newBuilder()
                .setId(x + ":" + y + ":" + z)
                .setContainer("bag")
                .setCell(Volumes.cell(x, y, z))
                .setSize(Volumes.UNIT)
                .build();
    }
}
