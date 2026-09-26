package dev.betterendfield.android;

/**
 * Windows virtual-key codes the ported desktop modules poll for.
 *
 * The desktop UI and camera modules decide what to do by reading
 * {@code GetAsyncKeyState}. Rather than fork those code paths for a device with
 * no keyboard, the Android build keeps them and lets the in-game panel press the
 * keys through {@link NativeCommandBridge#key}. These are therefore the single
 * source of truth for both sides: the panel buttons press them, and the module
 * configuration written by {@link ModuleSettings} names the same ones.
 */
final class Hotkeys {
    /** Desktop default for the all-HUD toggle in {@code BetterEndfield.UI}. */
    static final int HIDE_HUD = 0x30; // '0'
    /** Desktop defaults for {@code BetterEndfield.Camera}. */
    static final int FREE_CAMERA = 0x39; // '9'
    static final int WORLD_PAUSE = 0x38; // '8'
    static final int FIRST_PERSON = 0xBD; // VK_OEM_MINUS

    /** Free-camera movement, held rather than tapped. */
    static final int MOVE_FORWARD = 0x26; // VK_UP
    static final int MOVE_BACK = 0x28; // VK_DOWN
    static final int MOVE_LEFT = 0x25; // VK_LEFT
    static final int MOVE_RIGHT = 0x27; // VK_RIGHT
    static final int MOVE_UP = 0x21; // VK_PRIOR
    static final int MOVE_DOWN = 0x22; // VK_NEXT

    /**
     * Camera extensions from upstream 9b1e895 (roll, FOV, view reset, motion
     * presets, keyframes, VMD replay). The desktop module defaults them to the
     * numpad block, which no phone can press — the panel presses these same
     * codes instead, and {@code ModuleSettings} names them in the camera
     * configuration so both sides stay pinned even if defaults change.
     */
    static final int ROLL_LEFT = 0x67; // VK_NUMPAD7
    static final int ROLL_RIGHT = 0x69; // VK_NUMPAD9
    static final int FOV_WIDE = 0x61; // VK_NUMPAD1
    static final int FOV_NARROW = 0x63; // VK_NUMPAD3
    static final int VIEW_RESET = 0x65; // VK_NUMPAD5
    static final int MOTION = 0x68; // VK_NUMPAD8
    static final int KEYFRAME_ADD = 0x60; // VK_NUMPAD0
    static final int KEYFRAME_PLAY = 0x62; // VK_NUMPAD2
    static final int KEYFRAME_CLEAR = 0x64; // VK_NUMPAD4
    static final int VMD_PLAY = 0x66; // VK_NUMPAD6

    /**
     * How the configuration files spell each code. The module parsers accept a
     * single alphanumeric character as itself, {@code -} as VK_OEM_MINUS, and
     * {@code NUMPAD0}–{@code NUMPAD9} as the numpad block.
     */
    static final String HIDE_HUD_NAME = "0";
    static final String FREE_CAMERA_NAME = "9";
    static final String WORLD_PAUSE_NAME = "8";
    static final String FIRST_PERSON_NAME = "-";

    static final String ROLL_LEFT_NAME = "NUMPAD7";
    static final String ROLL_RIGHT_NAME = "NUMPAD9";
    static final String FOV_WIDE_NAME = "NUMPAD1";
    static final String FOV_NARROW_NAME = "NUMPAD3";
    static final String VIEW_RESET_NAME = "NUMPAD5";
    static final String MOTION_NAME = "NUMPAD8";
    static final String KEYFRAME_ADD_NAME = "NUMPAD0";
    static final String KEYFRAME_PLAY_NAME = "NUMPAD2";
    static final String KEYFRAME_CLEAR_NAME = "NUMPAD4";
    static final String VMD_PLAY_NAME = "NUMPAD6";

    private Hotkeys() {}
}
