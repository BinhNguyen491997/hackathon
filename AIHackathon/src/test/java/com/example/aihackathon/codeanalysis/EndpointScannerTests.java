package com.example.aihackathon.codeanalysis;

import java.util.List;

import com.example.aihackathon.codeanalysis.model.ApiEndpoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointScannerTests {

    private static List<ApiEndpoint> endpoints;

    @BeforeAll
    static void scanOnce() {
        endpoints = FixtureRepo.endpoints();
    }

    @Test
    void ghepPrefixClassVoiPathMethod() {
        assertThat(endpoints).extracting(ApiEndpoint::httpMethod, ApiEndpoint::path)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("POST", "/api/orders"),
                        org.assertj.core.groups.Tuple.tuple("GET", "/api/orders/{id}"));
    }

    @Test
    void docDuocSummaryTuOperation() {
        ApiEndpoint create = endpointFor("POST", "/api/orders");
        assertThat(create.summary()).isEqualTo("Tạo đơn hàng mới cho khách");
        assertThat(create.controllerSimpleName()).isEqualTo("OrderController");
        assertThat(create.methodName()).isEqualTo("create");
        assertThat(create.sourceFile()).endsWith("OrderController.java");
        assertThat(create.line()).isPositive();
    }

    @Test
    void khopPathCoBienDuDatTenBienKhac() {
        ApiEndpoint findOne = endpointFor("GET", "/api/orders/{id}");
        assertThat(findOne.matches("GET", "/api/orders/{orderId}")).isTrue();
    }

    @Test
    void khopPathDaDienGiaTriThatNhuCopyTuPostman() {
        ApiEndpoint findOne = endpointFor("GET", "/api/orders/{id}");
        assertThat(findOne.matches("GET", "/api/orders/12345")).isTrue();
        assertThat(findOne.matches("GET", "/api/orders/12345?expand=lines")).isTrue();
    }

    @Test
    void khongKhopKhiSaiHttpMethodHoacSaiSoSegment() {
        ApiEndpoint findOne = endpointFor("GET", "/api/orders/{id}");
        assertThat(findOne.matches("POST", "/api/orders/1")).isFalse();
        assertThat(findOne.matches("GET", "/api/orders")).isFalse();
        assertThat(findOne.matches("GET", "/api/orders/1/lines")).isFalse();
    }

    @Test
    void joinPathsChuanHoaDauGachCheo() {
        assertThat(EndpointScanner.joinPaths("/api/orders/", "/{id}")).isEqualTo("/api/orders/{id}");
        assertThat(EndpointScanner.joinPaths("api/orders", "")).isEqualTo("/api/orders");
        assertThat(EndpointScanner.joinPaths("", "/health")).isEqualTo("/health");
    }

    private static ApiEndpoint endpointFor(String method, String path) {
        return endpoints.stream()
                .filter(endpoint -> endpoint.httpMethod().equals(method) && endpoint.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("không tìm thấy " + method + " " + path));
    }
}
