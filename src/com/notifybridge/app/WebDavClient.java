package com.notifybridge.app;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 WebDAV 客户端：测试连接(PROPFIND)、创建目录(MKCOL)、上传(PUT)。
 *
 * v3 完全重写：放弃 HttpURLConnection.setRequestMethod（Android 白名单拒绝
 * PROPFIND/MKCOL，反射方案跨 ROM 不可靠），改用原生 Socket 手写 HTTP/1.1 请求。
 * 优点：完全受控，支持任意方法，超时精确，不依赖网络栈实现。适用于 http:// 明文地址。
 */
public final class WebDavClient {
    private static final String TAG = "WebDavClient";

    private final String host;
    private final int port;
    private final String basePath;
    private final String authHeader;

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 8000;
    private final String userAgent = "NotifyBridge/3.0";

    public WebDavClient(String baseUrl, String user, String pass) throws IOException {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IOException("服务器地址为空");
        }
        URL u = parseUrl(baseUrl.trim());
        this.host = u.getHost();
        this.port = u.getPort() > 0 ? u.getPort() : (u.getDefaultPort() > 0 ? u.getDefaultPort() : 80);
        String p = u.getPath();
        if (p == null || p.isEmpty()) p = "/";
        if (!p.endsWith("/")) p = p + "/";
        this.basePath = p;
        String cred = (user == null ? "" : user) + ":" + (pass == null ? "" : pass);
        this.authHeader = "Basic " + Base64.encodeToString(
                cred.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static URL parseUrl(String s) throws IOException {
        // 仅支持 http://
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            throw new IOException("仅支持 http/https 地址");
        }
        try {
            return new URL(s);
        } catch (Exception e) {
            throw new IOException("地址格式错误: " + e.getMessage(), e);
        }
    }

    /** 建立 TCP 连接并封装请求头。 */
    private Socket connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
        s.setSoTimeout(READ_TIMEOUT);
        s.setTcpNoDelay(true);
        return s;
    }

    /**
     * 发送一个 HTTP 方法请求，返回响应状态码。
     * @param method   如 PROPFIND / MKCOL / PUT / DELETE
     * @param urlPath  目标资源路径（相对 basePath 或绝对路径）
     * @param body     请求体（PUT 用；其他传 null）
     * @param contentType PUT 的内容类型
     */
    private int request(String method, String urlPath, byte[] body, String contentType)
            throws IOException {
        // 拼绝对路径
        String abs;
        if (urlPath.startsWith("/")) {
            abs = urlPath;
        } else {
            abs = basePath + urlPath;
        }
        String path = abs;
        // 拼接请求行
        StringBuilder req = new StringBuilder();
        req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host);
        if (!(port == 80 || port == 443)) req.append(":").append(port);
        req.append("\r\n");
        req.append("Authorization: ").append(authHeader).append("\r\n");
        req.append("User-Agent: ").append(userAgent).append("\r\n");
        if ("PROPFIND".equals(method)) {
            req.append("Depth: 0\r\n");
        }
        req.append("Accept: */*\r\n");
        req.append("Connection: close\r\n");
        if (body != null) {
            req.append("Content-Type: ").append(contentType == null ? "application/octet-stream" : contentType).append("\r\n");
            req.append("Content-Length: ").append(body.length).append("\r\n");
        }
        req.append("\r\n");

        Socket s = connect();
        try {
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            if (body != null) {
                os.write(body);
                os.flush();
            }

            // 读取响应头 + 状态行
            InputStream is = s.getInputStream();
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int state = 0; // 解析 \r\n\r\n
            int prev = -1;
            while (true) {
                int b = is.read();
                if (b == -1) {
                    if (headerBuf.size() == 0) throw new IOException("连接被关闭，无响应");
                    break;
                }
                headerBuf.write(b);
                if (prev == '\r' && b == '\n') {
                    // 检测到 \r\n\r\n 表示头结束（需累计两个）
                    if (headerBuf.size() >= 4) {
                        byte[] h = headerBuf.toByteArray();
                        if (h[headerBuf.size()-4]=='\r' && h[headerBuf.size()-3]=='\n'
                                && h[headerBuf.size()-2]=='\r' && h[headerBuf.size()-1]=='\n') {
                            break;
                        }
                    }
                }
                prev = b;
                if (headerBuf.size() > 65536) throw new IOException("响应头过大");
            }

            String headerStr = headerBuf.toString("ISO-8859-1");
            int sp1 = headerStr.indexOf(' ');
            int sp2 = headerStr.indexOf(' ', sp1 + 1);
            int code;
            try {
                code = Integer.parseInt(headerStr.substring(sp1 + 1, sp2));
            } catch (Exception e) {
                throw new IOException("无法解析 HTTP 状态码: " + headerStr.substring(0, Math.min(60, headerStr.length())));
            }

            // 读取剩余 body（忽略内容，仅确认读取完成，防断连）
            try {
                byte[] buf = new byte[4096];
                // 尽量读完 body，避免过早 close 触发 RST
                while (is.read(buf) != -1) { /* drain */ }
            } catch (IOException ignoreDrain) {
                // 忽略 body 读取异常（Connection: close 时正常）
            }
            return code;
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    /** 测试连接：对根发 PROPFIND，2xx/207 即成功。 */
    public String test() throws IOException {
        int code = request("PROPFIND", "/", null, null);
        if (code == 200 || code == 201 || code == 207) {
            return "OK (" + code + ")";
        } else if (code == 301 || code == 302) {
            return "重定向(" + code + ")";
        } else if (code == 401 || code == 403) {
            throw new IOException("认证失败 HTTP " + code);
        } else if (code == 404) {
            throw new IOException("路径不存在 HTTP " + code);
        } else {
            throw new IOException("HTTP " + code);
        }
    }

    /**
     * 下载远端文本内容（GET）。资源不存在(404)返回 null，其他错误抛异常。
     * 用于上传前与本地 daily note 做一致性比对。
     */
    public String fetchText(String relPath) throws IOException {
        String abs;
        if (relPath.startsWith("/")) {
            abs = relPath;
        } else {
            abs = basePath + relPath;
        }
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(abs).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host);
        if (!(port == 80 || port == 443)) req.append(":").append(port);
        req.append("\r\n");
        req.append("Authorization: ").append(authHeader).append("\r\n");
        req.append("User-Agent: ").append(userAgent).append("\r\n");
        req.append("Accept: */*\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");

        Socket s = connect();
        try {
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            os.flush();

            InputStream is = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            // 读响应头直到 \r\n\r\n
            boolean headerDone = false;
            while (!headerDone) {
                int b = is.read();
                if (b == -1) {
                    if (head.size() == 0) throw new IOException("连接被关闭，无响应");
                    break;
                }
                head.write(b);
                byte[] h = head.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n-4]=='\r' && h[n-3]=='\n' && h[n-2]=='\r' && h[n-1]=='\n') {
                    headerDone = true;
                }
                if (n > 65536) throw new IOException("响应头过大");
            }

            String headerStr = head.toString("ISO-8859-1");
            int sp1 = headerStr.indexOf(' ');
            int sp2 = headerStr.indexOf(' ', sp1 + 1);
            int code;
            try {
                code = Integer.parseInt(headerStr.substring(sp1 + 1, sp2));
            } catch (Exception e) {
                throw new IOException("无法解析 HTTP 状态码: " +
                        headerStr.substring(0, Math.min(60, headerStr.length())));
            }
            if (code == 404) return null;
            if (code < 200 || code >= 300) {
                throw new IOException("下载失败 HTTP " + code);
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // 跳过空行与已读缓冲中的 body
            byte[] all = head.toByteArray();
            int bodyStart = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i]=='\r' && all[i+1]=='\n' && all[i+2]=='\r' && all[i+3]=='\n') {
                    bodyStart = i + 4;
                    break;
                }
            }
            if (bodyStart >= 0 && bodyStart < all.length) {
                body.write(all, bodyStart, all.length - bodyStart);
            }
            byte[] buf = new byte[4096];
            int rd;
            while ((rd = is.read(buf)) != -1) body.write(buf, 0, rd);
            return body.toString("UTF-8");
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }

    /** 递归创建目录。405/409=已存在视为成功。 */
    public void ensureDir(String path) throws IOException {
        if (path == null) return;
        String[] segs = path.split("/");
        StringBuilder cur = new StringBuilder(basePath);
        for (String seg : segs) {
            if (seg.isEmpty()) continue;
            cur.append(seg).append("/");
            int code = request("MKCOL", cur.toString(), null, null);
            // 201=新建；405/409/301/302/200=目录已存在或已就绪，视为成功
            if (code != 201 && code != 405 && code != 409 && code != 301 && code != 302 && code != 200) {
                throw new IOException("创建目录失败 HTTP " + code + " @ " + seg);
            }
        }
    }

    /** 上传文本到相对路径。自动创建父目录。 */
    public void uploadText(String relPath, String content) throws IOException {
        int slash = relPath.lastIndexOf('/');
        if (slash > 0) {
            ensureDir(relPath.substring(0, slash));
        }
        byte[] body = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        // 网络层重试一次（应对间歇性连接问题）
        int code = -1;
        IOException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                code = request("PUT", relPath, body, "text/markdown; charset=utf-8");
                lastErr = null;
                break;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        if (lastErr != null) throw lastErr;
        if (code < 200 || code >= 300) {
            throw new IOException("上传失败 HTTP " + code);
        }
    }

    public String getBaseUrl() { return "http://" + host + (port != 80 ? ":" + port : "") + basePath; }
}
