package dev.quantumchamber.candidate;

/** 持久協定的 domain tag 固定為顯式字串，不依賴 enum 名稱。 */
public enum DerivationDomain {
    CANDIDATE_ID("quantumchamber:candidate-id:v1"),
    BUCKET("quantumchamber:bucket:v1"),
    EXISTING_INDEX("quantumchamber:existing-index:v1"),
    ALLOCATION_TOKEN("quantumchamber:allocation-token:v1"),
    GENERATION_SEED_MATERIAL("quantumchamber:generation-seed-material:v1");

    private final String tag;

    DerivationDomain(String tag) { this.tag = tag; }

    String tag() { return tag; }
}
