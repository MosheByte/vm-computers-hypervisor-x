package net.hypervisorx.vm;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.VmInstance;

/**
 * Types into a running Windows guest through its virtual keyboard, like a player would (US layout).
 * Used to kick off the guest tools installer on a Windows that's already installed.
 */
final class GuestKeyboard {
    private static final int SHIFT = 340, CTRL = 341, ALT = 342, SUPER = 343, ENTER = 257, R = 82, Y = 89;

    private GuestKeyboard() {
    }

    /**
     * Win+R for Run, types {@code command}, runs it as admin (Ctrl+Shift+Enter) and says yes to UAC (Alt+Y).
     * Runs on its own thread and returns right away.
     */
    static void runElevated(VmInstance vm, String command) {
        Thread t = new Thread(() -> {
            try {
                chord(vm, SUPER, R);
                Thread.sleep(1500);
                for (char c : command.toCharArray()) type(vm, c);
                Thread.sleep(300);
                chord(vm, CTRL, SHIFT, ENTER);
                Thread.sleep(3000);
                chord(vm, ALT, Y);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                HyperVisorX.LOG.warn("Typing into the guest failed: {}", e.toString());
            }
        }, "hvx-guest-keyboard");
        t.setDaemon(true);
        t.start();
    }

    /** presses the keys in order, holds them a bit (VMware drops instant taps), releases in reverse. */
    private static void chord(VmInstance vm, int... keys) throws InterruptedException {
        for (int k : keys) {
            vm.key(k, true);
            Thread.sleep(40);
        }
        Thread.sleep(60);
        for (int i = keys.length - 1; i >= 0; i--) {
            vm.key(keys[i], false);
            Thread.sleep(30);
        }
    }

    private static void type(VmInstance vm, char c) throws InterruptedException {
        int key = c;
        boolean shift = false;
        if (c >= 'a' && c <= 'z') {
            key = 'A' + (c - 'a');
        } else if (c >= 'A' && c <= 'Z') {
            shift = true;
        } else if ("\"%&()*:_".indexOf(c) >= 0) {
            shift = true;
            key = switch (c) {
                case '"' -> '\'';
                case '%' -> '5';
                case '&' -> '7';
                case '(' -> '9';
                case ')' -> '0';
                case '*' -> '8';
                case ':' -> ';';
                default -> '-';
            };
        } else if (" '-./0123456789;=\\".indexOf(c) < 0) {
            return;
        }
        if (shift) chord(vm, SHIFT, key);
        else chord(vm, key);
    }
}
