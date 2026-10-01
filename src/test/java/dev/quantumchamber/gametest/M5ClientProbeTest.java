package dev.quantumchamber.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/** F1a：/qcprobe 只在 -Dquantumchamber.clientProbe=true 時註冊，而且只給 op 2。 */
class M5ClientProbeTest {
    /**
     * Fabric 的指令事件是全域的，註冊之後不能撤銷；所以同一個 test 依序驗證「沒有 property」、「非精確值」，最後才驗證「true」。
     */
    @Test
    void registersQcprobeOnlyWithExactTrueProperty() {
        String previous = System.getProperty(M5ClientProbe.PROPERTY);
        try {
            System.clearProperty(M5ClientProbe.PROPERTY);
            new M5ClientProbe().onInitialize();
            assertNull(dispatch().getRoot().getChild("qcprobe"), "沒有 property 時不得註冊 /qcprobe");

            System.setProperty(M5ClientProbe.PROPERTY, "TRUE");
            new M5ClientProbe().onInitialize();
            assertNull(dispatch().getRoot().getChild("qcprobe"), "只接受精確的 true");

            System.setProperty(M5ClientProbe.PROPERTY, "true");
            new M5ClientProbe().onInitialize();
            CommandNode<ServerCommandSource> qcprobe = dispatch().getRoot().getChild("qcprobe");
            assertNotNull(qcprobe, "property 為 true 時必須註冊 /qcprobe");
            assertFalse(qcprobe.getRequirement().test(source(1)), "op 1 不得使用 /qcprobe");
            assertTrue(qcprobe.getRequirement().test(source(2)), "op 2 可以使用 /qcprobe");
            CommandNode<ServerCommandSource> universe = qcprobe.getChild("universe");
            assertNotNull(universe);
            assertEquals(Set.of("universe"), names(qcprobe));
            assertEquals(Set.of("allocate", "enter", "return", "status"), names(universe));
            for (var leaf : universe.getChildren()) {
                assertNotNull(leaf.getCommand(), "子指令必須可執行：" + leaf.getName());
                assertTrue(leaf.getChildren().isEmpty(), "子指令不接受參數：" + leaf.getName());
            }
        } finally {
            if (previous == null) System.clearProperty(M5ClientProbe.PROPERTY);
            else System.setProperty(M5ClientProbe.PROPERTY, previous);
        }
    }

    private static CommandDispatcher<ServerCommandSource> dispatch() {
        var dispatcher = new CommandDispatcher<ServerCommandSource>();
        CommandRegistrationCallback.EVENT.invoker().register(dispatcher, null, CommandManager.RegistrationEnvironment.DEDICATED);
        return dispatcher;
    }

    private static Set<String> names(CommandNode<ServerCommandSource> node) {
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    private static ServerCommandSource source(int level) {
        return new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO, null, level,
                "f1a-test", Text.literal("f1a-test"), null, null);
    }
}
