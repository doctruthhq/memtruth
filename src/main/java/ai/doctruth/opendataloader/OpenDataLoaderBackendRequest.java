package ai.doctruth.opendataloader;

import java.nio.file.Path;
import java.util.Objects;

import ai.doctruth.ParserPreset;

/** Request for the local OpenDataLoader-compatible Java parser backend. */
public record OpenDataLoaderBackendRequest(Path document, ParserPreset preset) {

    public OpenDataLoaderBackendRequest {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(preset, "preset");
    }
}
