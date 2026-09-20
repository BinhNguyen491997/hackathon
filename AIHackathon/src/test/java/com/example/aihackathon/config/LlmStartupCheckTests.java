package com.example.aihackathon.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmStartupCheckTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(LlmStartupCheck.class);

    @Test
    void chapNhanCauHinhMaaSDung() {
        String base = "https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1";
        assertThatCode(() -> LlmStartupCheck.validate(base, "key")).doesNotThrowAnyException();
        assertThat(LlmStartupCheck.validate(base, "key")).isEmpty();
    }

    @Test
    void baoLoiKhiBaseUrlMaaSThieuV1() {
        assertThatThrownBy(() -> LlmStartupCheck.validate("https://maas-llm-aiplatform-hcm.api.vngcloud.vn", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https://maas-llm-aiplatform-hcm.api.vngcloud.vn/chat/completions")
                .hasMessageContaining("LLM_BASE_URL=\"https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1\"");
    }

    @Test
    void baoLoiVoiCaDomainGreennodeAi() {
        assertThatThrownBy(() -> LlmStartupCheck.validate("https://maas.greennode.ai", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_BASE_URL=\"https://maas.greennode.ai/v1\"");
    }

    @Test
    void baoLoiKhiMaaSThieuApiKey() {
        assertThatThrownBy(
                () -> LlmStartupCheck.validate("https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1", " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-auth mode");
    }

    @Test
    void chiCanhBaoVoiProviderKhacKhiThieuV1HoacKey() {
        assertThat(LlmStartupCheck.validate("http://localhost:11434", "")).hasSize(2);
        assertThat(LlmStartupCheck.validate("http://localhost:11434/v1/", "key")).isEmpty();
    }

    @Test
    void canhBaoKhiThieuModel() {
        assertThat(LlmStartupCheck.warnings("")).singleElement()
                .asString().contains("LLM_MODEL");
        assertThat(LlmStartupCheck.warnings("z-ai/glm-5.2-hackathon")).isEmpty();
    }

    @Test
    void beanChanContextKhiBaseUrlMaaSThieuV1() {
        this.runner.withPropertyValues(
                        "spring.ai.openai.base-url=https://maas-llm-aiplatform-hcm.api.vngcloud.vn",
                        "spring.ai.openai.api-key=k",
                        "spring.ai.openai.chat.model=z-ai/glm-5.2-hackathon")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("/v1");
                });
    }

    @Test
    void beanKhoiDongBinhThuongVoiCauHinhMaaSDung() {
        this.runner.withPropertyValues(
                        "spring.ai.openai.base-url=https://maas-llm-aiplatform-hcm.api.vngcloud.vn/v1",
                        "spring.ai.openai.api-key=k",
                        "spring.ai.openai.chat.model=z-ai/glm-5.2-hackathon")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(LlmStartupCheck.class));
    }
}
