package dev.quantumchamber.mixin;

import dev.quantumchamber.universe.EndCityInvocationState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 只替換原版bridge的三個船隻旗標存取點，不改RNG或collision的接受順序。
 * 無frame時沿原字段；不承諾第三方混用整輪與直接Part呼叫仍看見原版final共享旗標。
 */
@Mixin(targets="net.minecraft.structure.EndCityGenerator$3")
abstract class EndCityBridgeStateMixin {
    @Shadow public boolean shipGenerated;

    @Redirect(method="init()V", at=@At(value="FIELD", target="Lnet/minecraft/structure/EndCityGenerator$3;shipGenerated:Z", opcode=Opcodes.PUTFIELD), require=1, allow=1)
    private void quantumchamber$resetShip(@Coerce Object receiver, boolean value) {
        if (!EndCityInvocationState.write(value)) shipGenerated = value;
    }

    @Redirect(method="create(Lnet/minecraft/structure/StructureTemplateManager;ILnet/minecraft/structure/EndCityGenerator$Piece;Lnet/minecraft/util/math/BlockPos;Ljava/util/List;Lnet/minecraft/util/math/random/Random;)Z",
            at=@At(value="FIELD", target="Lnet/minecraft/structure/EndCityGenerator$3;shipGenerated:Z", opcode=Opcodes.GETFIELD), require=1, allow=1)
    private boolean quantumchamber$readShip(@Coerce Object receiver) {
        return EndCityInvocationState.read(shipGenerated);
    }

    @Redirect(method="create(Lnet/minecraft/structure/StructureTemplateManager;ILnet/minecraft/structure/EndCityGenerator$Piece;Lnet/minecraft/util/math/BlockPos;Ljava/util/List;Lnet/minecraft/util/math/random/Random;)Z",
            at=@At(value="FIELD", target="Lnet/minecraft/structure/EndCityGenerator$3;shipGenerated:Z", opcode=Opcodes.PUTFIELD), require=1, allow=1)
    private void quantumchamber$writeShip(@Coerce Object receiver, boolean value) {
        if (!EndCityInvocationState.write(value)) shipGenerated = value;
    }
}
