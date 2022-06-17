package org.apache.eventmesh.runtime.boot;

import io.netty.handler.codec.http.HttpRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.eventmesh.runtime.trace.SpanKey;
import org.apache.eventmesh.trace.api.TracePluginFactory;
import org.apache.eventmesh.trace.api.TraceService;

import java.net.InetSocketAddress;

public class HTTPTrace {
    private TextMapPropagator textMapPropagator;
    private Tracer tracer;
    private boolean useTrace = false;

    //重载初始化方法
    public void initTrace(Tracer tracer, TextMapPropagator textMapPropagator, boolean useTrace) {
        this.tracer = tracer;
        this.textMapPropagator = textMapPropagator;
        this.useTrace = useTrace;
    }

    public void initTrace(String traceType, boolean traceEnable, Class<?> clazz) {
        if (StringUtils.isNotEmpty(traceType) && traceEnable) {
            TraceService traceService = TracePluginFactory.getTraceService(traceType);
            traceService.init();
            tracer = traceService.getTracer(clazz.toString());
            textMapPropagator = traceService.getTextMapPropagator();
            useTrace = true;
        }
    }

    public TraceOperation getTraceOperation(HttpRequest httpRequest, InetSocketAddress inetSocket) {
        Context context = textMapPropagator.extract(Context.current(), httpRequest, new TextMapGetter<HttpRequest>() {
            @Override
            public Iterable<String> keys(HttpRequest carrier) {
                return carrier.headers().names();
            }

            @Override
            public String get(HttpRequest carrier, String key) {
                return carrier.headers().get(key);
            }
        });
        String ip = inetSocket.getAddress().getHostAddress();
        String port = String.valueOf(inetSocket.getPort());
        //使用IP和PORT
        Span span = tracer.spanBuilder(ip + ":" + port + "/" + httpRequest.method())
                .setParent(context)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();
        context = context.with(SpanKey.SERVER_KEY, span);

        return new TraceOperation(context, span, useTrace);
    }

    @AllArgsConstructor
    static class TraceOperation {

        private final Context context;
        private final Span span;
        private final boolean useTrace;

        public void errorEnd() {
            if (useTrace) {
                try (Scope ignored = context.makeCurrent()) {
                    span.setStatus(StatusCode.ERROR);
                    span.end();
                }
            }
        }

        public void end() {
            if (useTrace) {
                try (Scope ignored = context.makeCurrent()) {
                    span.end();
                }
            }
        }

        public void httpReqTrace(HttpRequest httpRequest) {
            if (useTrace) {
                span.setAttribute(SemanticAttributes.HTTP_METHOD, httpRequest.method().name());
                span.setAttribute(SemanticAttributes.HTTP_FLAVOR, httpRequest.protocolVersion().protocolName());
                span.setAttribute(String.valueOf(SemanticAttributes.HTTP_URL), httpRequest.getUri());
            }
        }

        public void exceptionTrace(Exception ex) {
            if (useTrace) {
                span.setAttribute(SemanticAttributes.EXCEPTION_MESSAGE, ex.getMessage());
                span.setStatus(StatusCode.ERROR, ex.getMessage());
                span.recordException(ex);
                span.end();
            }
        }
    }
}
