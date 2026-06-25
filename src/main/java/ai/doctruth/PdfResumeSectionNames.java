package ai.doctruth;

import java.util.Locale;

final class PdfResumeSectionNames {

    private PdfResumeSectionNames() {
        throw new AssertionError("no instances");
    }

    static boolean isKnown(String text) {
        var normalized = normalize(text);
        return isKnownNormalized(normalized) || isKnownCompact(normalized);
    }

    private static boolean isKnownNormalized(String normalized) {
        return switch (normalized) {
            case "additional information",
                    "career objective",
                    "certification",
                    "certifications",
                    "contact",
                    "education",
                    "experience",
                    "executive summary",
                    "interests",
                    "language",
                    "languages",
                    "objective",
                    "professional experience",
                    "profile",
                    "project experience",
                    "quality",
                    "references",
                    "skills",
                    "skill and education",
                    "summary",
                    "technical skills",
                    "work experience",
                    "work history",
                    "bahasa",
                    "butiran diri",
                    "kemahiran",
                    "kemahiran bahasa",
                    "kemahiran komputer",
                    "kekuatan diri",
                    "lain-lain",
                    "latar belakang pendidikan",
                    "mengenai saya",
                    "objektif",
                    "pendidikan",
                    "pengalaman kerja",
                    "pengalaman pekerjaan",
                    "rujukan" -> true;
            default -> false;
        };
    }

    private static boolean isKnownCompact(String normalized) {
        return switch (normalized.replace(" ", "")) {
            case "additionalinformation",
                    "careerobjective",
                    "certification",
                    "certifications",
                    "contact",
                    "education",
                    "experience",
                    "executivesummary",
                    "interests",
                    "language",
                    "languages",
                    "objective",
                    "professionalexperience",
                    "profile",
                    "projectexperience",
                    "quality",
                    "references",
                    "skills",
                    "skillandeducation",
                    "summary",
                    "technicalskills",
                    "workexperience",
                    "workhistory",
                    "bahasa",
                    "butirandiri",
                    "kemahiran",
                    "kemahiranbahasa",
                    "kemahirankomputer",
                    "kekuatandiri",
                    "lain-lain",
                    "latarbelakangpendidikan",
                    "mengenaisaya",
                    "objektif",
                    "pendidikan",
                    "pengalamankerja",
                    "pengalamanpekerjaan",
                    "rujukan" -> true;
            default -> false;
        };
    }

    static boolean isRowValueSection(String text) {
        return switch (normalize(text)) {
            case "bahasa",
                    "kemahiran",
                    "kemahiran bahasa",
                    "kemahiran komputer",
                    "language",
                    "languages",
                    "skills",
                    "technical skills" -> true;
            default -> false;
        };
    }

    static boolean isCompactRowValue(String text) {
        var lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || lines.size() > 3) {
            return false;
        }
        for (var line : lines) {
            if (line.length() > 28 || line.split("\\s+").length > 4) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("&", "and").strip();
    }
}
