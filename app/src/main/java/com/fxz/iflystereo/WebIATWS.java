package com.fxz.iflystereo;

import com.google.gson.JsonObject;
import okhttp3.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 语音听写流式 WebAPI 接口调用示例 接口文档（必看）：https://doc.xfyun.cn/rest_api/语音听写（流式版）.html
 * webapi 听写服务参考帖子（必看）：http://bbs.xfyun.cn/forum.php?mod=viewthread&tid=38947&extra=
 * 语音听写流式WebAPI 服务，热词使用方式：登陆开放平台https://www.xfyun.cn/后，找到控制台--我的应用---语音听写---个性化热词，上传热词
 * 注意：热词只能在识别的时候会增加热词的识别权重，需要注意的是增加相应词条的识别率，但并不是绝对的，具体效果以您测试为准。
 * 错误码链接：https://www.xfyun.cn/document/error-code （code返回错误码时必看）
 * 语音听写流式WebAPI 服务，方言或小语种试用方法：登陆开放平台https://www.xfyun.cn/后，在控制台--语音听写（流式）--方言/语种处添加
 * 添加后会显示该方言/语种的参数值
 * @author iflytek
 */

public class WebIATWS {
    // 获取网络数据
    public static String getAuthUrl(String hostUrl, String apiKey, String apiSecret) throws Exception {
        URL url = new URL(hostUrl);
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());
        StringBuilder builder = new StringBuilder("host: ").append(url.getHost()).append("\n").//
                append("date: ").append(date).append("\n").//
                append("GET ").append(url.getPath()).append(" HTTP/1.1");
        //System.out.println(builder);
        Charset charset = Charset.forName("UTF-8");
        Mac mac = Mac.getInstance("hmacsha256");
        SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(charset), "hmacsha256");
        mac.init(spec);
        byte[] hexDigits = mac.doFinal(builder.toString().getBytes(charset));
        String sha = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            sha = Base64.getEncoder().encodeToString(hexDigits);
        }

        String authorization = String.format("api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"", apiKey, "hmac-sha256", "host date request-line", sha);

        HttpUrl httpUrl = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            httpUrl = HttpUrl.parse("https://" + url.getHost() + url.getPath()).newBuilder().
                    addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.getBytes(charset))).
                    addQueryParameter("date", date).
                    addQueryParameter("host", url.getHost()).
                    build();
        }
        return httpUrl.toString();
    }
    public static class ResponseData {
        private int code;
        private String message;
        private String sid;
        private Data data;
        public int getCode() {
            return code;
        }
        public String getMessage() {
            return this.message;
        }
        public String getSid() {
            return sid;
        }
        public Data getData() {
            return data;
        }
    }
    public static class Data {
        private int status;
        private Result result;
        public int getStatus() {
            return status;
        }
        public Result getResult() {
            return result;
        }
    }
    public static class Result {
        int bg;
        int ed;
        String pgs;
        int[] rg;
        int sn;
        Ws[] ws;
        boolean ls;
        JsonObject vad;
        public Text getText() {
            Text text = new Text();
            StringBuilder sb = new StringBuilder();
            for (Ws ws : this.ws) {
                sb.append(ws.cw[0].w);
            }
            text.sn = this.sn;
            text.text = sb.toString();
            text.sn = this.sn;
            text.rg = this.rg;
            text.pgs = this.pgs;
            text.bg = this.bg;
            text.ed = this.ed;
            text.ls = this.ls;
            text.vad = this.vad==null ? null : this.vad;
            return text;
        }
    }
    public static class Ws {
        Cw[] cw;
        int bg;
        int ed;
    }
    public static class Cw {
        int sc;
        String w;
    }
    public static class Text {
        int sn;
        int bg;
        int ed;
        String text;
        String pgs;
        int[] rg;
        boolean deleted;
        boolean ls;
        JsonObject vad;
        @Override
        public String toString() {
            return "Text{" +
                    "bg=" + bg +
                    ", ed=" + ed +
                    ", ls=" + ls +
                    ", sn=" + sn +
                    ", text='" + text + '\'' +
                    ", pgs=" + pgs +
                    ", rg=" + Arrays.toString(rg) +
                    ", deleted=" + deleted +
                    ", vad=" + (vad==null ? "null" : vad.getAsJsonArray("ws").toString()) +
                    '}';
        }
    }
    //解析返回数据，仅供参考
    public static class Decoder {
        private Text[] texts;
        private int defc = 10;
        public Decoder() {
            this.texts = new Text[this.defc];
        }
        public synchronized void decode(Text text) {
            if (text.sn >= this.defc) {
                this.resize();
            }
            if ("rpl".equals(text.pgs)) {
                for (int i = text.rg[0]; i <= text.rg[1]; i++) {
                    this.texts[i].deleted = true;
                }
            }
            this.texts[text.sn] = text;
        }
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Text t : this.texts) {
                if (t != null && !t.deleted) {
                    sb.append(t.text);
                }
            }
            return sb.toString();
        }
        public void resize() {
            int oc = this.defc;
            this.defc <<= 1;
            Text[] old = this.texts;
            this.texts = new Text[this.defc];
            if (oc >= 0) System.arraycopy(old, 0, this.texts, 0, oc);
        }
        public void discard(){
            Arrays.fill(this.texts, null);
        }
    }
}
