package com.mgr.transcribe;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivityV2 extends Activity {
    private static final int PICK_AUDIO = 1001;
    private static final long MAX_BYTES = 25L * 1024L * 1024L;
    private static final String[] MODELS = {"gpt-4o-mini-transcribe", "gpt-4o-transcribe"};

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build();

    private Uri selectedUri;
    private String selectedName = "audio";
    private long selectedSize = -1;

    private TextView fileInfo, status;
    private EditText transcript, keyInput;
    private Button transcribeBtn, saveBtn, copyBtn;
    private ProgressBar progress;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        keyInput.setText(getPreferences(MODE_PRIVATE).getString("api_key", ""));
        updateReady();
    }

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        sc.addView(root);

        root.addView(label("MGR Transcribe", 30, true));
        TextView sub = label("Arquivo de áudio • máximo 25 MB", 15, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        Button pick = button("Selecionar áudio");
        pick.setOnClickListener(v -> pickAudio());
        root.addView(pick);

        fileInfo = label("Nenhum arquivo selecionado.", 14, false);
        root.addView(fileInfo);

        transcribeBtn = button("Transcrever");
        transcribeBtn.setOnClickListener(v -> startTranscription());
        root.addView(transcribeBtn);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(42)));

        status = label("Selecione um áudio para começar.", 14, false);
        root.addView(status);

        root.addView(label("Transcrição", 18, true));
        transcript = new EditText(this);
        transcript.setHint("O texto aparecerá aqui.");
        transcript.setMinLines(12);
        transcript.setGravity(Gravity.TOP | Gravity.START);
        transcript.setTextSize(16);
        transcript.setBackgroundColor(Color.WHITE);
        root.addView(transcript, new LinearLayout.LayoutParams(-1, -2));

        copyBtn = button("Copiar texto");
        copyBtn.setOnClickListener(v -> copyText());
        root.addView(copyBtn);

        saveBtn = button("Salvar TXT em Downloads");
        saveBtn.setOnClickListener(v -> saveTxt());
        root.addView(saveBtn);

        root.addView(label("Chave da API OpenAI", 17, true));
        keyInput = new EditText(this);
        keyInput.setHint("sk-...");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(keyInput, new LinearLayout.LayoutParams(-1, -2));

        Button saveKey = button("Salvar chave");
        saveKey.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit()
                    .putString("api_key", keyInput.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "Chave salva.", Toast.LENGTH_SHORT).show();
            updateReady();
        });
        root.addView(saveKey);

        return sc;
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(25, 35, 52));
        if (bold) t.setTypeface(t.getTypeface(), 1);
        t.setPadding(0, dp(7), 0, dp(7));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, PICK_AUDIO);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_AUDIO || res != RESULT_OK || data == null || data.getData() == null) return;

        selectedUri = data.getData();
        try {
            final int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(selectedUri, flags);
        } catch (Exception ignored) {}

        selectedName = queryName(selectedUri);
        selectedSize = querySize(selectedUri);
        if (selectedSize < 0) selectedSize = measure(selectedUri);

        if (selectedSize < 0) {
            fileInfo.setText(selectedName + "\nTamanho desconhecido");
            status.setText("Não foi possível verificar o tamanho.");
            selectedUri = null;
            updateReady();
            return;
        }

        fileInfo.setText(selectedName + "\n" + String.format(Locale.US, "%.2f MB", selectedSize / 1048576.0));

        if (selectedSize > MAX_BYTES) {
            status.setText("Arquivo acima do limite de 25 MB.");
            selectedUri = null;
        } else {
            status.setText("Arquivo pronto para transcrição.");
        }

        transcript.setText("");
        updateReady();
    }

    private void updateReady() {
        if (transcribeBtn != null) {
            transcribeBtn.setEnabled(selectedUri != null
                    && !keyInput.getText().toString().trim().isEmpty()
                    && progress.getVisibility() != View.VISIBLE);
        }
    }

    private void startTranscription() {
        if (selectedUri == null) {
            toast("Selecione um áudio válido.");
            return;
        }

        String key = keyInput.getText().toString().trim();
        if (key.isEmpty()) {
            toast("Informe a chave da API.");
            return;
        }

        setBusy(true, "Preparando o arquivo...");

        pool.execute(() -> {
            try {
                byte[] audio = readSelectedAudio();
                runOnUiThread(() -> status.setText("Enviando e transcrevendo..."));
                TranscriptionResult result = transcribeWithFallback(key, audio);
                runOnUiThread(() -> {
                    transcript.setText(result.text);
                    setBusy(false, "Transcrição concluída com " + result.model + ".");
                });
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
                String finalMessage = message;
                runOnUiThread(() -> {
                    setBusy(false, "Falha na transcrição.");
                    new AlertDialog.Builder(this)
                            .setTitle("Erro")
                            .setMessage(finalMessage)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private byte[] readSelectedAudio() throws Exception {
        if (selectedUri == null) throw new Exception("Nenhum áudio selecionado.");

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                selectedSize > 0 && selectedSize < Integer.MAX_VALUE ? (int) selectedSize : 8192);

        try (InputStream in = getContentResolver().openInputStream(selectedUri)) {
            if (in == null) throw new Exception("Não foi possível abrir o arquivo selecionado.");
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) throw new Exception("O arquivo excede o limite de 25 MB.");
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private TranscriptionResult transcribeWithFallback(String apiKey, byte[] audio) throws Exception {
        Exception last = null;

        for (String model : MODELS) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    return transcribeOnce(apiKey, audio, model);
                } catch (ApiException e) {
                    last = e;
                    if (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 404 || e.code == 429) {
                        throw e;
                    }
                    if (e.code >= 500 && e.code <= 599 && attempt < 2) {
                        Thread.sleep(1500L * attempt);
                        continue;
                    }
                    break;
                } catch (IOException e) {
                    last = e;
                    if (attempt < 2) {
                        Thread.sleep(1500L * attempt);
                        continue;
                    }
                    break;
                }
            }
        }

        if (last != null) throw last;
        throw new Exception("Não foi possível concluir a transcrição.");
    }

    private TranscriptionResult transcribeOnce(String apiKey, byte[] audio, String model) throws Exception {
        String mime = inferMime(selectedName);
        MediaType mediaType = MediaType.parse(mime);
        RequestBody audioBody = RequestBody.create(audio, mediaType);

        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "pt")
                .addFormDataPart("response_format", "json")
                .addFormDataPart("file", selectedName, audioBody)
                .build();

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "MGR-Transcribe/1.0.2 Android")
                .post(multipart)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            String requestId = response.header("x-request-id", "");

            if (!response.isSuccessful()) {
                String detail = body;
                try {
                    JSONObject j = new JSONObject(body);
                    JSONObject er = j.optJSONObject("error");
                    if (er != null) detail = er.optString("message", body);
                } catch (Exception ignored) {}

                String suffix = requestId.isEmpty() ? "" : "\nRequest ID: " + requestId;
                throw new ApiException(response.code(), "HTTP " + response.code() + " usando " + model + ": " + detail + suffix);
            }

            JSONObject json = new JSONObject(body);
            String text = json.optString("text", "").trim();
            if (text.isEmpty()) throw new Exception("A API respondeu sem texto.");
            return new TranscriptionResult(text, model);
        }
    }

    private String inferMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".mp3") || n.endsWith(".mpeg") || n.endsWith(".mpga")) return "audio/mpeg";
        if (n.endsWith(".mp4") || n.endsWith(".m4a") || n.endsWith(".m4b")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".webm")) return "audio/webm";
        if (n.endsWith(".flac")) return "audio/flac";
        String fromResolver = getContentResolver().getType(selectedUri);
        if (fromResolver != null && !fromResolver.trim().isEmpty()) return fromResolver;
        return "application/octet-stream";
    }

    private static class TranscriptionResult {
        final String text;
        final String model;
        TranscriptionResult(String text, String model) {
            this.text = text;
            this.model = model;
        }
    }

    private static class ApiException extends Exception {
        final int code;
        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private void copyText() {
        String s = transcript.getText().toString();
        if (s.trim().isEmpty()) {
            toast("Não há texto para copiar.");
            return;
        }
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("MGR Transcribe", s));
        toast("Texto copiado.");
    }

    private void saveTxt() {
        String s = transcript.getText().toString().trim();
        if (s.isEmpty()) {
            toast("Não há transcrição para salvar.");
            return;
        }

        try {
            String base = selectedName.replaceAll("\\.[^.]+$", "").replaceAll("[^a-zA-Z0-9._-]", "_");
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            String fn = "MGR_" + base + "_" + stamp + ".txt";

            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fn);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("Não foi possível criar o TXT.");

            try (OutputStream out = getContentResolver().openOutputStream(u)) {
                if (out == null) throw new Exception("Não foi possível abrir o arquivo TXT.");
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            toast("TXT salvo em Downloads.");
        } catch (Exception e) {
            new AlertDialog.Builder(this)
                    .setTitle("Erro ao salvar")
                    .setMessage(e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void setBusy(boolean busy, String msg) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(msg);
        transcribeBtn.setEnabled(!busy && selectedUri != null && !keyInput.getText().toString().trim().isEmpty());
        copyBtn.setEnabled(!busy);
        saveBtn.setEnabled(!busy);
    }

    private String queryName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        }
        return "audio";
    }

    private long querySize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        }
        return -1;
    }

    private long measure(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return -1;
            long t = 0;
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) {
                t += n;
                if (t > MAX_BYTES) return t;
            }
            return t;
        } catch (Exception e) {
            return -1;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
