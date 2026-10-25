package dev.quantumchamber.universe;

import java.util.ArrayDeque;
import java.util.Deque;

/** End city整輪的短期狀態；不保留world、template manager、RNG或piece。 */
public final class EndCityInvocationState {
    private static final ThreadLocal<Deque<Frame>> FRAMES = new ThreadLocal<>();
    private EndCityInvocationState() { }

    /** root入口在原版Part.init之前建立新的false旗標。 */
    public static void enter() {
        var frames = FRAMES.get();
        if (frames == null) {
            frames = new ArrayDeque<>();
            frames.push(new Frame());
            FRAMES.set(frames);
        } else frames.push(new Frame());
    }

    /** finally返回父frame；最外層返回時不留下空stack。 */
    public static void exit() {
        var frames = FRAMES.get();
        if (frames == null || frames.isEmpty()) {
            FRAMES.remove();
            throw new IllegalStateException("End city invocation frame不存在");
        }
        frames.pop();
        if (frames.isEmpty()) FRAMES.remove();
    }

    /** 無frame只沿原字段；清除JDK get可能建立的瞬間null entry。 */
    public static boolean read(boolean nativeValue) {
        var frames = FRAMES.get();
        if (frames == null) { FRAMES.remove(); return nativeValue; }
        return frames.peek().shipGenerated;
    }

    /** 回傳false表示呼叫端仍須寫入原字段。 */
    public static boolean write(boolean value) {
        var frames = FRAMES.get();
        if (frames == null) { FRAMES.remove(); return false; }
        frames.peek().shipGenerated = value;
        return true;
    }

    private static final class Frame { private boolean shipGenerated; }
}
