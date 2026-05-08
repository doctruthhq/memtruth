package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("OptiTalent read-only resume smoke")
class OptiTalentResumeSmokeIT {

    private static final Logger LOG = LoggerFactory.getLogger(OptiTalentResumeSmokeIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PDFS = 20;
    private static final int TARGET_OK = 3;
    private static final Path DEFAULT_RESUMES =
            Path.of("/Users/jameslee/OptiTalent Nan V1/optitalent-monorepo/frontend/storage/resumes/resumes");
    private static final String MARKER_SCHEMA = """
            {
              "type": "object",
              "properties": { "marker": { "type": "string", "minLength": 3 } },
              "required": ["marker"],
              "additionalProperties": false
            }
            """;

    @Test
    @DisplayName("bounded real OptiTalent PDFs parse and pass JSON extraction with citation matching")
    void realOptiTalentPdfsRunThroughDocTruthPipeline() throws Exception {
        Path resumesDir =
                Path.of(System.getenv().getOrDefault("DOCTRUTH_OPTITALENT_RESUMES_DIR", DEFAULT_RESUMES.toString()));
        assumeTrue(Files.isDirectory(resumesDir), "OptiTalent resumes directory not present; skipping smoke");
        List<Path> pdfs = listPdfs(resumesDir);
        assumeTrue(!pdfs.isEmpty(), "OptiTalent resumes directory has no PDFs; skipping smoke");

        Map<String, Integer> categories = new LinkedHashMap<>();
        int ok = 0;
        for (Path pdf : pdfs) {
            try {
                ParsedDocument doc = PdfDocumentParser.parse(pdf);
                Optional<String> marker = markerText(doc);
                if (marker.isEmpty()) {
                    increment(categories, "scanned_or_empty");
                    continue;
                }
                runMarkerExtraction(doc, marker.get());
                increment(categories, "ok");
                ok++;
                if (ok >= TARGET_OK) {
                    break;
                }
            } catch (ParseException e) {
                increment(categories, e.errorCode());
            } catch (ExtractionException e) {
                increment(categories, e.errorCode());
            }
        }

        LOG.info("OptiTalent resume smoke categories: {}", categories);
        assertThat(ok)
                .as("at least one real OptiTalent PDF should parse and match citation")
                .isPositive();
        assertThat(categories).doesNotContainKey("EXTRACTION_EVIDENCE_MISSING");
    }

    private static List<Path> listPdfs(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_PDFS)
                    .toList();
        }
    }

    private static Optional<String> markerText(ParsedDocument doc) {
        return doc.sections().stream()
                .map(OptiTalentResumeSmokeIT::sectionText)
                .map(String::strip)
                .filter(text -> text.length() >= 8)
                .map(text -> text.length() > 80 ? text.substring(0, 80) : text)
                .findFirst();
    }

    private static String sectionText(ParsedSection section) {
        if (section instanceof TextSection text) {
            return text.text();
        }
        if (section instanceof TableSection table) {
            return table.rows().stream().flatMap(List::stream).findFirst().orElse("");
        }
        return "";
    }

    private static void runMarkerExtraction(ParsedDocument doc, String marker) throws Exception {
        String markerJson = MAPPER.writeValueAsString(marker);
        LlmProvider provider = new AnthropicProvider("test-key") {
            @Override
            public ProviderResponse complete(ProviderRequest request) throws ProviderException {
                return new ProviderResponse(
                        "{\"marker\":" + markerJson + "}", new ProviderUsage(0, 0, "opititalent-smoke-fake"));
            }
        };
        DocTruth.from(provider)
                .extractJson("Extract marker", JsonSchema.from(MARKER_SCHEMA))
                .requireCitation("marker")
                .withMaxRetries(0)
                .runJson(doc);
    }

    private static void increment(Map<String, Integer> categories, String key) {
        categories.merge(key, 1, Integer::sum);
    }
}
