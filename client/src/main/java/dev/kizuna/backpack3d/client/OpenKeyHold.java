package dev.kizuna.backpack3d.client;

/**
 * 背包界面里「打开背包键」的一次按下。这个键在界面里有两个用处：按着它点物品是快速存取，按一下松开是关界面。
 * 所以按下时先不关，等松开时看这次按下期间有没有拿它当过修饰键：没有就关，有就什么都不做。
 */
final class OpenKeyHold {
    private boolean carried;
    private boolean held;
    private boolean used;

    /** 界面刚被这个键打开：键还按着的话，这次按下发生在界面外，松开时不关界面。 */
    void opened(boolean keyDown) {
        carried = keyDown;
        held = false;
    }

    /** 界面里收到按下。带进来的那次按下、按住不放的重复按键都不算新的一次。 */
    void pressed() {
        if (carried || held) {
            return;
        }
        held = true;
        used = false;
    }

    /** 按着这个键做了一次快速存取。 */
    void usedAsModifier() {
        used = true;
    }

    /** 每 tick 问一次，keyDown 是这个键此刻是否实际按着。返回 true：界面里按下的这一次松开了，期间没当过修饰键，该关界面。 */
    boolean released(boolean keyDown) {
        if (keyDown) {
            return false;
        }
        carried = false;
        if (!held) {
            return false;
        }
        held = false;
        return !used;
    }

    /** 窗口失焦、界面被换掉：键的松开读不准了，这次按下作废，不关界面。 */
    void cancel() {
        carried = false;
        held = false;
    }
}
