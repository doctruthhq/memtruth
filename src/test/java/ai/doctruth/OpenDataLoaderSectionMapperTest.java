package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenDataLoaderSectionMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsTextListTableAndNestedNodes() throws Exception {
        var geometry =
                new OpenDataLoaderPdfGeometry(Map.of(1, new OpenDataLoaderPdfGeometry.PageGeometry(200.0, 400.0)));
        var kids = MAPPER.readTree("""
                [
                  {
                    "type": "heading",
                    "page number": 1,
                    "bounding box": [20, 100, 100, 150],
                    "content": "Section Title"
                  },
                  {
                    "type": "list",
                    "page number": 1,
                    "bounding box": [20, 80, 120, 95],
                    "list items": [
                      { "type": "list item", "page number": 1, "content": "- First item" }
                    ]
                  },
                  {
                    "type": "table",
                    "page number": 1,
                    "rows": [
                      {
                        "cells": [
                          { "kids": [{ "type": "paragraph", "page number": 1, "content": "A1" }] },
                          { "content": "B1" }
                        ]
                      }
                    ]
                  },
                  {
                    "type": "unknown",
                    "kids": [
                      { "type": "paragraph", "page number": 1, "content": "Nested body" }
                    ]
                  }
                ]
                """);

        var sections = new OpenDataLoaderSectionMapper(geometry).map(kids);

        assertThat(sections).hasSize(4);
        assertThat(sections.get(0))
                .isEqualTo(new TextSection(
                        "Section Title",
                        new SourceLocation(1, 1, 1, 1, 0),
                        BlockKind.HEADING,
                        java.util.Optional.of(new BoundingBox(100.0, 625.0, 500.0, 750.0))));
        assertThat((TextSection) sections.get(1)).satisfies(section -> {
            assertThat(section.text()).isEqualTo("- First item");
            assertThat(section.kind()).isEqualTo(BlockKind.LIST);
            assertThat(section.location().lineStart()).isEqualTo(2);
            assertThat(section.boundingBox()).isPresent();
        });
        assertThat((TableSection) sections.get(2))
                .extracting(TableSection::rows)
                .isEqualTo(java.util.List.of(java.util.List.of("A1", "B1")));
        assertThat((TextSection) sections.get(3))
                .extracting(TextSection::text, TextSection::kind)
                .containsExactly("Nested body", BlockKind.BODY);
    }

    @Test
    void ignoresMissingArraysBlankTextAndInvalidBoundingBoxes() throws Exception {
        var geometry =
                new OpenDataLoaderPdfGeometry(Map.of(1, new OpenDataLoaderPdfGeometry.PageGeometry(200.0, 400.0)));
        var mapper = new OpenDataLoaderSectionMapper(geometry);

        assertThat(mapper.map(null)).isEmpty();
        assertThat(mapper.map(MAPPER.readTree("{}"))).isEmpty();

        var kids = MAPPER.readTree("""
                [
                  { "type": "paragraph", "page number": 1, "content": "   " },
                  { "type": "paragraph", "page number": 1, "bounding box": [100, 100, 90, 110], "content": "bad box" },
                  { "type": "paragraph", "page number": 2, "bounding box": [20, 100, 100, 150], "content": "no geometry" },
                  {
                    "type": "list",
                    "page number": 1,
                    "list items": [
                      { "type": "paragraph", "page number": 1, "content": "fallback item" }
                    ]
                  }
                ]
                """);

        var sections = mapper.map(kids);

        assertThat(sections)
                .filteredOn(TextSection.class::isInstance)
                .map(TextSection.class::cast)
                .extracting(TextSection::text)
                .containsExactly("bad box", "no geometry", "fallback item");
        assertThat(sections)
                .filteredOn(TextSection.class::isInstance)
                .map(TextSection.class::cast)
                .allSatisfy(section -> assertThat(section.boundingBox()).isEmpty());
    }

    @Test
    void flattensNestedTextWithoutBlankSegments() throws Exception {
        var geometry =
                new OpenDataLoaderPdfGeometry(Map.of(1, new OpenDataLoaderPdfGeometry.PageGeometry(200.0, 400.0)));
        var kids = MAPPER.readTree("""
                [
                  {
                    "type": "paragraph",
                    "page number": 1,
                    "kids": [
                      { "content": "Alpha" },
                      { "content": "   " },
                      {
                        "list items": [
                          { "content": "Beta" },
                          {
                            "kids": [
                              { "content": "Gamma" }
                            ]
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "type": "table",
                    "page number": 1,
                    "rows": [
                      {
                        "cells": [
                          {
                            "kids": [
                              { "content": "Cell A" },
                              { "content": "Cell B" }
                            ]
                          }
                        ]
                      }
                    ]
                  }
                ]
                """);

        var sections = new OpenDataLoaderSectionMapper(geometry).map(kids);

        assertThat((TextSection) sections.get(0)).extracting(TextSection::text).isEqualTo("Alpha\nBeta\nGamma");
        assertThat((TableSection) sections.get(1))
                .extracting(TableSection::rows)
                .isEqualTo(java.util.List.of(java.util.List.of("Cell A\nCell B")));
    }

    @Test
    void loadsGeometryOnlyWhenBoundingBoxesArePresent() throws Exception {
        var loads = new AtomicInteger();
        var geometry = OpenDataLoaderPdfGeometry.lazy(() -> {
            loads.incrementAndGet();
            return new OpenDataLoaderPdfGeometry(Map.of(1, new OpenDataLoaderPdfGeometry.PageGeometry(200.0, 400.0)));
        });

        new OpenDataLoaderSectionMapper(geometry).map(MAPPER.readTree("""
                [
                  { "type": "paragraph", "page number": 1, "content": "No box" }
                ]
                """));

        assertThat(loads).hasValue(0);

        new OpenDataLoaderSectionMapper(geometry).map(MAPPER.readTree("""
                [
                  {
                    "type": "paragraph",
                    "page number": 1,
                    "content": "Has box",
                    "bounding box": [20, 100, 100, 150]
                  }
                ]
                """));

        assertThat(loads).hasValue(1);
    }
}
