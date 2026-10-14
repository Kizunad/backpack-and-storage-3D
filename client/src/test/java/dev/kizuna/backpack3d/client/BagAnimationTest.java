package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BagAnimationTest {
    @Test
    void lidOpensImmediatelyButPopupWaitsForFullOpening() {
        var animation = new BagAnimation();
        animation.open();
        animation.advance(0.05f);
        assertTrue(animation.lid() > 0);
        assertFalse(animation.ready());
        for (int frame = 0; frame < 30; frame++) {
            animation.advance(0.05f);
        }
        assertTrue(animation.ready());
        assertEquals(1, animation.lid());
    }

    @Test
    void interruptedOpeningClosesFromCurrentProgress() {
        var animation = new BagAnimation();
        animation.open();
        for (int frame = 0; frame < 4; frame++) {
            animation.advance(0.05f);
        }
        assertTrue(animation.lid() > 0);
        float beforeClosing = animation.lid();
        var poseBefore = animation.sample();
        animation.close();
        assertEquals(poseBefore, animation.sample());
        animation.advance(0.05f);
        assertTrue(animation.lid() > 0 && animation.lid() < beforeClosing);
        assertFalse(animation.ready());
        for (int frame = 0; frame < 30; frame++) {
            animation.advance(0.05f);
        }
        assertTrue(animation.atRest());
    }
}
