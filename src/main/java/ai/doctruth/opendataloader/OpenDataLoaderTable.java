package ai.doctruth.opendataloader;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ai.doctruth.BoundingBox;

/** OpenDataLoader-shaped table projection. */
public final class OpenDataLoaderTable {

    private final String id;
    private final int pageIndex;
    private final Optional<BoundingBox> bbox;
    private final List<OpenDataLoaderTableCell> cells;

    public OpenDataLoaderTable(
            String id, int pageIndex, Optional<BoundingBox> bbox, List<OpenDataLoaderTableCell> cells) {
        this.id = requireText(id, "id");
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0");
        }
        this.pageIndex = pageIndex;
        this.bbox = Objects.requireNonNull(bbox, "bbox");
        this.cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
    }

    public String id() {
        return id;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public Optional<BoundingBox> bbox() {
        return bbox;
    }

    public List<OpenDataLoaderTableCell> cells() {
        return cells;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
