package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import java.util.Objects;
import java.util.UUID;

/** 選擇是明確的互斥狀態，不以空欄位猜測是否已測量。 */
public sealed interface CandidateSelection {
    record Selectable() implements CandidateSelection {}

    record Selected(DoorKey doorKey, CandidateId candidateId, UUID selectedBy,
            long selectedAtGameTime, int selectionRevision) implements CandidateSelection {
        public Selected {
            Objects.requireNonNull(doorKey, "doorKey");
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(selectedBy, "selectedBy");
            if (selectedAtGameTime < 0 || selectionRevision != 1) {
                throw new IllegalArgumentException("測量時間不得為負且 selection revision 必須為 1");
            }
        }
    }
}
