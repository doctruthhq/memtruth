// SPDX-License-Identifier: Apache-2.0
package ai.doctruth.examples.pydanticinterop;

import ai.doctruth.DocTruth;
import ai.doctruth.JsonSchema;
import java.nio.file.Path;

public final class PydanticInteropExample {

    private PydanticInteropExample() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: PydanticInteropExample <resume.pdf> <resume.schema.json> <audit.json>");
            System.exit(2);
        }
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("set OPENAI_API_KEY before running this example");
            System.exit(2);
        }

        Path pdfPath = Path.of(args[0]);
        Path schemaPath = Path.of(args[1]);
        Path auditPath = Path.of(args[2]);

        JsonSchema schema = JsonSchema.from(schemaPath);

        var result = DocTruth.withOpenAi(apiKey)
                .fromPdf(pdfPath)
                .extractJson("Extract resume fields. Return JSON only.", schema)
                .requireCitation("fullName")
                .requireCitation("experience[0].company")
                .withEvidence()
                .withMaxRetries(2)
                .runJson();

        result.toAuditJson(auditPath);
        System.out.println("extracted JSON: " + result.value());
        System.out.println("citations: " + result.citations().keySet());
        System.out.println("audit JSON written to: " + auditPath);
    }
}
