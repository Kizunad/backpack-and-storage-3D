package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LayerRangeTest {
    @Test
    void middleLayerRemainsVisibleWhenHandlesTryToCross() {
        var range = new LayerRange();
        range.resize(3);
        range.moveLower(1);
        range.moveUpper(2);
        range.moveLower(3);
        range.moveUpper(0);
        assertEquals(1, range.lower());
        assertEquals(2, range.upper());
        assertTrue(range.contains(1, 1));
        assertFalse(range.contains(0, 1));
        assertFalse(range.contains(1, 2));
    }

    @Test
    void tallItemsIntersectSliceWithoutBeingValidPlacementCandidates() {
        var range = new LayerRange();
        range.resize(3);
        range.moveLower(1);
        range.moveUpper(2);
        assertTrue(range.intersects(0, 3));
        assertFalse(range.contains(0, 3));
        assertFalse(range.intersects(0, 1));
        assertFalse(range.intersects(2, 1));
    }

    @Test
    void resizePreservesSelectionAndKeepsOneLayerInSmallContainers() {
        var range = new LayerRange();
        range.resize(3);
        assertTrue(range.full());
        range.moveLower(1);
        range.moveUpper(2);
        range.resize(5);
        assertEquals(2, range.upper());
        range.resize(1);
        range.moveLower(999);
        range.moveUpper(-999);
        assertEquals(0, range.lower());
        assertEquals(1, range.upper());
        assertTrue(range.full());
        assertThrows(IllegalArgumentException.class, () -> range.resize(0));
    }

    @Test
    void sliderDragOutsideBoundsClampsWithoutChangingOtherContainer() {
        var range = new LayerRange();
        range.resize(3);
        var other = new LayerRange();
        other.resize(3);
        var slider = new LayerRangeSlider(range);
        slider.layout(new InteriorRenderer.Rect(0, 0, 170, LayerRangeSlider.HEIGHT));
        slider.press(8, 18);
        slider.drag(48);
        assertEquals(1, range.lower());
        slider.press(128, 8);
        slider.drag(-100);
        assertEquals(2, range.upper());
        assertTrue(other.full());
        slider.drag(1000);
        assertEquals(3, range.upper());
    }
}
