package ai.doctruth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract tests for local model runtime policy. */
class ModelRuntimePolicyTest {

    @Test
    @DisplayName("lite offline policy requires no model downloads")
    void liteOfflineRequiresNoModelDownloads() {
        var policy = ModelRuntimePolicy.liteOffline();

        assertThat(policy.offlineMode()).isTrue();
        assertThat(policy.allowModelDownloads()).isFalse();
        assertThat(policy.requiredModels()).isEmpty();
        assertThat(policy.networkAccessRequired()).isFalse();
        assertThat(policy.warnings()).isEmpty();
    }

    @Test
    @DisplayName("offline mode with required models emits one blocking warning per model")
    void offlineRequiredModelsEmitBlockingWarning() {
        var model = new ModelDescriptor("tatr", "v1", "sha256:abc", 30_000_000, true);
        var policy = new ModelRuntimePolicy(true, false, List.of(model));

        assertThat(policy.networkAccessRequired()).isFalse();
        assertThat(policy.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("model_unavailable_fallback");
            assertThat(warning.severity()).isEqualTo(ParserWarningSeverity.SEVERE);
            assertThat(warning.message()).contains("tatr:v1").contains("sha256:abc");
        });
    }

    @Test
    @DisplayName("offline model warnings preserve every missing required model identity")
    void offlineRequiredModelsEmitOneWarningPerModelIdentity() {
        var layout = new ModelDescriptor("layout-rtdetr", "v2", "sha256:layout", 169_000_000, true);
        var table = new ModelDescriptor("tatr", "v1", "sha256:table", 30_000_000, true);
        var policy = ModelRuntimePolicy.offlineRequired(List.of(layout, table));

        assertThat(policy.warnings()).hasSize(2);
        assertThat(policy.warnings())
                .extracting(ParserWarning::message)
                .anySatisfy(message -> assertThat(message).contains("layout-rtdetr:v2").contains("sha256:layout"))
                .anySatisfy(message -> assertThat(message).contains("tatr:v1").contains("sha256:table"));
        assertThat(policy.warnings())
                .extracting(ParserWarning::severity)
                .containsOnly(ParserWarningSeverity.SEVERE);
    }

    @Test
    @DisplayName("online model policy reports network access when downloads are allowed")
    void onlineModelPolicyRequiresNetwork() {
        var model = new ModelDescriptor("layout-rtdetr", "v2", "sha256:def", 169_000_000, true);
        var policy = new ModelRuntimePolicy(false, true, List.of(model));

        assertThat(policy.networkAccessRequired()).isTrue();
        assertThat(policy.warnings()).isEmpty();
    }

    @Test
    @DisplayName("model descriptor rejects blank identity and invalid size")
    void modelDescriptorInvariants() {
        assertThatThrownBy(() -> new ModelDescriptor(" ", "v1", "sha256:abc", 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new ModelDescriptor("tatr", " ", "sha256:abc", 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new ModelDescriptor("tatr", "v1", " ", 1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sha256");
        assertThatThrownBy(() -> new ModelDescriptor("tatr", "v1", "sha256:abc", -1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sizeBytes");
    }
}
