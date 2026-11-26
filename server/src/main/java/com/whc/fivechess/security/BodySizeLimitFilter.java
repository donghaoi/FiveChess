package com.whc.fivechess.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求体大小上限。
 *
 * 容器自带的 maxPostSize 只约束表单解析，对 JSON 请求体不生效；而接口把请求体
 * 整体读进 String 再交给 JSON 解析，若不加限制，一个几十 MB 的请求就能让单个连接
 * 占住大量堆内存，几个并发即可拖垮进程（/sessionCheck 还不在限流名单里）。
 *
 * 两道防线：
 * <ol>
 *   <li>有 Content-Length 时直接按声明值拒绝，不读流；</li>
 *   <li>没有 Content-Length（Transfer-Encoding: chunked）时，把输入流包成限长流，
 *       读取超过上限立即中断并返回 413。只判断 Content-Length 会被 chunked 直接绕过。</li>
 * </ol>
 * 上限取 8MB：头像接口允许 3MB 图片，经 base64 膨胀约 4.1MB，仍有余量。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodySizeLimitFilter implements Filter {

    /** 允许的最大请求体字节数 */
    private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

    @Override
    public void init(FilterConfig filterConfig) {
        //无需初始化
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        long length = httpRequest.getContentLengthLong();
        if (length > MAX_BODY_BYTES) {
            reject(httpResponse);
            return;
        }
        //没有 Content-Length 说明是 chunked 或长度未知：只能边读边计数
        if (length < 0) {
            try {
                chain.doFilter(new LimitedRequest(httpRequest, httpResponse, MAX_BODY_BYTES), httpResponse);
            } catch (IOException e) {
                if (isLimitExceeded(e)) {
                    //超限时已在读取线程内直接写回 413 并提交响应，这里只负责结束处理
                    return;
                }
                throw e;
            } catch (ServletException e) {
                if (isLimitExceeded(e)) {
                    return;
                }
                throw e;
            }
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse httpResponse) throws IOException {
        httpResponse.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write(
                "{\"code\":413,\"success\":false,\"message\":\"请求体过大，请压缩图片后重试！\",\"content\":null}");
    }

    /** 沿异常链找「超限」标记（Spring 可能把 IOException 包成 ServletException） */
    private boolean isLimitExceeded(Throwable error) {
        Throwable cause = error;
        int depth = 0;
        while (cause != null && depth++ < 10) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void destroy() {
        //无需清理
    }

    /** 请求体超限时抛出的标记异常 */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    /** 给请求包一层限长输入流；字符流走同一个输入流的包装，避免 getReader 绕过 */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private final HttpServletResponse response;
        private ServletInputStream stream;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request, HttpServletResponse response, long limit) {
            super(request);
            this.limit = limit;
            this.response = response;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called");
            }
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), limit, response);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (stream != null) {
                throw new IllegalStateException("getInputStream() has already been called");
            }
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(
                        new LimitedInputStream(super.getInputStream(), limit, response), StandardCharsets.UTF_8));
            }
            return reader;
        }
    }

    /** 计数输入流：累计读取超过上限立即写回 413 并抛异常中断读取 */
    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private final HttpServletResponse response;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long limit, HttpServletResponse response) {
            this.delegate = delegate;
            this.limit = limit;
            this.response = response;
        }

        private void count(long n) throws IOException {
            if (n <= 0) {
                return;
            }
            read += n;
            if (read > limit) {
                abort();
            }
        }

        /**
         * 直接写回 413 并提交响应。
         * 不能只抛异常：Spring 会把「读请求体失败」统一解析成 400，
         * 调用方就看不到「请求体过大」这个明确结论。响应一旦提交，后续的异常解析也改不了状态码。
         */
        private void abort() throws IOException {
            if (response != null && !response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":413,\"success\":false,\"message\":\"请求体过大，请压缩图片后重试！\",\"content\":null}");
                response.flushBuffer();
            }
            throw new BodyTooLargeException();
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            count(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = delegate.skip(n);
            count(skipped);
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
