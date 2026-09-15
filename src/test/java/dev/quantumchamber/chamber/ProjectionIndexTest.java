package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.quantumchamber.universe.DimensionRole;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class ProjectionIndexTest {
    @Test
    void indexesEveryChunkTouchedBySevenBlockWideBounds() {
        ProjectionIndex index = new ProjectionIndex();
        UUID chamber = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        // x=12..18 crosses chunks 0 and 1; z=0..6 stays in chunk 0.
        index.add(DimensionRole.OVERWORLD, chamber, new BlockBox(12, 64, 0, 18, 70, 6));

        assertEquals(Set.of(chamber), index.candidates(DimensionRole.OVERWORLD, new BlockPos(15, 65, 3)));
        assertEquals(Set.of(chamber), index.candidates(DimensionRole.OVERWORLD, new BlockPos(16, 65, 3)));
        assertEquals(Set.of(), index.candidates(DimensionRole.OVERWORLD, new BlockPos(32, 65, 3)));
        assertEquals(Set.of(), index.candidates(DimensionRole.NETHER, new BlockPos(16, 65, 3)));
    }
}
