package com.opspilot.gateway;

import com.opspilot.config.OpsPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Optional;

/** 通过 HTTP JSON 接口访问业务系统。每个请求都有连接超时和读超时，不会无限等待。 */
@Component
public class HttpBusinessSystemGateway implements BusinessSystemGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpBusinessSystemGateway.class);

    private final RestClient procurement;
    private final RestClient invoices;
    private final RestClient finance;
    private final ChaosSwitch chaos;

    public HttpBusinessSystemGateway(RestClient.Builder builder, OpsPilotProperties properties, ChaosSwitch chaos) {
        OpsPilotProperties.Systems systems = properties.systems();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(systems.connectTimeoutMs()))
                // 模拟系统是 uvicorn，只说 HTTP/1.1；不强制的话 JDK 会先尝试 h2c 升级
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(systems.readTimeoutMs()));
        RestClient.Builder base = builder.requestFactory(factory);
        this.procurement = base.clone().baseUrl(systems.procurementUrl()).build();
        this.invoices = base.clone().baseUrl(systems.invoiceUrl()).build();
        this.finance = base.clone().baseUrl(systems.financeUrl()).build();
        this.chaos = chaos;
    }

    @Override
    public Optional<PurchaseOrder> findOrder(String poNumber) {
        return get(procurement, "采购系统", PurchaseOrder.class, "/api/orders/{po}", poNumber);
    }

    @Override
    public Optional<Invoice> findInvoiceByPo(String poNumber) {
        return get(invoices, "发票系统", Invoice.class, "/api/invoices/by-po/{po}", poNumber);
    }

    @Override
    public Optional<Payment> findPayment(String poNumber, String invoiceId) {
        return get(finance, "财务系统", Payment.class, "/api/payments/{po}/{inv}", poNumber, invoiceId);
    }

    @Override
    public SubmitResult submitPayment(PaymentRequest request) {
        try {
            return finance.post()
                    .uri("/api/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, res) -> {
                        int code = res.getStatusCode().value();
                        if (chaos.consumeDrop()) {
                            // 请求已经被财务系统处理了，只是我们"没收到"响应
                            log.warn("[chaos] 丢弃付款响应 po={} http={}", request.poNumber(), code);
                            throw new SubmitException(SubmitException.Kind.OUTCOME_UNKNOWN, "响应丢失（故障注入）");
                        }
                        if (code == 200 || code == 201) {
                            return new SubmitResult(res.bodyTo(Payment.class), code == 201);
                        }
                        if (code >= 400 && code < 500) {
                            throw new SubmitException(SubmitException.Kind.REJECTED, "财务系统拒绝: HTTP " + code);
                        }
                        throw new SubmitException(SubmitException.Kind.OUTCOME_UNKNOWN, "财务系统返回 HTTP " + code);
                    });
        } catch (ResourceAccessException e) {
            if (connectionNeverEstablished(e)) {
                throw new SubmitException(SubmitException.Kind.NOT_SENT, "连接财务系统失败: " + e.getMessage(), e);
            }
            // 连接建立之后才出错（典型是读超时）：对方可能已经处理了
            throw new SubmitException(SubmitException.Kind.OUTCOME_UNKNOWN, "等待财务系统响应失败: " + e.getMessage(), e);
        }
    }

    private static <T> Optional<T> get(RestClient client, String system, Class<T> type, String uri, Object... vars) {
        try {
            return client.get().uri(uri, vars).exchange((req, res) -> {
                int code = res.getStatusCode().value();
                if (code == 404) {
                    return Optional.<T>empty();
                }
                if (code != 200) {
                    throw new UpstreamUnavailableException(system + "返回 HTTP " + code, null);
                }
                return Optional.ofNullable(res.bodyTo(type));
            });
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(system + "不可用: " + e.getMessage(), e);
        }
    }

    private static boolean connectionNeverEstablished(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }
}
