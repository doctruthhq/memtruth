package ai.doctruth;

import java.util.List;

/**
 * Local parser quality presets for {@link TrustDocument} generation.
 *
 * @since 1.0.0
 */
public enum ParserPreset {
    LITE("lite", ModelRuntimePolicy.liteOffline()),
    STANDARD(
            "standard",
            ModelRuntimePolicy.offlineRequired(List.of(
                    new ModelDescriptor("layout-rtdetr", "v2", "sha256:pending-layout-rtdetr-v2", 169_000_000, true),
                    new ModelDescriptor("tatr", "v1", "sha256:pending-tatr-v1", 30_000_000, true)))),
    TABLE_LITE(
            "table-lite",
            ModelRuntimePolicy.offlineRequired(List.of(
                    new ModelDescriptor("slanet-plus", "v1", "sha256:pending-slanet-plus-v1", 7_780_000, true)))),
    TABLE_SERVER(
            "table-server",
            ModelRuntimePolicy.offlineRequired(List.of(
                    new ModelDescriptor("slanext-auto", "v1", "sha256:pending-slanext-auto-v1", 737_000_000, true)))),
    OCR(
            "ocr",
            ModelRuntimePolicy.offlineRequired(List.of(
                    new ModelDescriptor("ocr-router", "v1", "sha256:pending-ocr-router-v1", 0, true))));

    private final String id;
    private final ModelRuntimePolicy runtimePolicy;

    ParserPreset(String id, ModelRuntimePolicy runtimePolicy) {
        this.id = id;
        this.runtimePolicy = runtimePolicy;
    }

    public String id() {
        return id;
    }

    public ModelRuntimePolicy runtimePolicy() {
        return runtimePolicy;
    }

    public static ParserPreset fromId(String value) {
        return switch (value) {
            case "lite" -> LITE;
            case "standard" -> STANDARD;
            case "table-lite" -> TABLE_LITE;
            case "table-server" -> TABLE_SERVER;
            case "ocr" -> OCR;
            default -> throw new IllegalArgumentException("unknown parser preset: " + value);
        };
    }

    ParserRun parserRun() {
        return parserRun("pdfbox");
    }

    public ParserRun parserRun(String backend) {
        var models = runtimePolicy.requiredModels().stream().map(ModelDescriptor::identity).toList();
        return new ParserRun("1.0.0", id, backend, models, runtimePolicy.warnings());
    }
}
