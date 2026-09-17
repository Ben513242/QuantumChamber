package dev.quantumchamber.corridor;

public record LogicalAddress(long pageIndex, double localZ) {
    public LogicalAddress {
        if (!Double.isFinite(localZ) || localZ < 0 || localZ >= 96) {
            throw new IllegalArgumentException("頁內座標必須位於 [0,96)");
        }
        try { Math.multiplyExact(pageIndex, 96); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException("邏輯頁超出範圍", failure); }
    }
    public static LogicalAddress from(double logicalZ) {
        // double 精確整數範圍內才能維持 block 邊界與雙向映射。
        if (!Double.isFinite(logicalZ) || Math.abs(logicalZ) >= 0x1p53) {
            throw new IllegalArgumentException("邏輯座標必須有限且可精確表示 block");
        }
        long block = (long) Math.floor(logicalZ);
        long page = Math.floorDiv(block, 96);
        return new LogicalAddress(page, logicalZ - page * 96);
    }
}
