package com.fxz.iflystereo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {
    private static final int SAMPLE_RATE = 16000;  // The sampling rate

    private boolean startRecord = false;
    private AudioRecord record = null;
    private int miniBufferSize = 0;  // 1280 bytes 648 byte 40ms, 0.04s

    static final String hostUrl = "https://iat-api.xfyun.cn/v2/iat"; //中英文，http url 不支持解析 ws/wss schema
    private static final String appid = "6c157d10"; //在控制台-我的应用获取
    static final String apiSecret = "MGY0YjM4NWMyZDYyYWRlMmI2MTlhZmZk"; //在控制台-我的应用-语音听写（流式版）获取
    static final String apiKey = "8735f05eb184366efebb03483591ff41"; //在控制台-我的应用-语音听写（流式版）获取
    private static final String TAG = "MainActivity";
    public static final int StatusFirstFrame = 0;
    public static final int StatusContinueFrame = 1;
    public static final int StatusLastFrame = 2;
    public static final Gson json = new Gson();
    WebIATWS.Decoder decoder = new WebIATWS.Decoder();
    private static Date dateEnd = new Date();
    private String mFileName;

    private final int MY_PERMISSIONS_RECORD_AUDIO = 1;
    private static final String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Permission Granted");
                initRecorder();
            } else {
                Toast.makeText(this, "Permissions Denied", Toast.LENGTH_LONG).show();
                Button button = findViewById(R.id.button);
                button.setEnabled(false);
            }
        }
    }

    public static boolean hasPermissions(Context context, String[] permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, MY_PERMISSIONS_RECORD_AUDIO);
        }

        mFileName = Environment.getExternalStorageDirectory().getAbsolutePath();
        mFileName += "/audio_record.pcm";

        TextView textView = findViewById(R.id.textView);
        textView.setText("录音时，静默时长不得超过10s，说话时长最多为60s。若不为近距离说话，则只显示波形，无法识别语音。");

        Button button = findViewById(R.id.button);
        button.setText("Start Record");
        button.setOnClickListener(view -> {
            if (!startRecord) {
                startRecord = true;
                startRecordThread();
                startAsrThread();
                button.setText("Stop Record");
            } else {
                startRecord = false;  // 不是关闭服务，只要关闭录音就行
                button.setText("Start Record");
            }
            button.setEnabled(false);
        });
    }

    private void initRecorder() {
        // buffer size in bytes 1280
        miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Audio buffer can't initialize!");
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                miniBufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!");
            return;
        }
        Log.i(TAG, "Record init okay");
    }

    private void startRecordThread() {
        new Thread(() -> {
            VoiceRectView voiceView = findViewById(R.id.voiceRectView);
            if(startRecord && record != null) {
                record.startRecording();
            } else {
                startRecord = true;
                Button button = findViewById(R.id.button);
                button.setText("Stop Record");
                initRecorder();
                record.startRecording();
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);  // 声音线程的标准级别

            FileOutputStream os = null;
            try {
                os = new FileOutputStream(mFileName);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            if (null != os) {
                while (startRecord) {
                    byte[] buffer = new byte[miniBufferSize / 2];
                    byte[] buffer1 = new byte[buffer.length / 2];
                    byte[] bufferLeft = new byte[buffer.length / 2];
                    byte[] bufferRight = new byte[buffer.length / 2];
                    int read = record.read(buffer, 0, buffer.length);  // 用byte也可以

                    boolean leftV = true;
                    int leftIndex = 0, rightIndex = 0;
                    for (int i = 0; i < buffer1.length; i++) {
                        if (leftV) {
                            bufferLeft[leftIndex] = buffer[i];
                            i++;
                            leftIndex++;
                            bufferLeft[leftIndex] = buffer[i];
                            leftIndex++;
                        } else {
                            bufferRight[rightIndex] = buffer[i];
                            i++;
                            rightIndex++;
                            bufferRight[rightIndex] = buffer[i];
                            rightIndex++;
                        }
// 说明录制的是16bit，所以每个采样的数据占两位*2，先左声道（2位）后右声道（2位）
                        leftV = !leftV;
                    }
                    for (int i = 0; i < leftIndex; i++) {
                        buffer1[i] = (byte) (bufferLeft[i] - bufferRight[i]);
                    }

                    if(calculateDb(buffer1) <= 10 || (calculateDb(buffer1) > 10 && Math.abs(calculateDb(bufferLeft) - calculateDb(bufferRight)) < 0.0015 * calculateDb(buffer1))) {
                        Log.e(TAG, calculateDb(buffer1) + "!!!!!!!!!!!!!" + calculateDb(bufferLeft) + "!!!!!" + calculateDb(bufferRight));
                        for(int i = 0; i < buffer.length / 2; i++) {
                            buffer1[i] = 0;
                        }
                    }  // 是否需要校准？
                    Log.e(TAG, calculateDb(buffer1) + "!!");
                    buffer = buffer1;


                    voiceView.add(calculateDb(buffer) / 100);
                    if (AudioRecord.ERROR_INVALID_OPERATION != read) {

                        try {
                            os.write(buffer);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    Button button = findViewById(R.id.button);
                    if (!button.isEnabled() && startRecord) {
                        runOnUiThread(() -> button.setEnabled(true));
                    }
                }
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            record.stop();
            record.release();
            record = null;
            Log.e(TAG, "已关闭录音");
            voiceView.zero();
        }).start();
    }

    private double calculateDb(byte[] buffer) {  // 实际上不是dB
        double energy = 0.0;
        for (byte value : buffer) {
            energy += value * value;
        }
        energy /= buffer.length;
        energy = (10 * Math.log10(1 + energy)) / 100;
        energy = Math.min(energy, 1.0);
        return energy * 100;
    }

//    整个会话时长最多持续60s，或者超过10s未发送数据，服务端会主动断开连接
    private void startAsrThread() {
        // 构建鉴权url
        String authUrl = null;
        try {
            authUrl = WebIATWS.getAuthUrl(hostUrl, apiKey, apiSecret);
        } catch (Exception e) {
            e.printStackTrace();
        }
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)  // 设置超时时间
                .build();
        //将url中的 schema http://和https://分别替换为ws:// 和 wss://
        String url = authUrl.replace("http://", "ws://").replace("https://", "wss://");
        Request request = new Request.Builder().url(url).build();

        // 录音关闭了，还在发送中间帧
        WebSocket webSocket = client.newWebSocket(request,  // 建立连接
        new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                super.onOpen(webSocket, response);
                new Thread(() -> {
                    //连接成功，开始发送数据
                    int frameSize = 1280; //每一帧音频的大小,建议每 40ms 发送 122B
                    int intervel = 40;
                    int status = 0;  // 音频的状态

                    try (FileInputStream os = new FileInputStream(mFileName)) {
                        byte[] buffer = new byte[frameSize];
                        // 发送音频
                        end:
                        while (true) {
                            int len = os.read(buffer);
                            if (len == -1) {
                                status = StatusLastFrame;  //文件读完，改变status 为 2
                            }
                            switch (status) {
                                case StatusFirstFrame:   // 第一帧音频status = 0
                                    JsonObject frame = new JsonObject();
                                    JsonObject business = new JsonObject();  //第一帧必须发送
                                    JsonObject common = new JsonObject();  //第一帧必须发送
                                    JsonObject data = new JsonObject();  //每一帧都要发送
                                    // 填充common
                                    common.addProperty("app_id", appid);
                                    //填充business
                                    business.addProperty("language", "zh_cn");
                                    business.addProperty("domain", "iat");
                                    business.addProperty("accent", "mandarin");
                                    //business.addProperty("nunum", 0);
                                    //business.addProperty("ptt", 0);//标点符号
                                    //business.addProperty("vinfo", 1);
                                    business.addProperty("dwa", "wpgs");//动态修正(若未授权不生效，在控制台可免费开通)
                                    //填充data
                                    data.addProperty("status", StatusFirstFrame);
                                    data.addProperty("format", "audio/L16;rate=16000");
                                    data.addProperty("encoding", "raw");
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        data.addProperty("audio", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                                    }
                                    //填充frame
                                    frame.add("common", common);
                                    frame.add("business", business);
                                    frame.add("data", data);
                                    webSocket.send(frame.toString());
                                    status = StatusContinueFrame;  // 发送完第一帧改变status 为 1
                                    break;
                                case StatusContinueFrame:  //中间帧status = 1
                                    JsonObject frame1 = new JsonObject();
                                    JsonObject data1 = new JsonObject();
                                    data1.addProperty("status", StatusContinueFrame);
                                    data1.addProperty("format", "audio/L16;rate=16000");
                                    data1.addProperty("encoding", "raw");
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        data1.addProperty("audio", Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, len)));
                                    }
                                    frame1.add("data", data1);
                                    webSocket.send(frame1.toString());
                                    break;
                                case StatusLastFrame:    // 最后一帧音频status = 2 ，标志音频发送结束
                                    JsonObject frame2 = new JsonObject();
                                    JsonObject data2 = new JsonObject();
                                    data2.addProperty("status", StatusLastFrame);
                                    data2.addProperty("audio", "");
                                    data2.addProperty("format", "audio/L16;rate=16000");
                                    data2.addProperty("encoding", "raw");
                                    frame2.add("data", data2);
                                    webSocket.send(frame2.toString());
                                    break end;
                            }
                            Thread.sleep(intervel); //模拟音频采样延时
                        }
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                }).start();
            }

//            The 'end' statement is used to create a named block, which is being used to control the execution of the while loop.
//            The while loop is running until the status of the audio data is changed to 2 (StatusLastFrame).
//            The code then sends the audio data in different frames based on the status of the audio data,
//            either it is the first frame (StatusFirstFrame), a middle frame (StatusContinueFrame),
//            or the last frame (StatusLastFrame). The 'break end' statement is used to break out of the named block
//            when the last frame of audio data has been sent.

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                super.onMessage(webSocket, text);
                WebIATWS.ResponseData resp = json.fromJson(text, WebIATWS.ResponseData.class);
                if (resp != null) {
                    if (resp.getCode() != 0) {
                        Log.e(TAG, "code=>" + resp.getCode() + " error=>" + resp.getMessage() + " sid=" + resp.getSid());
                        Log.e(TAG, "错误码查询链接：https://www.xfyun.cn/document/error-code");
                        return;
                    }
                    if (resp.getData() != null) {
                        if (resp.getData().getResult() != null) {
                            WebIATWS.Text te = resp.getData().getResult().getText();
                            try {
                                decoder.decode(te);
                                Log.e(TAG, "中间语音识别结果：" + decoder.toString());
                                runOnUiThread(() -> {
                                    TextView textView = findViewById(R.id.textView);
                                    textView.setText(decoder.toString());
                                    Log.e(TAG, resp.getMessage() + " " + resp.getData().getResult().getText());
                                });
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        if (resp.getData().getStatus() == 2) {
                            // todo  resp.data.status == 2 说明数据全部返回完毕，可以关闭连接，释放资源
                            Log.e(TAG, "session end ");
                            dateEnd = new Date();
                            Log.e(TAG, "语音识别结果：" + decoder.toString());
                            Log.e(TAG, "本次识别sid：" + resp.getSid() + " code:" + resp.getCode());
                            Log.e(TAG, resp.getMessage() + " " + resp.getData().getResult().getText());

                            runOnUiThread(() -> {  // 耗时的放在子线程
                                Button button = findViewById(R.id.button);
                                button.setEnabled(true);
                                button.setText("Start Record");
                            });

                            startRecord = false;
                            decoder.discard();
                            webSocket.close(1000, "");
                        }
                    }
                }
            }


            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                super.onFailure(webSocket, t, response);
                startRecord = false;
                try {
                    if (null != response) {
                        int code = response.code();
                        Log.e(TAG, "onFailure code: " + code);
                        Log.e(TAG, "onFailure body:" + response.body().string());
                        if (101 != code) {
                            Log.e(TAG, "connection failed");
                            System.exit(0);
                        }
                    }
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Release the resources used by the AudioRecord object
        if (record != null) {
            record.release();
            record = null;
        }
        Log.e(TAG, "onDestroy: ");  // 好像没啥用但还是写了
    }
}