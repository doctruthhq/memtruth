use serde_json::{Value, json};

struct TemporaryRepair {
    repair: &'static str,
    processor: &'static str,
    bucket: &'static str,
    focused_test: &'static str,
    replacement_plan: &'static str,
}

const TEMPORARY_REPAIRS: &[TemporaryRepair] = &[
    TemporaryRepair {
        repair: "remittance_growth_table_reconstruction",
        processor: "TableStructureNormalizer",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized multi-column table reconstruction before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "kinematic_viscosity_table_reconstruction",
        processor: "TableStructureNormalizer",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized numeric table reconstruction before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "chart_axis_fragment_demotion",
        processor: "SpecialTableProcessor",
        bucket: "table_false_positive_rejection",
        focused_test: "opendataloader_table_processor_contract",
        replacement_plan: "replace with generalized chart-axis false-table rejection before marking SpecialTableProcessor matched",
    },
    TemporaryRepair {
        repair: "blank_comparison_table_merge",
        processor: "TableStructureNormalizer",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized blank-row label merge before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "national_initiatives_table_normalization",
        processor: "TableStructureNormalizer",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized long-text table normalization before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "eco_competence_framework_normalization",
        processor: "TableStructureNormalizer",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized framework-table normalization before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "area_competence_table_promotion",
        processor: "ClusterTableProcessor",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized rowspan-style borderless table promotion before marking ClusterTableProcessor matched",
    },
    TemporaryRepair {
        repair: "training_dataset_fragment_merge",
        processor: "ClusterTableProcessor",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized adjacent table-fragment merging before marking ClusterTableProcessor matched",
    },
    TemporaryRepair {
        repair: "port_shipcall_column_stream_merge",
        processor: "ClusterTableProcessor",
        bucket: "borderless_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized header-plus-column-stream merge before marking ClusterTableProcessor matched",
    },
    TemporaryRepair {
        repair: "inline_cation_observation_split",
        processor: "TableStructureNormalizer",
        bucket: "bordered_tables",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized inline caption/header/row-token splitting before marking TableStructureNormalizer matched",
    },
    TemporaryRepair {
        repair: "regulatory_narrative_shard_demotion",
        processor: "SpecialTableProcessor",
        bucket: "table_false_positive_rejection",
        focused_test: "PdfBorderlessTableExtractionTest",
        replacement_plan: "replace with generalized narrative-shard false-table rejection before marking SpecialTableProcessor matched",
    },
];

pub(crate) fn temporary_repairs() -> Vec<Value> {
    TEMPORARY_REPAIRS
        .iter()
        .map(|repair| {
            json!({
                "repair": repair.repair,
                "processor": repair.processor,
                "bucket": repair.bucket,
                "parity_claim": false,
                "focused_test": repair.focused_test,
                "replacement_plan": repair.replacement_plan
            })
        })
        .collect()
}
