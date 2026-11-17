package dev.kizuna.backpack3d.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenKeyHoldTest {
    private static final boolean DOWN = true;
    private static final boolean UP = false;

    @Test
    void tapClosesOnReleaseNotOnPress() {
        // 只按一下：按着的时候不关，松开才关，而且只关一次。
        var hold = new OpenKeyHold();
        hold.pressed();
        assertFalse(hold.released(DOWN));
        assertTrue(hold.released(UP));
        assertFalse(hold.released(UP));
    }

    @Test
    void holdUsedForQuickMoveDoesNotClose() {
        // 按着点了物品：这次按下只当修饰键，松开不关；下一次单按照常关。
        var hold = new OpenKeyHold();
        hold.pressed();
        hold.usedAsModifier();
        assertFalse(hold.released(DOWN));
        assertFalse(hold.released(UP));
        hold.pressed();
        assertTrue(hold.released(UP));
    }

    @Test
    void keyRepeatWhileHeldIsNotANewPress() {
        // 按住不放会不停收到重复按下：不能把「用过了」冲掉。
        var hold = new OpenKeyHold();
        hold.pressed();
        hold.usedAsModifier();
        hold.pressed();
        assertFalse(hold.released(UP));
    }

    @Test
    void pressThatOpenedTheScreenDoesNotCloseIt() {
        // 在世界里按下打开界面、一直没松：重复按下不算数，松开不关；松开以后再按一下才关。
        var hold = new OpenKeyHold();
        hold.opened(DOWN);
        hold.pressed();
        assertFalse(hold.released(DOWN));
        assertFalse(hold.released(UP));
        hold.pressed();
        assertTrue(hold.released(UP));
    }

    @Test
    void cancelDropsThePressWithoutClosing() {
        // 按着键切出窗口：这次按下作废，回来不关界面。
        var hold = new OpenKeyHold();
        hold.pressed();
        hold.cancel();
        assertFalse(hold.released(UP));
    }

    @Test
    void openedByOtherMeansLeavesNothingCarried() {
        // 不是靠这个键打开的（比如右键地面背包）：之后第一次按下照常算。
        var hold = new OpenKeyHold();
        hold.opened(UP);
        hold.pressed();
        assertTrue(hold.released(UP));
    }
}
