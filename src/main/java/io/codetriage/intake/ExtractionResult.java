package io.codetriage.intake;

import java.util.ArrayList;
import java.util.List;

public class ExtractionResult {
    private int sourceFilesExtracted;
    private long bytesWritten;
    private final List<String> skipped = new ArrayList<>();

    public int getSourceFilesExtracted() { return sourceFilesExtracted; }
    public long getBytesWritten() { return bytesWritten; }
    public List<String> getSkipped() { return skipped; }

    void countFile(long bytes) {
        sourceFilesExtracted++;
        bytesWritten += bytes;
    }

    void skip(String entryName, String reason) {
        if (skipped.size() < 50) {
            skipped.add(entryName + " (" + reason + ")");
        }
    }
}
