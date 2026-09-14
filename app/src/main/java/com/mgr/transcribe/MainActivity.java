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
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PICK_AUDIO=1001;
    private static final long MAX_BYTES=25L*1024L*1024L;
    private final ExecutorService pool=Executors.newSingleThreadExecutor();
    private Uri selectedUri;
    private String selectedName="audio";
    private long selectedSize=-1;
    private TextView fileInfo,status;
    private EditText transcript,keyInput;
    private Button transcribeBtn,saveBtn,copyBtn;
    private ProgressBar progress;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(buildUi());
        keyInput.setText(getPreferences(MODE_PRIVATE).getString("api_key",""));
        updateReady();
    }

    private View buildUi(){
        ScrollView sc=new ScrollView(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(18)); root.setBackgroundColor(Color.rgb(247,249,252)); sc.addView(root);
        root.addView(label("MGR Transcribe",30,true));
        TextView sub=label("Arquivo de áudio • máximo 25 MB",15,false); sub.setTextColor(Color.DKGRAY); root.addView(sub);
        Button pick=button("Selecionar áudio"); pick.setOnClickListener(v->pickAudio()); root.addView(pick);
        fileInfo=label("Nenhum arquivo selecionado.",14,false); root.addView(fileInfo);
        transcribeBtn=button("Transcrever"); transcribeBtn.setOnClickListener(v->startTranscription()); root.addView(transcribeBtn);
        progress=new ProgressBar(this); progress.setIndeterminate(true); progress.setVisibility(View.GONE); root.addView(progress,new LinearLayout.LayoutParams(-1,dp(42)));
        status=label("Selecione um áudio para começar.",14,false); root.addView(status);
        root.addView(label("Transcrição",18,true));
        transcript=new EditText(this); transcript.setHint("O texto aparecerá aqui."); transcript.setMinLines(12); transcript.setGravity(Gravity.TOP|Gravity.START); transcript.setTextSize(16); transcript.setBackgroundColor(Color.WHITE); root.addView(transcript,new LinearLayout.LayoutParams(-1,-2));
        copyBtn=button("Copiar texto"); copyBtn.setOnClickListener(v->copyText()); root.addView(copyBtn);
        saveBtn=button("Salvar TXT em Downloads"); saveBtn.setOnClickListener(v->saveTxt()); root.addView(saveBtn);
        root.addView(label("Chave da API OpenAI",17,true));
        keyInput=new EditText(this); keyInput.setHint("sk-..."); keyInput.setSingleLine(true); keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(keyInput,new LinearLayout.LayoutParams(-1,-2));
        Button saveKey=button("Salvar chave"); saveKey.setOnClickListener(v->{ getPreferences(MODE_PRIVATE).edit().putString("api_key",keyInput.getText().toString().trim()).apply(); Toast.makeText(this,"Chave salva.",Toast.LENGTH_SHORT).show(); updateReady();}); root.addView(saveKey);
        return sc;
    }

    private TextView label(String s,int sp,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.rgb(25,35,52)); if(bold)t.setTypeface(t.getTypeface(),1); t.setPadding(0,dp(7),0,dp(7)); return t; }
    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(6)); b.setLayoutParams(lp); return b; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }

    private void pickAudio(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*"); startActivityForResult(i,PICK_AUDIO); }

    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req!=PICK_AUDIO||res!=RESULT_OK||data==null||data.getData()==null)return;
        selectedUri=data.getData(); selectedName=queryName(selectedUri); selectedSize=querySize(selectedUri); if(selectedSize<0) selectedSize=measure(selectedUri);
        if(selectedSize<0){ fileInfo.setText(selectedName+"\nTamanho desconhecido"); status.setText("Não foi possível verificar o tamanho."); selectedUri=null; updateReady(); return; }
        fileInfo.setText(selectedName+"\n"+String.format(Locale.US,"%.2f MB",selectedSize/1048576.0));
        if(selectedSize>MAX_BYTES){ status.setText("Arquivo acima do limite de 25 MB."); selectedUri=null; } else status.setText("Arquivo pronto para transcrição.");
        transcript.setText(""); updateReady();
    }

    private void updateReady(){ if(transcribeBtn!=null) transcribeBtn.setEnabled(selectedUri!=null && !keyInput.getText().toString().trim().isEmpty() && progress.getVisibility()!=View.VISIBLE); }

    private void startTranscription(){
        if(selectedUri==null){ toast("Selecione um áudio válido."); return; }
        String key=keyInput.getText().toString().trim(); if(key.isEmpty()){ toast("Informe a chave da API."); return; }
        setBusy(true,"Enviando e transcrevendo...");
        pool.execute(()->{ try{ String result=transcribe(key); runOnUiThread(()->{ transcript.setText(result); setBusy(false,"Transcrição concluída.");}); }catch(Exception e){ runOnUiThread(()->{ setBusy(false,"Falha na transcrição."); new AlertDialog.Builder(this).setTitle("Erro").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}); }});
    }

    private String transcribe(String apiKey) throws Exception{
        String boundary="----MGR"+System.currentTimeMillis();
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(30000); c.setReadTimeout(300000); c.setChunkedStreamingMode(65536); c.setRequestProperty("Authorization","Bearer "+apiKey); c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try(OutputStream out=c.getOutputStream()){
            part(out,boundary,"model","gpt-4o-mini-transcribe"); part(out,boundary,"language","pt");
            String mime=getContentResolver().getType(selectedUri); if(mime==null)mime="application/octet-stream";
            String head="--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+selectedName.replace("\"","_")+"\"\r\nContent-Type: "+mime+"\r\n\r\n"; out.write(head.getBytes(StandardCharsets.UTF_8));
            try(InputStream in=getContentResolver().openInputStream(selectedUri)){ byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1)out.write(buf,0,n); }
            out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code=c.getResponseCode(); String body=readAll(code>=200&&code<300?c.getInputStream():c.getErrorStream()); c.disconnect();
        if(code<200||code>=300){ try{ JSONObject j=new JSONObject(body); JSONObject er=j.optJSONObject("error"); if(er!=null)body=er.optString("message",body);}catch(Exception ignored){} throw new Exception("HTTP "+code+": "+body); }
        String text=new JSONObject(body).optString("text","").trim(); if(text.isEmpty())throw new Exception("A API respondeu sem texto."); return text;
    }

    private void part(OutputStream out,String b,String n,String v)throws Exception{ out.write(("--"+b+"\r\nContent-Disposition: form-data; name=\""+n+"\"\r\n\r\n"+v+"\r\n").getBytes(StandardCharsets.UTF_8)); }
    private String readAll(InputStream in)throws Exception{ if(in==null)return ""; ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] x=new byte[8192]; int n; while((n=in.read(x))!=-1)b.write(x,0,n); return b.toString("UTF-8"); }

    private void copyText(){ String s=transcript.getText().toString(); if(s.trim().isEmpty()){toast("Não há texto para copiar.");return;} ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MGR Transcribe",s)); toast("Texto copiado."); }

    private void saveTxt(){
        String s=transcript.getText().toString().trim(); if(s.isEmpty()){toast("Não há transcrição para salvar.");return;}
        try{
            String base=selectedName.replaceAll("\\.[^.]+$","").replaceAll("[^a-zA-Z0-9._-]","_"); String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date()); String fn="MGR_"+base+"_"+stamp+".txt";
            ContentValues v=new ContentValues(); v.put(MediaStore.Downloads.DISPLAY_NAME,fn); v.put(MediaStore.Downloads.MIME_TYPE,"text/plain"); v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v); if(u==null)throw new Exception("Não foi possível criar o TXT.");
            try(OutputStream out=getContentResolver().openOutputStream(u)){ out.write(s.getBytes(StandardCharsets.UTF_8)); }
            toast("TXT salvo em Downloads.");
        }catch(Exception e){ new AlertDialog.Builder(this).setTitle("Erro ao salvar").setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
    }

    private void setBusy(boolean busy,String msg){ progress.setVisibility(busy?View.VISIBLE:View.GONE); status.setText(msg); transcribeBtn.setEnabled(!busy&&selectedUri!=null&&!keyInput.getText().toString().trim().isEmpty()); copyBtn.setEnabled(!busy); saveBtn.setEnabled(!busy); }
    private String queryName(Uri u){ try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){ if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0)return c.getString(i);} } return "audio"; }
    private long querySize(Uri u){ try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.SIZE},null,null,null)){ if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE); if(i>=0&&!c.isNull(i))return c.getLong(i);} } return -1; }
    private long measure(Uri u){ try(InputStream in=getContentResolver().openInputStream(u)){ long t=0; byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1){t+=n;if(t>MAX_BYTES)return t;} return t; }catch(Exception e){return -1;} }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy(){ super.onDestroy(); pool.shutdownNow(); }
}
